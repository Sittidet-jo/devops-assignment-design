# Presentation Guide - DevOps Assignment

## แนะนำตัว

สวัสดีครับ ผม**เซนต์ (สิทธิเดช)** สมัครตำแหน่ง **Site Reliability Engineer** ที่ SCG Digital Office

---

## สรุปภาพรวม (1 นาที)

> "ผมสร้าง Production-grade CI/CD Platform ที่ deploy 2 microservices (React frontend + FastAPI backend) บน K3s cluster (AWS) โดยใช้แนวคิด GitOps ผ่าน ArgoCD + Jenkins Shared Library ที่เป็น reusable pipeline ใช้ได้กับทุกโปรเจค"

---

## จุดเด่นของงาน

### 1. Jenkins Shared Library - Reusable Pipeline

**ปัญหาที่แก้:** ทุกโปรเจคต้องเขียน pipeline ซ้ำ

**สิ่งที่ทำ:**
- สร้าง Groovy Shared Library เป็น entry point เดียว (`generalPipeline`)
- ทุกโปรเจคแค่กำหนด config ใน Jenkinsfile 1 ไฟล์
- รองรับทั้ง Frontend (React/Node.js), Backend (Python/Go), CronJob
- รองรับ 8 pipeline modes (build-only, deploy-only, full, etc.)

**Demo:** เปิด Jenkinsfile ของ frontend vs backend -> config ต่างกัน แต่ใช้ pipeline เดียวกัน

---

### 2. GitOps with ArgoCD + Kustomize

**ปัญหาที่แก้:** deploy ด้วยมือ ไม่มี audit trail, drift detection

**สิ่งที่ทำ:**
- Pipeline generate Kubernetes manifests ด้วย Kustomize (base + overlay per environment)
- Push manifests ไป manifest branch -> ArgoCD detect และ sync อัตโนมัติ
- แยก base templates (immutable) กับ overlay patches (environment-specific)
- ArgoCD auto-prune + self-heal (cluster state = Git state เสมอ)

**Demo:** เปิด ArgoCD UI -> แสดง app sync status -> แสดง manifest branch structure

---

### 3. Canary Deployment (Argo Rollouts)

**ปัญหาที่แก้:** deploy version ใหม่แล้วพัง กระทบ user ทั้งหมด

**สิ่งที่ทำ:**
- ใช้ Argo Rollouts แทน Deployment -> canary strategy
- ค่อยๆ เพิ่ม traffic: 25% -> 50% -> 100%
- แต่ละ step มี automated analysis:
  - **Prometheus Analysis** - ตรวจ error rate < 1%, restarts <= 1
  - **Newman Smoke Test** - รัน Postman collection กับ canary endpoint
- ถ้า analysis fail -> **auto abort** กลับ stable version ทันที
- รองรับ manual pause สำหรับ human approval

**Demo:** เปิด ArgoCD -> แสดง Rollout steps -> แสดง AnalysisRun results

---

### 4. DevSecOps - Security ทุกขั้นตอน

**ปัญหาที่แก้:** ไม่มี security scan ใน pipeline, ช่องโหว่หลุดขึ้น production

**สิ่งที่ทำ:**

| Stage | Tool | Gate |
| --- | --- | --- |
| Image Scan | Trivy | CRITICAL = 0 -> FAIL, HIGH <= 5 -> UNSTABLE |
| SBOM | Trivy (SPDX + CycloneDX) | สร้าง Software Bill of Materials |
| DAST | OWASP ZAP | HIGH = 0 -> abort canary rollout |

- สร้าง reports อัตโนมัติ (PDF/DOCX/XLSX)
- DAST scan ยิงตรงไปที่ canary service ก่อน promote
- ถ้า DAST fail -> rollout ถูก abort อัตโนมัติ

**Demo:** เปิด Jenkins build -> แสดง Trivy report -> แสดง SBOM artifacts

---

### 5. Infrastructure as Code (Terraform)

**สิ่งที่ทำ:**
- Terraform provision AWS EC2 (1 K3s Server + 2 Agents)
- Security Group: ports 22, 80, 443, 6443
- Variables สำหรับ instance type, agent count, region
- Ansible provision Jenkins server (Docker container พร้อมเครื่องมือทั้งหมด)

**Demo:** เปิด `main.tf` -> แสดง resource structure

---

### 6. Auto Scaling (KEDA / HPA / VPA)

**สิ่งที่ทำ:**
- Frontend: **KEDA** (CPU > 75%, Memory > 75%) scale 2-6 replicas
- Backend: **VPA** (auto-adjust resource requests/limits)
- Production default: HPA, Non-production default: VPA
- Pipeline generate ScaledObject/HPA/VPA manifests อัตโนมัติตาม config

---

### 7. Monitoring & Alerting

