# ArgoCD & Argo Rollouts

## ภาพรวม

ระบบใช้ **ArgoCD** เป็น GitOps controller สำหรับ deploy application ลง Kubernetes cluster โดย ArgoCD จะ watch manifest repository และ sync ให้ cluster state ตรงกับ Git state เสมอ

นอกจากนี้ยังใช้ **Argo Rollouts** สำหรับ Canary Deployment ที่ค่อยๆ เพิ่ม traffic ไปยัง version ใหม่ พร้อม analysis อัตโนมัติก่อน promote

---

## สถาปัตยกรรม

```
Jenkins Pipeline                Manifest Repo (GitLab)           ArgoCD              K3s Cluster
      │                                │                           │                      │
      │  1. generate manifests         │                           │                      │
      │  (Kustomize base + overlay)    │                           │                      │
      │ ──────────────────────────────►│                           │                      │
      │                                │                           │                      │
      │  2. git push                   │                           │                      │
      │ ──────────────────────────────►│                           │                      │
      │                                │                           │                      │
      │  3. argocd app create/sync     │                           │                      │
      │ ──────────────────────────────────────────────────────────►│                      │
      │                                │                           │                      │
      │                                │   4. detect diff          │                      │
      │                                │◄──────────────────────────│                      │
      │                                │                           │                      │
      │                                │                           │  5. apply manifests  │
      │                                │                           │ ────────────────────►│
      │                                │                           │                      │
      │  6. argocd app wait (health)   │                           │                      │
      │ ──────────────────────────────────────────────────────────►│                      │
      │                                │                           │                      │
```

---

## ArgoCD Application

Pipeline สร้าง ArgoCD Application สูงสุด 2 ตัวต่อ project ต่อ environment:

| App Name | Path | ใช้เมื่อ |
| --- | --- | --- |
| `{project}-app-{env}` | `overlays/{env}/app/` | deploy API/Web app (`doDeploy=true`) |
| `{project}-cronjob-{env}` | `overlays/{env}/cronjob/` | deploy CronJob (`deployCronJobs=Yes`) |

### ตัวอย่างจริงในระบบ

| Service | App Name | Environment |
| --- | --- | --- |
| Frontend | `test-jenkins-app-app-prd` | prd |
| Backend | `test-be-api-app-prd` | prd |
| Backend CronJob | `test-be-api-cronjob-prd` | prd |

---

## Flow การทำงาน (argocd.groovy)

### 1. Login

```
argocd login {ARGOCD_HOST} --username $USER --password $PASS --insecure --grpc-web
```

### 2. Register Manifest Repository

```
argocd repo add {manifestRepoUrl} --username $GITLAB_USER --password $GITLAB_PASS --upsert
```

ลงทะเบียน GitLab repo ให้ ArgoCD สามารถ pull manifests ได้

### 3. Create/Update Application

```
argocd app create {appName} \
    --repo {manifestRepoUrl} \
    --path overlays/{env}/app \
    --dest-server https://kubernetes.default.svc \
    --dest-namespace {namespace} \
    --revision {projectName}-manifest \
    --project default \
    {syncPolicy} \
    --upsert
```

**Sync Policy:**

| autoSync | Policy | พฤติกรรม |
| --- | --- | --- |
| Yes | `--sync-policy automated --auto-prune --self-heal` | ArgoCD sync อัตโนมัติเมื่อ Git เปลี่ยน + ลบ resource ที่ไม่มีใน Git + แก้ไข drift อัตโนมัติ |
| No | `--sync-policy none` | ต้อง sync manual ผ่าน Discord Bot หรือ ArgoCD UI |

### 4. Sync & Wait

ขึ้นอยู่กับ deployment strategy:

**Standard (Rolling Update):**

```
argocd app sync {appName} --timeout 120
argocd app wait {appName} --timeout 180 --health --sync
```

รอให้ app healthy + synced

**Canary (Argo Rollouts):**

```
argocd app sync {appName} --timeout 120
argocd app wait {appName} --timeout 180 --sync
```

รอแค่ synced เท่านั้น ไม่ต้อง healthy เพราะ Rollout จะ pause อยู่ที่ 0% รอ DAST scan

---

## Manual Sync Flow (autoSync=No)

เมื่อตั้ง `autoSync: 'No'` ใน Jenkinsfile pipeline จะ:

1. สร้าง ArgoCD app แต่**ไม่ sync** (`skipSync=true`)
2. ส่ง Discord prompt ให้ user approve (ผ่าน Discord Bot ภายนอก)
3. รอ input 30 นาที
4. ถ้า **Proceed** -> เรียก `argocd.syncApp()` sync on-demand
5. ถ้า **Skip/Timeout** -> ข้าม sync (`syncSkipped=true`)

```
Pipeline         Discord Bot (external)     Discord          User
   │                   │                       │               │
   ├─ create app       │                       │               │
   │  (skipSync)       │                       │               │
   ├─ POST ───────────►│                       │               │
   │                   ├── embed ─────────────►│               │
   │                   │   [Proceed] [Skip]    │               │
   │                   │                       │◄── click ─────┤
   │◄── input ─────────┤                       │               │
   ├─ sync or skip     │                       │               │
```

---

## Canary Deployment (Argo Rollouts)

### ภาพรวม

Argo Rollouts ใช้ CRD `Rollout` แทน `Deployment` เพื่อรองรับ progressive delivery ระบบจะค่อยๆ เพิ่ม traffic ไปยัง version ใหม่ตาม steps ที่กำหนด

### Canary Steps (ตัวอย่าง Frontend)

```yaml
strategy:
  canary:
    stableService: test-jenkins-app-service-stable
    canaryService: test-jenkins-app-service-canary
    steps:
      - setWeight: 25          # ส่ง 25% traffic ไปยัง canary
      - pause: { duration: 15s }
      - analysis:              # Prometheus analysis (error rate, restarts)
          templates:
            - templateName: test-jenkins-app-analysis-prometheus
      - setWeight: 50          # เพิ่มเป็น 50%
      - analysis:              # Prometheus analysis อีกรอบ
          templates:
            - templateName: test-jenkins-app-analysis-prometheus
      - pause: {}              # หยุดรอ manual approve
      - setWeight: 100         # promote เต็ม 100%
```

### Service แยก Stable/Canary

| Service | ชี้ไปที่ | ใช้งาน |
| --- | --- | --- |
| `{project}-service-stable` | Pods version เดิม (stable) | traffic ปกติ |
| `{project}-service-canary` | Pods version ใหม่ (canary) | traffic ทดสอบ + DAST scan |

Canary service ใช้ NodePort offset +100 (เช่น stable=30001, canary=30101) สำหรับ DAST scan

### Flow Canary + DAST

```
  Deploy version ใหม่
         │
    setWeight: 0%  ◄── Rollout pause ที่นี่
         │
         ▼
  ┌──────────────┐
  │  DAST Scan   │   OWASP ZAP scan ยิงไปที่ canary service
  │  (OWASP ZAP) │   http://{argoIP}:{nodePort+100}
  └──────┬───────┘
         │
    PASS?──── No ──► argocd app actions run {app} abort
         │                (abort rollout, revert to stable)
        Yes
         │
         ▼
    argocd app actions run {app} resume
         │
    setWeight: 25%
         │
    pause 15s
         │
    AnalysisRun (Prometheus)
         │
    setWeight: 50%
         │
    AnalysisRun (Prometheus)
         │
    pause (manual)
         │
    setWeight: 100%  ──► Full promotion
```

---

## Analysis Templates

Argo Rollouts รองรับ AnalysisRun อัตโนมัติ 2 แบบ:

### 1. Newman Smoke Test

ทดสอบ API endpoints ด้วย Postman collection ผ่าน Newman

```yaml
kind: AnalysisTemplate
metadata:
  name: {project}-analysis-newman

# ทำงานโดย:
# 1. สร้าง Job ใน cluster
# 2. รัน newman (Postman CLI) ใน container
# 3. ยิง request ไปที่ canary service URL
# 4. ตรวจสอบ response ตาม collection
```

**Parameters:**

| Arg | ค่า Default | อธิบาย |
| --- | --- | --- |
| `url` | `http://{canary-svc}:{port}` | Base URL ที่จะทดสอบ |
| `timeout` | 10 | Request timeout (วินาที) |
| `count` | 3 | จำนวนรอบที่รัน |
| `failure` | 1 | จำนวน failure ที่ยอมรับได้ |
| `smoke_interval` | 5s | ระยะห่างระหว่างรอบ |
| `configMapName` | `{project}-smoke-test-config` | ConfigMap ที่เก็บ Postman collection |

### 2. Prometheus Metrics Analysis

ตรวจสอบ metrics จาก Prometheus เพื่อดูว่า canary มีปัญหาหรือไม่

