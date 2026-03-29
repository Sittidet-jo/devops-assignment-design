# เอกสาร CI/CD Pipeline

## ภาพรวม

CI/CD pipeline สร้างด้วย **Jenkins** โดยใช้รูปแบบ **Groovy Shared Library** มี entry point เดียว (`generalPipeline`) ที่รองรับทั้ง frontend และ backend ผ่านการกำหนดค่าใน Jenkinsfile

---

## ผังการทำงานของ Pipeline

```
 นักพัฒนา         GitLab              Jenkins                ArgoCD                 Cluster
    │                  │                   │                     │                    │
    │  git push        │                   │                     │                    │
    │ ────────────────►│   webhook         │                     │                    │
    │                  │ ─────────────────►│                     │                    │
    │                  │                   │                     │                    │
    │                  │    1. Checkout     │                     │                    │
    │                  │◄──────────────────│                     │                    │
    │                  │                   │                     │                    │
    │                  │                   │ 2. Guard            │                    │
    │                  │                   │ (ข้ามถ้าไม่มี        │                    │
    │                  │                   │  การเปลี่ยนแปลง)     │                    │
    │                  │                   │                     │                    │
    │                  │                   │ 3. สร้าง Release     │                    │
    │                  │                   │ (semantic version)  │                    │
    │                  │                   │                     │                    │
    │                  │                   │ 4. Build Image      │                    │
    │                  │                   │ (docker buildx)     │                    │
    │                  │                   │                     │                    │
    │                  │                   │ 5. Security Scan    │                    │
    │                  │                   │ (Trivy + SBOM)      │                    │
    │                  │                   │                     │                    │
    │                  │    6. Push Image   │                     │                    │
    │                  │◄──────────────────│                     │                    │
    │                  │ (GitLab Registry)  │                     │                    │
    │                  │                   │                     │                    │
    │                  │                   │ 7. อัปเดต Manifests  │                    │
    │                  │◄──────────────────│ (Kustomize)         │                    │
    │                  │ (manifest branch)  │                     │                    │
    │                  │                   │                     │                    │
    │                  │                   │ 8. Sync             │                    │
    │                  │                   │ ───────────────────►│                    │
    │                  │                   │                     │  9. Deploy         │
    │                  │                   │                     │ ──────────────────►│
    │                  │                   │                     │  (Canary Rollout)  │
    │                  │                   │                     │                    │
    │                  │                   │ 10. Health Check    │                    │
    │                  │                   │ ◄───────────────────────────────────────│
    │                  │                   │                     │                    │
    │  แจ้งเตือน Discord │                   │                     │                    │
    │◄─────────────────────────────────────│                     │                    │
```

---

## รายละเอียดแต่ละ Stage

### Stage 1: Setup & Checkout
- Clone source code จาก GitLab
- แปลงค่า config จาก Jenkinsfile (`cfg` map)
- รัน `ciSetup` (ติดตั้ง dependencies, lint, test ฯลฯ)
- ตรวจสอบว่าไม่มีไฟล์ tracked ถูกแก้ไขโดย CI

### Stage 2: Guard - ตรวจสอบการเปลี่ยนแปลง
- ข้ามการ build ถ้าไม่มีการเปลี่ยนแปลงที่สำคัญ
- ใช้เฉพาะเมื่อ trigger จาก webhook เท่านั้น ไม่ใช้กับการรันแบบ manual

### Stage 3: สร้าง Release
- เพิ่ม version อัตโนมัติด้วย **Semantic Release** (ตาม conventional commits)
- สร้าง git tag ใน GitLab
- สามารถกำหนด version เองผ่าน parameter `customVersion`

### Stage 4: Build Docker Image
- Build ด้วย **Docker Buildx** (รองรับ multi-platform)
- Frontend: inject ไฟล์ `.env` จาก Jenkins credentials
- รองรับ `forceRebuild` เพื่อข้าม cache

### Stage 5: Security Scan (Trivy + SBOM)
- **Trivy**: สแกน Docker image เพื่อหาช่องโหว่ (CVE)
- **SBOM**: สร้าง Software Bill of Materials (SPDX + CycloneDX)
- ถ้าพบช่องโหว่ร้ายแรง: ถามผู้ใช้ว่าจะดำเนินการต่อหรือยกเลิก
- Pipeline จะถูกทำเครื่องหมาย UNSTABLE ถ้าถูก bypass

### Stage 6: Push Image
- Push ไปยัง **GitLab Container Registry**
- Tag ด้วย version number และ environment (เช่น `dev`, `prd`)

### Stage 7: อัปเดต Manifests
- สร้าง Kubernetes manifests ด้วย **Kustomize** templates
- Resources ที่สร้าง: Deployment/Rollout, Service, ScaledObject/HPA/VPA, AnalysisTemplate, CronJob
- Push manifests ไปยัง branch `{project}-manifest` ใน GitLab

### Stage 8: ArgoCD Sync
- สร้าง/อัปเดต ArgoCD Application
- **autoSync=Yes**: sync และ deploy อัตโนมัติ
- **autoSync=No**: ส่งแจ้งเตือน Discord และรอ manual approve (timeout 30 นาที)

