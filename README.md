# DevOps Assignment - Production-Grade Microservice Deployment

## Project Overview

Production-grade CI/CD platform สำหรับ deploy microservices บน Kubernetes ด้วยแนวคิด **GitOps** และ **DevSecOps**

Deploy **2 services** (Frontend + Backend) บน **K3s cluster** (AWS) ผ่าน Jenkins pipeline ที่สร้างเป็น **Groovy Shared Library** ใช้ได้กับทุกโปรเจค

---

## Tech Stack

| Layer | Technology |
| --- | --- |
| Cloud | AWS EC2 (ap-southeast-1) |
| IaC | Terraform |
| OS / K8s | Amazon Linux 2023 + K3s (1 Server + 2 Agents) |
| Container | Docker (multi-stage build) + Buildx (layer caching) |
| Container Registry | GitLab Container Registry |
| CI/CD | Jenkins + Groovy Shared Library |
| GitOps | ArgoCD + Kustomize |
| Progressive Delivery | Argo Rollouts (Canary Strategy) |
| Auto Scaling | KEDA / HPA / VPA |
| Monitoring | Prometheus + Grafana |
| Logging | Loki + Grafana Alloy |
| Security Scan | Trivy (Image Scan + SBOM) + OWASP ZAP (DAST) |
| Notification | Discord Webhook |

---

## Architecture

### Application Architecture

```text
                        ┌──────────────────────────┐
                        │        End Users          │
                        └────────────┬─────────────┘
                                     │
                                     ▼
                        ┌──────────────────────────┐
                        │    Ingress (Nginx/K3s)    │
                        │       Port 80 / 443       │
                        └─────┬────────────┬───────┘
                              │            │
                  ┌───────────▼──┐    ┌────▼───────────┐
                  │   Frontend   │    │    Backend      │
                  │  (React/     │    │  (Python/       │
                  │   Node.js)   │    │   FastAPI)      │
                  │  Port: 8080  │    │  Port: 10104    │
                  └──────────────┘    └────────┬───────┘
                                               │
                                               ▼
                                        ┌──────────┐
                                        │ MongoDB  │
                                        │ (Replica │
                                        │   Set)   │
                                        └──────────┘
```

### Infrastructure Architecture (AWS)

```text
┌─────────────────────────────────────────────────────────────────┐
│                     AWS (ap-southeast-1)                        │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                Security Group (k3s-sg)                    │  │
│  │          Ports: 22, 80, 443, 6443 (K8s API)               │  │
│  │                                                           │  │
│  │  ┌─────────────────┐  ┌──────────┐  ┌──────────┐         │  │
│  │  │  EC2 (t3.small) │  │   EC2    │  │   EC2    │         │  │
│  │  │  K3s Server     │  │  Agent-1 │  │  Agent-2 │         │  │
│  │  │  (Master)       │  │ (Worker) │  │ (Worker) │         │  │
│  │  └─────────────────┘  └──────────┘  └──────────┘         │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  Provisioned by: Terraform                                      │
│  AMI: Amazon Linux 2023 | K3s Lightweight Kubernetes            │
└─────────────────────────────────────────────────────────────────┘
```

---

## CI/CD Pipeline

Jenkins pipeline ใช้ **Shared Library** เป็น entry point เดียว (`generalPipeline`) รองรับทั้ง frontend และ backend

```text
Developer ──git push──► GitLab ──webhook──► Jenkins Pipeline
                                                │
                                    1. Checkout + Setup
                                    2. Guard (skip if no changes)
                                    3. Semantic Release (auto version)
                                    4. Docker Build (buildx + cache)
                                    5. Security Scan (Trivy + SBOM)
                                    6. Push Image (GitLab Registry)
                                    7. Update Manifests (Kustomize)
                                    8. ArgoCD Sync
                                    9. DAST Scan (OWASP ZAP, canary only)
                                   10. Health Check + Discord Notify
```

### Pipeline Modes