**Metrics ที่ตรวจ:**

| Metric | Query | เงื่อนไขผ่าน |
| --- | --- | --- |
| Error Rate | `sum(rate(http_requests_total{status=~"5.*"}[1m])) / sum(rate(http_requests_total[1m]))` | error rate < 1% |
| Restarts | `sum(increase(kube_pod_container_status_restarts_total[1m]))` | restarts <= 1 |

**Configuration:**

- interval: 30s (ตรวจทุก 30 วินาที)
- count: 3 (ตรวจ 3 รอบ)
- failureLimit: 1 (fail ได้สูงสุด 1 รอบ)

---

## Rollout Actions

Pipeline ควบคุม Rollout ผ่าน ArgoCD CLI:

| Action | คำสั่ง | ใช้เมื่อ |
| --- | --- | --- |
| **Resume** | `argocd app actions run {app} resume --kind Rollout` | DAST ผ่าน -> เดินหน้า canary |
| **Abort** | `argocd app actions run {app} abort --kind Rollout` | DAST ไม่ผ่าน -> revert กลับ stable |
| **Retry** | `argocd app actions run {app} retry --kind Rollout` | ลองใหม่หลัง abort |

---

## Health Check (argocdHealthCheck.groovy)

หลัง deploy เสร็จ pipeline จะตรวจสอบ health:

| ประเภท App | คำสั่ง | Timeout | เหตุผล |
| --- | --- | --- | --- |
| API/Web app | `argocd app wait {app} --health` | 300s | รอ pods healthy |
| CronJob | `argocd app wait {app} --sync` | 120s | CronJob ไม่มี health state ตรวจแค่ sync |

ถ้า health check fail -> pipeline fail พร้อมแจ้ง Discord

---

## Configuration ใน Jenkinsfile

### Frontend (test-jenkins-app)

```groovy
ci: [
    autoSync: 'No',           // manual approve ผ่าน Discord
    deployStrategy: 'canary',  // ใช้ Argo Rollouts
    analysis: 'No',            // ไม่ใช้ AnalysisRun อัตโนมัติ
],

rollout: [
    enabled: true,
    autoPromotionEnabled: false,
    steps: [
        [setWeight: 25],
        [pause: [duration: '15s']],
        [analysis: "${appName}-analysis-prometheus"],
        [setWeight: 50],
        [analysis: "${appName}-analysis-prometheus"],
        [pause: [:]],          // manual pause
        [setWeight: 100]
    ],
    smoke: [
        paths: ['GET|/'],
        timeout: "10",
        count: 3,
    ]
]
```

### Backend (test-be-api)

```groovy
ci: [
    autoSync: 'No',
    deployStrategy: 'canary',
    analysis: 'No',
],

rollout: [
    enabled: true,
    autoPromotionEnabled: false,
    steps: [
        [setWeight: 25],
        [pause: [duration: '15s']],
        [analysis: "${appName}-analysis-prometheus"],
        [setWeight: 50],
        [analysis: "${appName}-analysis-newman"],
        [pause: [:]],
        [setWeight: 100]
    ],
    smoke: [
        testFilePath: 'tests/smoke-test.json',
        paths: ['GET|/health'],
        timeout: "10",
        count: 3,
    ]
]
```

---

## Manifest Repository Structure

ArgoCD อ่าน manifests จาก branch `{projectName}-manifest` ใน GitLab:

```
manifest branch/
├── base/
│   ├── app/
│   │   ├── kustomization.yaml
│   │   ├── kustomize-config.yaml
│   │   ├── rollout.yaml              # Argo Rollout CRD
│   │   ├── service-stable.yaml       # stable service
│   │   ├── service-canary.yaml       # canary service
│   │   ├── analysis-template-newman.yaml
│   │   ├── analysis-template-prometheus.yaml
│   │   └── rbac-analysis-job.yaml
│   └── cronjob/
│       ├── kustomization.yaml
│       └── cronjob-{name}.yaml
└── overlays/
    └── prd/
        ├── app/
        │   ├── kustomization.yaml    # kustomize edit set image
        │   └── patch-overrides.yaml  # env, probes, resources, canary steps
        └── cronjob/
            ├── kustomization.yaml
            └── patch-overrides.yaml
```

ArgoCD Application ชี้ไปที่ `overlays/{env}/app/` หรือ `overlays/{env}/cronjob/` แล้ว Kustomize จะ build manifest สุดท้ายจาก base + overlay patches
