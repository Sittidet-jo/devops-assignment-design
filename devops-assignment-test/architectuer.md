# Architecture Overview

## Tech Stack

| Layer              | Technology                                                  |
|--------------------|-------------------------------------------------------------|
| Cloud              | AWS (ap-southeast-1)                                        |
| IaC                | Terraform                                                   |
| OS / K8s           | Amazon Linux 2023 + K3s (1 Server + 2 Agents)              |
| Container Runtime  | Docker (multi-stage build)                                  |
| Container Registry | GitLab Container Registry (skywalker.inet.co.th:5050)       |
| CI/CD              | Jenkins + Groovy Shared Library                             |
| GitOps             | ArgoCD + Kustomize                                          |
| Deployment         | Argo Rollouts (Canary Strategy)                             |
| Auto Scaling       | KEDA / HPA / VPA                                            |
| Monitoring         | Prometheus + Grafana                                        |
| Logging            | Loki + Grafana                                              |
| Security Scan      | Trivy (image scan) + OWASP ZAP (DAST) + SonarQube (SAST)   |
| Notification       | Discord Webhook                                             |

---

## Application Architecture

```
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
                    │              │    │                 │
                    │  NodePort:   │    │  NodePort:      │
                    │  30001       │    │  30021          │
                    └──────────────┘    └─────────────────┘
                                              │
                                              ▼
                                       ┌──────────┐
                                       │ Database │
                                       └──────────┘
```

---

## Infrastructure Architecture (AWS)

```
┌─────────────────────────────────────────────────────────────────┐
│                     AWS (ap-southeast-1)                        │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                Security Group (k3s-sg)                    │  │
│  │          Ports: 22, 80, 443, 6443 (K8s API)               │  │
│  │                                                           │  │
│  │  ┌─────────────────┐  ┌──────────┐  ┌──────────┐         │  │
│  │  │  EC2 (t3.micro) │  │   EC2    │  │   EC2    │         │  │
│  │  │  K3s Server     │  │  Agent-1 │  │  Agent-2 │         │  │
│  │  │  (Master)       │  │ (Worker) │  │ (Worker) │         │  │
│  │  └─────────────────┘  └──────────┘  └──────────┘         │  │
│  │                                                           │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

Provisioned by: Terraform
  - AMI: Amazon Linux 2023 (x86_64)
  - K3s: Lightweight Kubernetes
  - Nodes: 1 Server + 2 Agents (configurable via variable)
```

---

## CI/CD Pipeline Architecture