| Mode | Build | Deploy App | Deploy CronJob |
| --- | --- | --- | --- |
| `build-deploy-all` | Yes | Yes | Yes |
| `build-deploy-app` | Yes | Yes | No |
| `build-only` | Yes | No | No |
| `deploy-app` | No | Yes | No |
| `deploy-cronjob` | No | No | Yes |

### Canary Deployment (Argo Rollouts)

```text
 25% traffic ──► pause 15s ──► Prometheus Analysis (error rate, restarts)
      │
 50% traffic ──► Analysis ──► pause (manual approve)
      │
100% traffic ──► Full promotion
```

ถ้า analysis fail -> rollout จะถูก abort อัตโนมัติ กลับไปใช้ stable version

### Security Gates

| Scanner | Gate Criteria | Action on Fail |
| --- | --- | --- |
| Trivy Image Scan | CRITICAL <= 0, HIGH <= 5 | Prompt user / UNSTABLE |
| OWASP ZAP (DAST) | HIGH = 0, MEDIUM = 0 | Abort canary rollout |
| SBOM | - | Generate SPDX + CycloneDX |

---

## Services

### Frontend - test-jenkins-app

| Item | Detail |
| --- | --- |
| Framework | React + Express.js |
| Port | 8080 |
| Dockerfile | Multi-stage (build + run), node:20-alpine |
| Auto Scaling | KEDA (CPU > 75%, Memory > 75%, 2-6 replicas) |
| Health Check | `GET /` |
| Deployment | Canary (Argo Rollouts) |

### Backend - test-python-backend-api

| Item | Detail |
| --- | --- |
| Framework | FastAPI (Python 3.11) |
| Port | 10104 |
| Database | MongoDB (Replica Set) |
| Config | `config.yaml` mount via K8s Secret |
| Auto Scaling | VPA |
| Health Check | `GET /health`, `/health/ready`, `/health/live` |
| API Docs | `/docs` (Swagger UI), `/openapi.json` |
| CronJob | Smoke test every 1 minute |
| Deployment | Canary (Argo Rollouts) |

---

## Monitoring & Logging

### Prometheus + Grafana

```text
K3s Cluster
  ├── Prometheus Agent (in-cluster scrape)
  │     ├── kube-state-metrics
  │     ├── cAdvisor (Kubelet)
  │     └── Node Exporter
  │           │
  │       remote_write
  │           │
  │           ▼
  │     Prometheus Server (external)
  │           │
  │           ▼
  └── Grafana
        ├── K8s Dashboard (Template 15661)
        ├── CPU / Memory / Disk Monitoring
        ├── Nginx Log Dashboard
        └── Alert Rules → Discord
```

### Grafana Alerting

| Alert | Condition | Channel |
| --- | --- | --- |
| Container Down (All Pods) | All pods down | Discord |
| Pod Not Ready | Pod not ready > threshold | Discord |
| CrashLoopBackOff | Container in crash loop | Discord |
| Replica Mismatch | Desired != Available | Discord |
| Node Not Ready | Node not ready | Discord |
| High CPU / Memory / Disk | Usage > threshold | Discord |

### Logging (Loki)

- **Grafana Alloy** agent บน cluster ส่ง logs ไปยัง **Loki**
- ดู logs ผ่าน **Grafana Log Explorer**
- รองรับ JSON log format

---

## Repository Structure

โปรเจคใช้ **multi-branch** strategy ใน GitLab repository เดียว:

| Branch | Content |
| --- | --- |
| `main` | Documentation + Monitoring guides |
| `jenkins-shared-library` | Jenkins Shared Library (Groovy) |
| `terraform` | Terraform IaC (AWS EC2 + K3s) |
| `test-jenkins-app` | Frontend app (React) |
| `test-python-backend-api` | Backend app (FastAPI) |
| `test-fe-manifest` | K8s manifests - Frontend (auto-generated) |
| `test-be-api-manifest` | K8s manifests - Backend (auto-generated) |

---

## How to Deploy

### Prerequisites

- AWS Account + CLI configured
- Terraform >= 1.5.0
- kubectl
- Jenkins server with shared library configured
- ArgoCD + Argo Rollouts installed on cluster
- GitLab account with container registry