### Stage 9: DAST Scan (ตัวเลือก)
- รัน **OWASP ZAP** scan กับ canary deployment
- ถ้า DAST ล้มเหลว: ยกเลิก rollout
- ถ้า DAST ผ่าน: promote canary (resume rollout)

### Stage 10: Health Check & แจ้งเตือน
- ตรวจสอบว่า pods ทำงานปกติใน cluster
- ส่งสถานะสุดท้ายแจ้งเตือนไปยัง Discord

---

## กลยุทธ์ Canary Deployment (Argo Rollouts)

```
  น้ำหนัก Traffic    การดำเนินการ
  ─────────────────────────────────────────────────
       25%          Deploy canary pods
                    หยุดรอ 15 วินาที
                    รัน AnalysisRun (Newman API test / Prometheus metrics)
  ─────────────────────────────────────────────────
       50%          รัน AnalysisRun
                    หยุดรอ (รอ manual approve หรือ auto)
  ─────────────────────────────────────────────────
      100%          Rollout เต็ม - แทนที่ stable pods
```

### Analysis Templates
- **Newman**: รัน API smoke tests กับ canary endpoint

ถ้า analysis ตัวใดล้มเหลว rollout จะถูกยกเลิกอัตโนมัติ และ traffic จะกลับไปยัง stable version

---

## โหมดการทำงานของ Pipeline

Pipeline รองรับหลายโหมดผ่าน parameter `pipelineMode`:

| โหมด                    | Build | Deploy App | Deploy CronJob |
|-------------------------|-------|------------|----------------|
| `full`                  | Yes   | Yes        | Yes            |
| `build-deploy-all`     | Yes   | Yes        | Yes            |
| `build-deploy-app`     | Yes   | Yes        | No             |
| `build-deploy-cronjob` | Yes   | No         | Yes            |
| `build-only`           | Yes   | No         | No             |
| `deploy-all`           | No    | Yes        | Yes            |
| `deploy-app`           | No    | Yes        | No             |
| `deploy-cronjob`       | No    | No         | Yes            |

---

## การตั้งค่า Jenkinsfile

แต่ละโปรเจคกำหนด pipeline ผ่าน `cfg` map ใน Jenkinsfile:

```groovy
def cfg = [
  projectName : 'my-app',
  language    : 'python',        // python, javascript
  projectType : 'backend',       // backend, frontend
  imageEnv    : 'prd',           // dev, uat, prd

  ci: [
    forceRebuild    : 'No',
    deployRegistry  : 'gitlab',
    deployStrategy  : 'canary',  // canary
    autoSync        : 'Yes',     // Yes = deploy อัตโนมัติ, No = รอ manual approve
    kubeHealthCheck : 'Yes',
    analysis        : 'Yes',
    autoScaling     : 'keda',    // keda, hpa, vpa
    dastScan        : 'No',
  ],

  gitlab: [ ... ],               // ตั้งค่า GitLab repository
  deployment: [ ... ],           // ตั้งค่า K8s deployment (ports, resources, probes)
  keda: [ ... ],                 // ตั้งค่า KEDA auto-scaling
  rollout: [ ... ],              // ขั้นตอน canary rollout และ analysis
  build: [ ... ],                // ตั้งค่า Docker build
  credentials: [ ... ],          // Jenkins credential IDs
]

generalPipeline(cfg)
```

---

## โครงสร้าง Jenkins Shared Library

```
jenkins-shared-library/
  vars/
    generalPipeline.groovy    # จุดเริ่มต้นหลักของ pipeline
    myDocker.groovy           # Build และ push Docker image
    versioning.groovy         # Semantic release และจัดการ version
    argocd.groovy             # สร้าง/sync/จัดการ ArgoCD app
    argocdHealthCheck.groovy  # ตรวจสอบสุขภาพหลัง deploy
    updateManifests.groovy    # สร้าง Kustomize manifests และ push
    kustomize.groovy          # Render Kustomize templates
    trivy.groovy              # สแกน image ด้วย Trivy
    sbom.groovy               # สร้าง SBOM (SPDX + CycloneDX)
    dastScan.groovy           # สแกน DAST ด้วย OWASP ZAP
    ciSetup.groovy            # ตั้งค่า CI environment (deps, lint, test)
    ciPostProcess.groovy      # ทำความสะอาดหลัง build และแจ้งเตือน
    notifier.groovy           # ส่งแจ้งเตือน Discord
    utils.groovy              # Git checkout และ utility functions
  src/org/devops/
    ConfigParser.groovy       # แปลงและ resolve runtime config
    CredentialConstants.groovy # ค่า credential IDs เริ่มต้น
    UiHelper.groovy           # คำอธิบาย parameter ใน Jenkins UI
    kustomize/
      BaseGenerator.groovy    # สร้าง base Kustomize manifests
      OverlayUpdater.groovy   # สร้าง overlay (เฉพาะ environment)
      RolloutHelper.groovy    # สร้าง manifest Argo Rollout
      GitOps.groovy           # Git operations สำหรับ manifest repo
      Utils.groovy            # Kustomize utility functions
      Validation.groovy       # ตรวจสอบความถูกต้องของ config
  resources/
    org/devops/kustomize/     # Kustomize YAML templates (.tmpl)
    scripts/                  # Shell/Python helper scripts
```