```
┌──────────┐    git push     ┌──────────────────────────────────────────────┐
│  GitLab  │ ──────────────► │                  Jenkins                     │
│   Repo   │   webhook       │                                              │
└──────────┘                 │  ┌──────────────────────────────────────┐    │
                             │  │        generalPipeline (Shared Lib)  │    │
                             │  │                                      │    │
                             │  │  1. Setup & Checkout                 │    │
                             │  │     └─ git clone + ciSetup           │    │
                             │  │                                      │    │
                             │  │  2. Guard (Release-worthy Changes)   │    │
                             │  │     └─ skip if no meaningful changes │    │
                             │  │                                      │    │
                             │  │  3. Create Release                   │    │
                             │  │     └─ auto version (git tag)        │    │
                             │  │                                      │    │
                             │  │  4. Build Docker Image               │    │
                             │  │     └─ docker buildx (multi-stage)   │    │
                             │  │                                      │    │
                             │  │  5. Security Scan                    │    │
                             │  │     ├─ Trivy (image vulnerabilities) │    │
                             │  │     └─ SBOM (SPDX + CycloneDX)      │    │
                             │  │                                      │    │
                             │  │  6. Push Image                       │    │
                             │  │     └─ GitLab Container Registry     │    │
                             │  │                                      │    │
                             │  │  7. Update Manifests (Kustomize)     │    │
                             │  │     └─ push to manifest branch       │    │
                             │  │                                      │    │
                             │  │  8. ArgoCD Sync                      │    │
                             │  │     ├─ autoSync=Yes → auto deploy    │    │
                             │  │     └─ autoSync=No  → manual approve │    │
                             │  │                                      │    │
                             │  │  9. DAST Scan (OWASP ZAP)            │    │
                             │  │     └─ canary only                   │    │
                             │  │                                      │    │
                             │  │ 10. Health Check & Notify            │    │
                             │  │     └─ Discord notification          │    │
                             │  └──────────────────────────────────────┘    │
                             └──────────────────────────────────────────────┘
                                              │
                                              ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         GitOps (ArgoCD)                                  │
│                                                                          │
│   Manifest Branch ──► ArgoCD watches ──► Sync to K3s Cluster            │
│                                                                          │
│   Deployment Strategy: Canary (Argo Rollouts)                           │
│   ┌─────────────────────────────────────────────────────────────┐       │
│   │  Step 1: setWeight 25%  ──► pause 15s                      │       │
│   │  Step 2: AnalysisRun (Newman / Prometheus)                 │       │
│   │  Step 3: setWeight 50%  ──► AnalysisRun                    │       │
│   │  Step 4: pause (manual approve or auto)                    │       │
│   │  Step 5: setWeight 100% ──► full rollout                   │       │
│   └─────────────────────────────────────────────────────────────┘       │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Monitoring & Logging Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      K3s Cluster                             │
│                                                              │
│  ┌────────────┐  ┌────────────┐  ┌────────────────────────┐ │
│  │  Frontend   │  │  Backend   │  │   CronJobs             │ │
│  │  Pod(s)     │  │  Pod(s)    │  │  (smoke-test-task)     │ │
│  └─────┬──────┘  └─────┬──────┘  └────────────────────────┘ │
│        │               │                                     │
│        ▼               ▼                                     │
│  ┌──────────────────────────────┐                            │
│  │      Prometheus              │   scrape metrics           │
│  │  (kube-state-metrics,        │   /health, /metrics        │
│  │   node-exporter, cadvisor)   │                            │
│  └──────────────┬───────────────┘                            │
│                 │                                            │
│                 ▼                                            │
│  ┌──────────────────────────────┐                            │
│  │         Grafana              │                            │
│  │  ┌────────────────────────┐  │                            │
│  │  │ K8s Dashboard          │  │                            │
│  │  │ CPU/Mem/Disk Alerts    │  │                            │
│  │  │ Nginx Log Dashboard    │  │                            │
│  │  └────────────────────────┘  │                            │
│  └──────────────────────────────┘                            │
│                                                              │
│  ┌──────────────────────────────┐                            │
│  │     Loki (Log Aggregation)   │                            │
│  │     + Grafana Log Explorer   │                            │
│  └──────────────────────────────┘                            │
│                                                              │
│  ┌──────────────────────────────┐                            │
│  │     KEDA (Auto Scaling)      │                            │
│  │  Triggers:                   │                            │
│  │  - CPU Utilization > 75%     │                            │
│  │  - Memory Utilization > 75%  │                            │
│  │  Scale: 2 → 6 replicas       │                            │
│  └──────────────────────────────┘                            │
│                                                              │
└──────────────────────────────────────────────────────────────┘

Alerting:
  - CPU / Memory / Disk usage thresholds
  - Pod crash detection
  - Configured via Grafana Alert Rules → Discord
```

---

## Kubernetes Resource Overview

| Resource            | Frontend (test-jenkins-app) | Backend (test-be-api)       |
|---------------------|-----------------------------|-----------------------------|
| Deployment Strategy | Argo Rollout (Canary)       | Argo Rollout (Canary)       |
| Container Port      | 8080                        | 10104                       |
| NodePort            | 30005                       | 30021                       |
| CPU Request/Limit   | 250m / 1000m                | 350m / 750m                 |
| Memory Req/Limit    | 512Mi / 1Gi                 | 256Mi / 512Mi               |
| Auto Scaling        | KEDA (2-6 replicas)         | VPA                         |
| Health Check        | / (HTTP)                    | /health (HTTP)              |
| Config Injection    | Env vars (HOSTNAME, PORT)   | config.yaml (Secret mount)  |
| Namespace           | default                     | default                     |