### Step 1: Provision Infrastructure

```bash
git checkout terraform
terraform init
terraform plan
terraform apply
```

ได้ EC2 instances 3 ตัว (1 K3s Server + 2 Agents) พร้อม Security Group

### Step 2: Install K3s

```bash
# On server node
curl -sfL https://get.k3s.io | sh -

# Get join token
cat /var/lib/rancher/k3s/server/node-token

# On each agent node
curl -sfL https://get.k3s.io | K3S_URL=https://<SERVER_IP>:6443 K3S_TOKEN=<TOKEN> sh -
```

### Step 3: Setup Jenkins

```bash
git checkout jenkins-shared-library
cd ansible
ansible-playbook -i inventory.ini site.yaml
```

Jenkins deploy เป็น Docker container พร้อมเครื่องมือทั้งหมด (Docker, Kustomize, ArgoCD CLI, Trivy, Node.js, Python)

### Step 4: Setup ArgoCD + Argo Rollouts

```bash
# ArgoCD
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Argo Rollouts
kubectl create namespace argo-rollouts
kubectl apply -n argo-rollouts -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml
```

### Step 5: Create Secrets on Cluster

```bash
# Image pull secret (GitLab Registry)
kubectl create secret docker-registry pull-secret \
  --docker-server=registry.gitlab.com \
  --docker-username=<USER> \
  --docker-password=<TOKEN>

# Backend config
kubectl create secret generic test-config-api \
  --from-file=config.yaml=config.yaml
```

### Step 6: Run Pipeline

Push code -> GitLab webhook triggers Jenkins -> Pipeline builds, scans, deploys automatically

```groovy
// Jenkinsfile example
@Library('devops-assignment-test@jenkins-shared-library') _

def cfg = [
  projectName : 'my-app',
  language    : 'javascript',
  ci: [ deployStrategy: 'canary', autoSync: 'Yes' ],
  deployment: [ containerPort: 8080, nodePort: 30001 ],
]

generalPipeline(cfg)
```

### Step 7: Setup Monitoring

```bash
# kube-state-metrics
helm install ksm kube-state-metrics/kube-state-metrics -n kube-system

# Deploy Prometheus Agent in cluster
kubectl apply -f prometheus-agent.yaml -n monitoring

# Grafana: Import K8s Dashboard (ID: 15661) + setup alert rules
```

---

## Kubernetes Resource Overview

| Resource | Frontend (test-fe) | Backend (test-be-api) |
| --- | --- | --- |
| Deployment Strategy | Argo Rollout (Canary) | Argo Rollout (Canary) |
| Container Port | 8080 | 10104 |
| NodePort | 30005 | 30021 |
| CPU Request / Limit | 250m / 1000m | 350m / 750m |
| Memory Request / Limit | 512Mi / 1Gi | 256Mi / 512Mi |
| Auto Scaling | KEDA (2-6 replicas) | VPA |
| Health Check | `/` (HTTP) | `/health` (HTTP) |
| Config Injection | Env vars | config.yaml (Secret mount) |

---

## Documentation

| File | Content |
| --- | --- |
| [Architecture](devops-assignment-test/architectuer.md) | Architecture diagrams + Tech stack |
| [CI/CD Pipeline](devops-assignment-test/cicd-piline.md) | Pipeline stages + Jenkinsfile config |
| [ArgoCD & Rollouts](devops-assignment-test/argocd.md) | ArgoCD + Canary deployment |
| [Frontend](devops-assignment-test/fe-test.md) | Frontend app documentation |
| [Backend](devops-assignment-test/be-test.md) | Backend app + API documentation |
| [Grafana Dashboard](monitor-grafana/Grafana-k8s-dashboard.md) | K8s monitoring setup |
| [Grafana Alerting](monitor-grafana/Grafana-Alert-CPU-Mem-Disk.md) | Alert rules configuration |
| [Nginx Log Dashboard](monitor-grafana/Nginx-log-exporter.md) | Nginx log monitoring |
| [Jenkins Shared Library](DOCUMENTATION.md) | Full shared library documentation |
