# Jenkins Shared Library - Full Documentation

เอกสารนี้อธิบายการทำงานทั้งหมดของ Jenkins Shared Library ตั้งแต่ภาพรวมระบบ, สถาปัตยกรรม, ไฟล์แต่ละไฟล์, ไปจนถึงรายละเอียดเชิงลึกทุก Component

---

## สารบัญ

1. [ภาพรวมระบบ (System Overview)](#1-ภาพรวมระบบ-system-overview)
2. [โครงสร้างไฟล์ (Project Structure)](#2-โครงสร้างไฟล์-project-structure)
3. [Infrastructure - Dockerfile & Ansible](#3-infrastructure---dockerfile--ansible)
4. [Pipeline หลัก - generalPipeline.groovy](#4-pipeline-หลัก---generalpipelinegroovy)
5. [Pipeline Modes (โหมดการทำงาน)](#5-pipeline-modes-โหมดการทำงาน)
6. [Core Logic Classes (src/)](#6-core-logic-classes-src)
7. [Pipeline Steps (vars/) - รายละเอียดทุกไฟล์](#7-pipeline-steps-vars---รายละเอียดทุกไฟล์)
8. [Kustomize & GitOps System](#8-kustomize--gitops-system)
9. [Notification System & Discord](#9-notification-system--discord)
10. [Deployment Strategies](#10-deployment-strategies)
11. [Flow Diagrams](#11-flow-diagrams)

---

## 1. ภาพรวมระบบ (System Overview)

ระบบนี้เป็น **Jenkins Shared Library** ที่รวมทุกอย่างสำหรับ CI/CD Pipeline แบบ DevSecOps เต็มรูปแบบ รองรับทั้ง **Frontend (Node.js/React/Next.js)**, **Backend (Go/Python)** และ **CronJob** โดยใช้แนวคิด GitOps ผ่าน **ArgoCD** และ **Kustomize**

### เทคโนโลยีที่ใช้

| หมวด | เครื่องมือ |
|------|-----------|
| CI Server | Jenkins (LTS, JDK 21) |
| Container | Docker, Docker Buildx (layer caching) |
| GitOps | ArgoCD, Kustomize |
| Security (SCA/Image) | Trivy (Image Scan) |
| Security (DAST) | OWASP ZAP |
| Versioning | Semantic Release (Conventional Commits) |
| SBOM | Trivy (SPDX + CycloneDX) |
| Notification | Discord (Webhook) |
| IaC / Provisioning | Ansible |
| Manifest Mgmt | Kustomize |
| Scaling | HPA, VPA, KEDA |
| Progressive Delivery | Argo Rollouts (Canary) |

---

## 2. โครงสร้างไฟล์ (Project Structure)

```
jenkins-shared-library/
├── vars/                          # Pipeline step definitions (Jenkins DSL)
│   ├── generalPipeline.groovy     #   Orchestrator หลัก - ร้อยเรียง Stage ทั้งหมด
│   ├── ciSetup.groovy             #   สร้าง release.config.js สำหรับ Semantic Release
│   ├── ciPostProcess.groovy       #   Post-build: archive reports, notify, docker cleanup
│   ├── versioning.groovy          #   Semantic Release + custom version management
│   ├── myDocker.groovy            #   Docker buildx build + push to GitLab Registry
│   ├── trivy.groovy               #   Trivy Image Scan (FINAL gate) + report generation
│   ├── dastScan.groovy            #   OWASP ZAP DAST scan for canary deployments
│   ├── kustomize.groovy           #   Kustomize orchestrator (base + overlay generation)
│   ├── updateManifests.groovy     #   Bridge: pipeline config -> kustomize params
│   ├── argocd.groovy              #   ArgoCD: register repo, create app, sync, rollout
│   ├── argocdHealthCheck.groovy   #   ArgoCD health check for deployed apps
│   ├── notifier.groovy            #   Discord notification (webhook + bot integration)
│   ├── sbom.groovy                #   SBOM generation (SPDX + CycloneDX via Trivy)
│   └── utils.groovy               #   Git checkout, Docker cleanup, workspace cleanup
│
├── src/org/devops/                # Core logic classes (OOP)
│   ├── ConfigParser.groovy        #   Parse config + resolve params + pipeline flags
│   ├── CredentialConstants.groovy #   Centralized credential ID constants
│   ├── UiHelper.groovy            #   UI description texts + gate messages
│   └── kustomize/
│       ├── BaseGenerator.groovy   #   Generate base/ Kubernetes manifests
│       ├── OverlayUpdater.groovy  #   Update overlays/<env>/ patches
│       ├── GitOps.groovy          #   Clone/checkout manifest repository
│       ├── RolloutHelper.groovy   #   Generate Argo Rollout canary steps/patches
│       ├── Utils.groovy           #   Template substitution + git commit/push
│       └── Validation.groovy      #   Config validation + defaults + scaling logic
│
├── resources/
│   ├── org/devops/kustomize/base/ #   Kustomize YAML templates (.tmpl)
│   │   ├── deployment.tmpl        #     Kubernetes Deployment template
│   │   ├── rollout.tmpl           #     Argo Rollout template
│   │   ├── service.tmpl           #     Service (standard)
│   │   ├── service-stable.tmpl    #     Service (canary stable)
│   │   ├── service-canary.tmpl    #     Service (canary)
│   │   ├── hpa.tmpl               #     HorizontalPodAutoscaler
│   │   ├── vpa.tmpl               #     VerticalPodAutoscaler
│   │   ├── scaledobject.tmpl      #     KEDA ScaledObject
│   │   ├── cronjob.tmpl           #     CronJob template
│   │   ├── pvc-logs.tmpl          #     PVC for log storage
│   │   ├── kustomization.tmpl     #     Base kustomization.yaml
│   │   ├── kustomize-config.tmpl  #     Kustomize config
│   │   ├── analysis-template-newman.tmpl     # Newman smoke test analysis
│   │   ├── analysis-template-prometheus.tmpl # Prometheus metrics analysis
│   │   └── rbac-analysis-job.tmpl            # RBAC for analysis jobs
│   ├── org/devops/kustomize/overlays/
│   │   └── kustomization.tmpl     #     Overlay kustomization template
│   └── scripts/
│       ├── generate_trivy_reports.py    #   Trivy PDF/DOCX/XLSX report generator
│       ├── generate_dast_reports.py     #   ZAP DAST PDF/DOCX/XLSX report generator
│       ├── report_utils.py              #   Shared report utilities (fonts, styles)
│       ├── check_tooling.sh             #   Verify required tools are installed
│       ├── install_node_deps.sh         #   Install Node.js dependencies
│       ├── install_python_deps.sh       #   Install Python dependencies
│       └── static-sec-checks.sh         #   Static security checks
│
├── ansible/                       # Jenkins Server Provisioning
│   ├── site.yaml                  #   Main playbook
│   ├── setup.yaml                 #   Setup playbook
│   ├── inventory.ini              #   Server inventory
│   ├── group_vars/
│   │   └── jenkins_servers.yaml   #   Server variables
│   └── roles/
│       ├── base/tasks/main.yaml   #   Base OS setup
│       ├── docker/tasks/main.yaml #   Docker installation
│       └── jenkins/
│           ├── tasks/main.yaml    #   Jenkins setup
│           └── templates/
│               ├── Dockerfile.j2           # Jenkins Dockerfile template
│               └── docker-compose.yaml.j2  # Docker compose template
│
├── jcasc/
│   └── jenkins.yaml.j2           # Jenkins Configuration as Code template
│
├── Dockerfile                     # Custom Jenkins image (all tools pre-installed)
└── .groovylintrc.json             # Groovy linter configuration
```

---

## 3. Infrastructure - Dockerfile & Ansible

### 3.1 Custom Jenkins Docker Image (`Dockerfile`)

Jenkins Image ถูก customize ให้มีเครื่องมือทั้งหมดที่ Pipeline ต้องการ ติดตั้งไว้ล่วงหน้า:

| เครื่องมือ | วัตถุประสงค์ |
|-----------|-------------|
| Docker CE CLI + Buildx | สร้าง Docker image พร้อม layer caching |
| Node.js 22 + Yarn | สำหรับ Frontend builds + Semantic Release |
| Python 3.12 (pyenv) | สำหรับ report generation + Python project builds |
| Kustomize | จัดการ Kubernetes manifests |
| ArgoCD CLI | สั่ง sync/rollout จาก pipeline |
| Trivy | Vulnerability scanning (Image Scan) |
| yq | YAML manipulation |
| Semantic Release | Automatic versioning |
| wkhtmltopdf | HTML to PDF conversion |
| reportlab, python-docx, openpyxl | PDF/DOCX/XLSX report generation |

**Shared Cache System**: Image สร้าง directory `/shared-cache/` สำหรับ npm, yarn, pip, go, trivy เพื่อลดเวลา build ซ้ำ

### 3.2 Ansible Provisioning (`ansible/`)

ใช้ Ansible สำหรับ provision Jenkins server บน remote host:

- **`roles/base`**: ติดตั้ง OS packages พื้นฐาน
- **`roles/docker`**: ติดตั้ง Docker Engine
- **`roles/jenkins`**: Build Jenkins Docker image จาก template + deploy ด้วย docker-compose

---

## 4. Pipeline หลัก - generalPipeline.groovy

ไฟล์นี้เป็น **Orchestrator** ที่ร้อยเรียง Stage ทั้งหมดของ Pipeline ทำงานตามลำดับดังนี้:

### Stage Flow

```
┌──────────────────────────────────────────────────────────────┐
│  1. Setup & Checkout                                         │
│     - cleanWs()                                              │
│     - ConfigParser.toSerializable(config)                    │
│     - ConfigParser.resolveRuntimeConfig(config, params)      │
│     - utils.gitCheckout() — clone source code                │
│     - ciSetup(config) — generate release.config.js           │
│     - ตรวจสอบว่า CI ไม่ได้แก้ tracked files                      │
├──────────────────────────────────────────────────────────────┤
│  2. Guard - Release-worthy Changes                           │
│     - ถ้าเป็น manual run → ข้าม guard                         │
│     - ตรวจสอบว่ามี commit ที่ควร release (feat/fix/perf/revert)│
│     - ถ้าไม่มี → NOT_BUILT + abort                           │
├──────────────────────────────────────────────────────────────┤
│  3. Create Release               [when: doBuild || doDeploy] │
│     - Semantic Release (auto version)                        │
│     - หรือ customVersion (manual tag)                        │
│     - return newVersion = "x.y.z"                            │
├──────────────────────────────────────────────────────────────┤
│  4. Setup Buildx                        [when: doBuild]      │
│     - สร้าง/ใช้ Docker Buildx builder "mybuilder"             │
├──────────────────────────────────────────────────────────────┤
│  5. Build Docker Image                  [when: doBuild]      │
│     - docker buildx build พร้อม cache-from/cache-to          │
│     - return dockerInfo = { imageTag, localImage,            │
│                              gitlabRegistryImage }           │
├──────────────────────────────────────────────────────────────┤
│  6. Resolve Image for Deploy   [when: !doBuild && doDeploy]  │
│     - ใช้ customVersion หรือ latest git tag                   │
│     - สร้าง dockerInfo โดยไม่ต้อง build                       │
├──────────────────────────────────────────────────────────────┤
│  7. Trivy Image Scan & SBOM             [when: doBuild]      │
│     - Trivy image scan (FINAL security report)               │
│     - SBOM generation (SPDX + CycloneDX)                    │
│     - ถ้า FAIL → prompt หรือ auto UNSTABLE                   │
├──────────────────────────────────────────────────────────────┤
│  8. Push Images                         [when: doBuild]      │
│     - docker tag + push to GitLab Registry                   │
├──────────────────────────────────────────────────────────────┤
│  9. Update Manifests       [when: doDeploy || doCronOnly]    │
│     - clone manifest repo                                    │
│     - generate base/ (Deployment/Rollout/Service/HPA/etc.)   │
│     - update overlays/<env>/ patches                         │
│     - git push to manifest branch                            │
├──────────────────────────────────────────────────────────────┤
│  10. Create ArgoCD App & Sync  [when: doDeploy || doCronOnly]│
│      - register repo + create/update ArgoCD app              │
│      - autoSync=Yes → auto sync                              │
│      - autoSync=No → Discord prompt → manual approve/skip    │
├──────────────────────────────────────────────────────────────┤
│  11. DAST Scan & Canary Promotion                            │
│      [when: dastScan=Yes && canary && doDeploy && !skipSync] │
│      - OWASP ZAP scan against canary service                 │
│      - PASS → resume rollout                                 │
│      - FAIL → abort rollout                                  │
├──────────────────────────────────────────────────────────────┤
│  12. Health Check & Notify                                   │
│      - argocd app wait --health (api apps)                   │
│      - argocd app wait --sync (cronjob apps)                 │
├──────────────────────────────────────────────────────────────┤
│  POST: always                                                │
│      - ciPostProcess:                                        │
│        • archiveArtifacts (reports, coverage, logs)           │
│        • Discord notification                                │
│        • Docker image cleanup                                │
│        • cleanWs()                                           │
└──────────────────────────────────────────────────────────────┘
```

### Jenkins Parameters

Pipeline มี parameters ให้ผู้ใช้เลือกตอน build:

| Parameter | ค่าที่เลือกได้ | อธิบาย |
|-----------|--------------|--------|
| `pipelineMode` | Default, full, build-only, build-deploy-all, ... (9 modes) | กำหนดว่า pipeline จะทำอะไรบ้าง |
| `customVersion` | string (e.g. 1.2.3) | กำหนด version เอง แทน auto-increment |
| `deployENV` | Default, poc, uat, prd | target environment |
| `forceRebuild` | Default, Yes, No | Yes = --no-cache (build จาก scratch) |
| `deployRegistry` | Default, gitlab, docker-registry | registry ที่จะ push image |
| `autoSync` | Default, Yes, No | Yes = ArgoCD auto sync |
| `deployStrategy` | Default, canary | deployment strategy |
| `kubeHealthCheck` | Default, Yes, No | ตรวจ pod health หลัง deploy |
| `analysis` | Default, Yes, No | Argo Rollouts analysis (canary) |
| `scalingStrategy` | Default, hpa, vpa, keda | auto-scaling strategy |
| `dastScan` | Default, Yes, No | OWASP ZAP DAST scan |
| `enableLogPvc` | Default, Yes, No | mount PVC สำหรับ app logs |

---

## 5. Pipeline Modes (โหมดการทำงาน)

`ConfigParser.resolveRuntimeConfig()` แปลง `pipelineMode` เป็น flags:

| Mode | Build | Deploy API | Deploy CronJob |
|------|-------|-----------|----------------|
| `Default` (→ build-deploy-all) | Y | Y | Y |
| `full` | Y | Y | Y |
| `build-only` | Y | - | - |
| `build-deploy-all` | Y | Y | Y |
| `build-deploy-app` | Y | Y | - |
| `build-deploy-cronjob` | Y | - | Y |
| `deploy-all` | - | Y | Y |
| `deploy-app` | - | Y | - |
| `deploy-cronjob` | - | - | Y |

Flags ที่ได้:
- **`doBuild`** — build Docker image
- **`doDeploy`** — deploy API/web app
- **`doCronOnly`** — deploy cronjob เท่านั้น (ไม่ deploy api)
- **`deployCronJobs`** — `"Yes"` ถ้า mode รวม cronjob

---

## 6. Core Logic Classes (src/)

### 6.1 ConfigParser (`src/org/devops/ConfigParser.groovy`)

**หน้าที่**: แปลง raw config จาก Jenkinsfile + Jenkins parameters ให้เป็น normalized config ที่ pipeline ใช้งานได้

**Methods สำคัญ**:
- **`toSerializable(obj)`** — แปลง Groovy objects ให้เป็น HashMap/ArrayList (Jenkins serialization)
- **`resolveRuntimeConfig(cfg, params)`** — merge config จาก file + params, normalize Yes/No, กำหนด pipeline flags
- **`rolloutSpecFromConfig(cfg)`** — สร้าง canary rollout spec จาก config

**Resolution Priority** (สำหรับแต่ละ setting):
1. Jenkins build parameter (ถ้าไม่ใช่ "Default")
2. `config.ci.<key>` (CI-specific config)
3. `config.<key>` (top-level config)
4. Default value

### 6.2 CredentialConstants (`src/org/devops/CredentialConstants.groovy`)

เก็บ Jenkins credential IDs เป็น constants:
- `GITLAB_PAT` = `'GITLAB_PAT_CREDENTIALS_TEST'`
- `GITLAB_DEPLOY` = `'GITLAB_CREDENTIALS_TEST'`
- `FRONTEND_ENV` = `'FRONTEND_ENV'`
- `ARGOCD` = `'ARGOCD_CREDENTIALS'`

### 6.3 UiHelper (`src/org/devops/UiHelper.groovy`)

เก็บ description texts สำหรับ Jenkins UI parameters + helper สำหรับสร้าง gate message prompt

### 6.4 Kustomize Classes (`src/org/devops/kustomize/`)

ดูรายละเอียดใน [Section 8 - Kustomize & GitOps System](#8-kustomize--gitops-system)

---

## 7. Pipeline Steps (vars/) - รายละเอียดทุกไฟล์

### 7.1 ciSetup.groovy

**หน้าที่**: สร้างไฟล์ `release.config.js` สำหรับ Semantic Release

**การทำงาน**:
1. ตรวจสอบภาษา (javascript/go/python) เพื่อกำหนด `gitAssets` (ไฟล์ที่ semantic-release จะ commit กลับ)
2. สร้าง `release.config.js` ที่มี:
   - Conventional Commits preset (feat=minor, fix=patch, breaking=major)
   - Changelog generation
   - Git commit with assets
3. ตรวจสอบว่าไม่ได้แก้ไข tracked files อื่นโดยไม่ตั้งใจ

### 7.2 versioning.groovy

**หน้าที่**: จัดการ version ผ่าน Semantic Release หรือ manual custom version

**`createRelease(config)`**:
1. ถ้ามี `customVersion`:
   - Validate semver format
   - ถ้า tag มีอยู่แล้ว → ใช้ tag เดิม
   - ถ้าเก่ากว่า latest tag → build image เท่านั้น ไม่สร้าง tag
   - ถ้าใหม่กว่า → prompt ยืนยัน → สร้าง git tag
2. ถ้าไม่มี `customVersion`:
   - รัน `npx semantic-release` → auto-calculate version จาก commits
   - Return new version (e.g. "1.2.3")

**`hasReleaseWorthyChanges()`**: ตรวจสอบว่ามี commit ที่ควร release หรือไม่ (feat/fix/perf/revert/BREAKING CHANGE)

### 7.3 myDocker.groovy

**หน้าที่**: Build และ Push Docker image

**`call(config)`**:
1. กำหนด image tags (local, gitlab registry)
2. Setup cache strategy:
   - `forceRebuild=Yes` → `--no-cache`
   - ปกติ → `--cache-from=type=registry,ref=<cache-image>`
3. ถ้ามี `envFileCredentialId` (frontend) → copy `.env.production` จาก Jenkins secret
4. Login to GitLab Registry
5. `docker buildx build` พร้อม:
   - `--load` (load ลง local daemon)
   - `--cache-from` + `--cache-to` (registry-based caching)
   - Build args: `NODE_OPTIONS`, `NEXT_MAX_WORKERS`
6. Return `dockerInfo` map

**`pushToGitlabRegistry(config)`**: Tag + push image ไป GitLab Registry

**`cleanupBaseImage(args)`**: ลบ local Docker images

### 7.4 trivy.groovy

**หน้าที่**: Trivy Image Scan (FINAL security gate)

**Gate Policy** (configurable):
- `critical_threshold`: default 0 (FAIL ถ้ามี CRITICAL > threshold)
- `high_threshold`: default 5 (UNSTABLE ถ้ามี HIGH > threshold)

**Flow**:
1. Scan Docker image → JSON + Table report (`reports/trivy/image/`)
2. Count vulnerabilities by severity (CRITICAL/HIGH/MEDIUM/LOW)
3. Generate PDF/DOCX/XLSX reports ด้วย `generate_trivy_reports.py`
4. Evaluate gate → PASS/FAIL
5. Archive artifacts

**เงื่อนไขรัน**: `doBuild` (รันหลัง build image สำเร็จ ไม่ว่าจะ deploy หรือไม่)

**เมื่อ FAIL**:
- ถ้า pipeline เป็น UNSTABLE อยู่แล้ว → skip prompt, คงสถานะ UNSTABLE
- ถ้าไม่ → prompt user "Proceed / Abort" → mark UNSTABLE

### 7.5 sbom.groovy

**หน้าที่**: สร้าง Software Bill of Materials

ใช้ Trivy generate:
- **SPDX** format → `reports/sbom.spdx.json`
- **CycloneDX** format → `reports/sbom.cyclonedx.json`

รันหลัง Trivy Image Scan ภายใน stage เดียวกัน (`Trivy Image Scan & SBOM`)

### 7.6 dastScan.groovy

**หน้าที่**: OWASP ZAP Dynamic Application Security Testing

**ใช้เมื่อ**: `dastScan=Yes` + `deployStrategy=canary` + deploy mode + ไม่ได้ skip sync

**Gate Policy**:
- HIGH > 0 → FAIL (abort rollout)
- MEDIUM > 0 → WARN (UNSTABLE)

**Flow**:
1. คำนวณ target URL:
   - ถ้ามี nodePort → `http://<argoIP>:<nodePort+100>` (canary port)
   - ถ้าไม่มี → `http://<service>-canary-<env>.<ns>:<port>`
2. รอให้ ArgoCD app healthy/suspended
3. รัน ZAP scan ใน Docker container:
   - Backend + API spec → `zap-api-scan.py` (OpenAPI spec)
   - อื่นๆ → `zap-baseline.py` (passive scan)
4. Extract reports จาก container → `reports/zap/`
5. Generate PDF/DOCX/XLSX reports ด้วย `generate_dast_reports.py`
6. Evaluate gate → PASS/WARN/FAIL

### 7.7 kustomize.groovy

**หน้าที่**: Orchestrate Kustomize manifest generation

**Flow**:
1. Validation → apply defaults
2. Clone manifest repo (GitOps)
3. Generate base manifests (BaseGenerator)
4. Update overlay patches (OverlayUpdater)
   - ถ้า deployApp + deployCron → update ทั้ง 2 overlays
   - ถ้า cronOnly → update เฉพาะ cronjob overlay

### 7.8 updateManifests.groovy

**หน้าที่**: Bridge ระหว่าง pipeline config → kustomize params

รวบรวมข้อมูลจาก:
- `config.deployment` (ports, probes, resources, namespace)
- `config.deployStrategy` (canary/default)
- `config.scalingStrategy` (hpa/vpa/keda)
- `config.cronjobs`
- `dockerInfo` (image tag, registry URL)

แล้วส่งให้ `kustomize.call(commonParams)`

### 7.9 argocd.groovy

**หน้าที่**: จัดการ ArgoCD apps

**`call(config)`**:
1. Login to ArgoCD
2. Register GitLab repo
3. สร้าง/update ArgoCD apps ตาม mode:
   - `doDeploy=true` → `<project>-app-<env>`
   - `deployCronJobs=Yes` → `<project>-cronjob-<env>`
4. Set sync policy (automated หรือ none)
5. Sync + wait:
   - Canary → wait sync only (จะ pause ที่ 0%)
   - ปกติ → wait health + sync

**`syncApp(config)`**: On-demand sync (ใช้ตอน manual approve)

**`actionRollout(config)`**: สั่ง resume/abort/retry rollout

**`getAppSyncStatus(config)`**: ดึงสถานะ sync ของ app

### 7.10 argocdHealthCheck.groovy

**หน้าที่**: ตรวจสอบ health หลัง deploy

- API apps → `argocd app wait --health --timeout 300`
- CronJob apps → `argocd app wait --sync --timeout 120` (ไม่ต้อง health check)

### 7.11 notifier.groovy

**หน้าที่**: แจ้งเตือนผ่าน Jenkins console + Discord

**`call(config)`**: Print สรุปผลลง Jenkins console log

**`discordNotifier(params)`**:
- สร้าง Discord embed message พร้อม:
  - Color code ตามสถานะ (green/red/yellow/gray)
  - Fields: Project, Service, Pipeline Mode, Environment, Version, Commit, Duration
  - Error logs (ถ้า FAIL)
  - Links: Jenkins + ArgoCD
- **Suppress logic**: ไม่ส่ง Discord ถ้า:
  - NOT_BUILT
  - SUCCESS + no deploy (build only)
  - SUCCESS + minor commit + ไม่ใช่ manual run

**`sendSyncPromptToDiscord(params)`**: ส่ง HTTP request ไปที่ Discord Bot เพื่อสร้าง prompt message

**`sendSyncResultToDiscord(params)`**: ส่งผลลัพธ์ของ sync action (proceed/skip/timeout)

### 7.12 ciPostProcess.groovy

**หน้าที่**: Post-build cleanup + reporting

**Flow**:
1. Archive artifacts (reports, coverage, logs) — ถ้าไม่ใช่ ABORTED/NOT_BUILT
2. Send notification (Jenkins console + Discord)
3. Docker image cleanup
4. `cleanWs()` — clean workspace

**หมายเหตุ**: ไม่มี JUnit publish, DefectDojo upload, หรือ scan zip แนบ Discord อีกต่อไป

### 7.13 utils.groovy

**หน้าที่**: Utility functions

- **`gitCheckout(config)`**: Clone git repo (shallow/full, with credentials)
- **`cleanupBaseImage(args)`**: ลบ Docker images + prune
- **`cleanupWorkspace(config)`**: Selective workspace cleanup (เก็บ cache)
- **`checkTooling(config)`**: ตรวจสอบว่า tools ที่ต้องการมีครบ

---

## 8. Kustomize & GitOps System

### 8.1 ภาพรวม

ระบบ GitOps ทำงานโดย:
1. Pipeline clone **manifest repository** (แยกจาก source code repo)
2. สร้าง/update Kubernetes manifests ใน `base/` และ `overlays/<env>/`
3. Git push กลับไปที่ manifest repo
4. ArgoCD detect การเปลี่ยนแปลง → sync ลง cluster

### 8.2 Manifest Repository Structure

```
manifest-repo/
├── base/
│   ├── app/                      # Workload manifests
│   │   ├── kustomization.yaml
│   │   ├── kustomize-config.yaml
│   │   ├── deployment.yaml       # (standard mode)
│   │   ├── rollout.yaml          # (canary mode)
│   │   ├── service.yaml          # (standard mode)
│   │   ├── service-stable.yaml   # (canary mode)
│   │   ├── service-canary.yaml   # (canary mode)
│   │   ├── hpa.yaml              # (ถ้า scalingStrategy=hpa)
│   │   ├── vpa.yaml              # (ถ้า scalingStrategy=vpa)
│   │   ├── scaledobject.yaml     # (ถ้า scalingStrategy=keda)
│   │   ├── pvc-logs.yaml         # (ถ้า enableLogPvc=Yes)
│   │   ├── analysis-template-newman.yaml
│   │   ├── analysis-template-prometheus.yaml
│   │   └── rbac-analysis-job.yaml
│   └── cronjob/                  # CronJob manifests
│       ├── kustomization.yaml
│       └── cronjob-<name>.yaml
└── overlays/
    ├── poc/
    │   ├── app/
    │   │   ├── kustomization.yaml
    │   │   └── patch-overrides.yaml
    │   └── cronjob/
    │       ├── kustomization.yaml
    │       └── patch-overrides.yaml
    ├── uat/
    │   ├── app/
    │   │   └── ...
    │   └── cronjob/
    │       └── ...
    └── prd/
        ├── app/
        │   └── ...
        └── cronjob/
            └── ...
```

### 8.3 GitOps.groovy

Clone manifest repo → checkout branch (or create orphan branch):
- Branch name: `<projectName>-manifest`
- สร้าง `base/` + `overlays/` skeleton ถ้ายังไม่มี

### 8.4 BaseGenerator.groovy

**สร้าง base/ manifests จาก templates**:

1. ลบไฟล์เก่าใน `base/app/` และ `base/cronjob/`
2. สร้าง `base/app/` ตามสถานะ:
   - **Standard**: `deployment.yaml` + `service.yaml`
   - **Canary/Rollout**: `rollout.yaml` + `service-stable.yaml` + `service-canary.yaml` + analysis templates
3. เพิ่ม optional resources: HPA, VPA, KEDA ScaledObject, PVC
4. สร้าง `base/cronjob/` (ถ้ามี cronjobs ใน config)
5. สร้าง overlay skeletons สำหรับทุก env (test/dev/poc/uat/prd)
6. Git commit + push

### 8.5 OverlayUpdater.groovy

**อัปเดต overlay patch สำหรับ specific environment**:

1. Cleanup patches เก่า
2. สร้าง `patch-overrides.yaml` ที่มี:
   - **Container spec**: image, ports, env, volumeMounts, resources, probes
   - **Replicas**: ตาม config (ถ้าไม่ใช่ HPA/KEDA)
   - **Service patch**: ports + nodePort
   - **Canary patch**: rollout steps + service refs (ถ้า canary)
   - **CronJob patch**: image + env + resources สำหรับแต่ละ cronjob
3. `kustomize edit`:
   - `set image <project>=<new-image>`
   - `set namesuffix -- -<env>`
   - `set namespace <ns>`
   - `add patch patch-overrides.yaml`
4. สร้าง smoke-test ConfigMap (ถ้ามี analysis)
5. Git commit + push
6. `kustomize build .` → sanity render

### 8.6 RolloutHelper.groovy

สร้าง Argo Rollout canary configuration:

- **`stepsBlock(config)`**: Generate YAML steps สำหรับ base rollout (setWeight, pause, analysis)
- **`canaryPatch(config)`**: Generate overlay patch สำหรับ canary strategy
- **`kedaTriggersBlock(config)`**: Generate KEDA triggers YAML

### 8.7 Validation.groovy

Validate + apply defaults:
- Required fields: manifestRepoUrl, deployEnv, projectName, imageToSet, etc.
- Scaling strategy resolution: PRD default=hpa, อื่นๆ default=vpa
- KEDA trigger normalization (migration สำหรับ KEDA 2.18+)
- Smoke test defaults

### 8.8 Templates (resources/org/devops/kustomize/base/)

ไฟล์ `.tmpl` ใช้ `{{VARIABLE}}` syntax → แทนที่ด้วย `Utils.tmpl()`:

| Template | สร้าง |
|----------|------|
| `deployment.tmpl` | Kubernetes Deployment |
| `rollout.tmpl` | Argo Rollout (canary) |
| `service.tmpl` | ClusterIP/NodePort Service |
| `service-stable.tmpl` | Stable Service (canary) |
| `service-canary.tmpl` | Canary Service |
| `hpa.tmpl` | HorizontalPodAutoscaler |
| `vpa.tmpl` | VerticalPodAutoscaler |
| `scaledobject.tmpl` | KEDA ScaledObject |
| `cronjob.tmpl` | Kubernetes CronJob |
| `pvc-logs.tmpl` | PersistentVolumeClaim |

---

## 9. Notification System & Discord

### 9.1 Discord Notifications

**เมื่อไหร่จะส่ง?**

| สถานะ | ส่ง Discord? | เงื่อนไข |
|-------|-------------|---------|
| SUCCESS + deploy | Y | เฉพาะ manual run หรือ important commit (feat/fix/perf/release) |
| SUCCESS + no deploy | N | ไม่ส่ง (build only) |
| NOT_BUILT | N | ไม่ส่ง |
| FAILURE | Y | เสมอ |
| UNSTABLE | Y | เสมอ |
| ABORTED | Y | เสมอ |

**Embed Structure**:
- Title: status icon + mode label
- Description: สรุปสิ่งที่เกิดขึ้น
- Fields: Project, Service, Mode, Environment, Version, Commit, Duration, Message, Links
- Color: Green (success), Red (failure), Yellow (unstable/warning), Gray (aborted/skipped)

### 9.2 Manual Sync via Discord Bot (External)

Pipeline รองรับ Manual Sync flow เมื่อ `autoSync=No` โดยส่ง HTTP request ไปยัง **Discord Bot ภายนอก** (ไม่ได้อยู่ใน repo นี้):

1. `notifier.sendSyncPromptToDiscord()` — ส่ง POST request ไป Discord Bot เพื่อสร้าง prompt message พร้อมปุ่ม Proceed/Skip
2. Pipeline รอ Jenkins input (timeout 30 นาที)
3. `notifier.sendSyncResultToDiscord()` — ส่งผลลัพธ์กลับไป Discord (proceed/skip/timeout)

> **หมายเหตุ**: Discord Bot code (bot.py, Dockerfile) ถูก deploy แยกต่างหาก ไม่ได้อยู่ใน repository นี้

---

## 10. Deployment Strategies

### 10.1 Standard (Rolling Update)

- ใช้ Kubernetes `Deployment`
- ArgoCD sync → wait health
- ไม่มี DAST scan

### 10.2 Canary (Argo Rollouts)

```
┌─────────────┐
│  Deploy v2   │
│  (Canary)    │
└──────┬──────┘
       │
  setWeight: 0% ← Pause
       │
┌──────┴──────┐
│  DAST Scan  │
│  (ZAP)      │
└──────┬──────┘
       │
   PASS?──No──→ Abort Rollout
       │
      Yes
       │
  Resume Rollout
       │
  setWeight: 20%
       │
  Pause (manual/timed)
       │
  setWeight: 50%
       │
  Pause (10m)
       │
  setWeight: 100%
       │
┌──────┴──────┐
│  Full Deploy│
└─────────────┘
```

**Features**:
- Canary Service (แยก traffic)
- NodePort offset (+100 สำหรับ canary)
- Analysis templates (Newman smoke test, Prometheus metrics)
- Auto/manual promotion steps

### 10.3 Auto-Scaling

| Strategy | Resource | ใช้เมื่อ |
|----------|----------|---------|
| **HPA** | HorizontalPodAutoscaler | Production (default) |
| **VPA** | VerticalPodAutoscaler | Non-production (default) |
| **KEDA** | ScaledObject | Event-driven scaling |

เมื่อใช้ HPA/KEDA → ลบ static replicas จาก overlay (ให้ autoscaler จัดการ)

### 10.5 Security Gates ใน Pipeline

| Scanner | เงื่อนไขรัน | Gate Criteria | Action on Fail |
|---------|------------|--------------|----------------|
| **Trivy Image** | `doBuild` | CRITICAL <= 0, HIGH <= 5 | Prompt user (FINAL gate) |
| **DAST (ZAP)** | `dastScan=Yes && canary && doDeploy` | HIGH = 0, MEDIUM = 0 | HIGH → abort rollout, MEDIUM → UNSTABLE |

---

## 11. Flow Diagrams

### 11.1 Complete Pipeline Flow

```
Developer commits code
        │
        ▼
┌─────────────────────────┐
│  GitLab Webhook         │
│  triggers Jenkins       │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  generalPipeline()      │
│  ├─ Parse config        │
│  ├─ Resolve params      │
│  └─ Set pipeline flags  │
└───────────┬─────────────┘
            │
     ┌──────┴──────┐
     │  doBuild?   │
     └──────┬──────┘
         Yes│         No
            ▼          │
   ┌────────────────┐  │
   │ Semantic Release│  │
   │ Docker build   │  │
   │ Trivy image    │  │
   │ SBOM           │  │
   │ Push registry  │  │
   └───────┬────────┘  │
           │           │
     ┌─────┴──────┐    │
     │  doDeploy? │◄───┘
     └─────┬──────┘
        Yes│         No
           ▼          │
   ┌────────────────┐ │
   │ Update manifest│ │
   │ ArgoCD sync    │ │
   │ (DAST if canary)│ │
   │ Health check   │ │
   └───────┬────────┘ │
           │          │
           ▼          ▼
   ┌────────────────────┐
   │  Post-Process      │
   │  ├─ Archive reports│
   │  ├─ Discord notify │
   │  └─ Cleanup        │
   └────────────────────┘
```

### 11.2 GitOps Manifest Update Flow

```
Pipeline                    Manifest Repo              ArgoCD
   │                            │                        │
   ├── clone manifest repo ────►│                        │
   │                            │                        │
   ├── generate base/ ────────►│                        │
   │   (deployment/rollout,     │                        │
   │    service, hpa, cronjob)  │                        │
   │                            │                        │
   ├── update overlay/ ───────►│                        │
   │   (patch-overrides.yaml,   │                        │
   │    kustomize edit)         │                        │
   │                            │                        │
   ├── git push ──────────────►│                        │
   │                            │                        │
   ├── argocd app create ──────┼───────────────────────►│
   │                            │                        │
   ├── argocd app sync ────────┼───────────────────────►│
   │                            │          ┌─────────────┤
   │                            │          │ detect diff  │
   │                            │          │ apply to K8s │
   │                            │          └─────────────┤
   │                            │                        │
   ├── argocd app wait ────────┼───────────────────────►│
   │   (health/sync)           │                        │
   │                            │                        │
```