**สิ่งที่ทำ:**
- **Prometheus Agent** (in-cluster) -> remote_write -> Prometheus Server (external)
- **Grafana** dashboards: K8s overview, CPU/Memory/Disk, Nginx logs
- **Loki** + Grafana Alloy สำหรับ centralized logging
- **5 Alert Rules** -> Discord:
  - Container Down, Pod Not Ready, CrashLoopBackOff
  - Replica Mismatch, Node Not Ready

**Demo:** เปิด Grafana -> แสดง K8s dashboard -> แสดง alert rules

---

### 8. Manual Approval via Discord Bot

**สิ่งที่ทำ:**
- เมื่อ `autoSync=No` -> pipeline ส่ง Discord message พร้อมปุ่ม Proceed/Skip
- User กดปุ่มใน Discord -> pipeline sync หรือ skip
- Timeout 30 นาที -> auto skip
- Role-based access (กำหนดว่าใครกดได้)

---

## สิ่งที่แตกต่างจาก DevOps ทั่วไป

| ทั่วไป | งานนี้ |
| --- | --- |
| Pipeline เขียนซ้ำทุกโปรเจค | Shared Library ใช้ได้ทุกโปรเจค |
| Deploy แบบ rolling update | Canary + automated analysis |
| Security scan แยกต่างหาก | Security gates ฝังใน pipeline |
| Manual deploy | GitOps (ArgoCD) auto-sync + self-heal |
| Static replicas | KEDA/HPA/VPA auto-scaling ตาม workload |
| Deploy แล้วหวังว่าจะ work | Prometheus analysis + Newman smoke test ก่อน promote |

---

## ลำดับการ Present (แนะนำ)

1. **Architecture Overview** (2 นาที)
   - เปิด `architectuer.md` -> อธิบาย tech stack + diagram

2. **Live Demo - CI/CD Pipeline** (5 นาที)
   - Push code -> แสดง Jenkins pipeline ทำงาน
   - หรือเปิด build ที่รันสำเร็จแล้ว -> walk through แต่ละ stage

3. **ArgoCD + Canary Rollout** (3 นาที)
   - เปิด ArgoCD UI -> แสดง app status
   - แสดง Rollout steps + AnalysisRun

4. **Security Reports** (2 นาที)
   - แสดง Trivy scan results
   - แสดง SBOM artifacts

5. **Monitoring** (2 นาที)
   - เปิด Grafana -> K8s dashboard
   - แสดง alert rules

6. **Code Walkthrough** (3 นาที)
   - แสดง Jenkinsfile config (FE vs BE)
   - แสดง Shared Library structure

---

## คำถามที่อาจถูกถาม + คำตอบ

### "ทำไมเลือก K3s ไม่ใช่ EKS?"
> K3s lightweight เหมาะกับ assignment ที่ต้องการ deploy เร็ว ค่าใช้จ่ายต่ำ แต่ production จริงผมจะใช้ EKS/GKE เพราะ managed control plane, auto-upgrade, better HA

### "ทำไมใช้ Jenkins ไม่ใช่ GitHub Actions?"
> Jenkins Shared Library ให้ความยืดหยุ่นสูง สร้าง reusable pipeline ที่ complex ได้ง่าย รองรับ multi-branch, parameterized builds, และ integration กับ enterprise tools ได้ดีกว่า

### "ทำไมใช้ Kustomize ไม่ใช่ Helm?"
> Kustomize ใช้ native YAML patches ไม่ต้อง template engine เข้าใจง่าย debug ง่าย เหมาะกับ GitOps pattern ที่ต้องการ review YAML changes ใน Git

### "Canary ดีกว่า Blue-Green อย่างไร?"
> Canary ค่อยๆ shift traffic ทีละนิด ถ้ามีปัญหากระทบ user น้อย + สามารถรัน automated analysis ระหว่าง rollout ได้ Blue-Green switch ทีเดียว 100% ถ้ามี bug กระทบ user ทั้งหมด

### "DAST scan ช้าไหม?"
> ZAP baseline scan ใช้เวลาประมาณ 2-5 นาที เป็น passive scan ไม่ invasive scan เฉพาะ canary service ที่ยังรับ traffic น้อย ไม่กระทบ production

### "ถ้า ArgoCD ตาย จะ deploy ยังไง?"
> ArgoCD เป็นแค่ sync mechanism ถ้าตาย cluster ยังทำงานปกติ (pods ไม่ตายตาม) แค่ไม่ sync ใหม่ พอ ArgoCD กลับมา จะ reconcile state ให้ตรงกับ Git อัตโนมัติ

### "Secret management ทำยังไง?"
> ตอนนี้ใช้ Kubernetes Secrets + Jenkins Credentials production จริงควรใช้ External Secrets Operator + HashiCorp Vault หรือ AWS Secrets Manager
