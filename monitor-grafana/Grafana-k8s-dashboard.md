# คู่มือการทำ Dashboard Kubernetes แบบ Production (Prometheus Agent + Prometheus Server + Grafana)

คู่มือนี้จะอธิบายตั้งแต่ **สิ่งที่ต้องติดตั้งใน Kubernetes**, วิธีทำ **Prometheus Agent ใน cluster**, วิธีตั้งค่า **Prometheus Server (ใหญ่)** ที่อยู่นอก cluster, และการทำ **Grafana Dashboard** โดยใช้ Template ID: `15661` หรือจะ customize ตามต้องการ

คู่มือนี้ออกแบบให้เป็น “แบบ Production Ready” สามารถใช้จริงได้ทันทีในองค์กร

---

# 1) สิ่งที่ต้องติดตั้งใน Kubernetes Cluster

## 1.1 องค์ประกอบพื้นฐานที่ต้องมี

* **kube-state-metrics** (สำหรับดึงสถานะ Kubernetes API เช่น pod/node/deployment)
* **Prometheus Agent (lightweight)** สำหรับ scrape metric และส่งออกไป Prometheus หลัก
* **CAdvisor (ผ่าน Kubelet API)** สำหรับ scrape CPU/Memory/Network ของ container และ node
* **Node Exporter (บน VM)** – ใช้ monitor VM ที่เป็น worker/control-plane

## 1.2 สาเหตุที่ไม่ควรให้ Prometheus ใหญ่ scrape ผ่าน NodePort

* ทำให้ metric ซ้ำหลายรอบ
* Node ตาย → metric หาย
* ไม่ปลอดภัย (Kubelet metrics / cAdvisor เผยข้อมูลเยอะ)
* ไม่ HA

### แนวทางที่ถูกต้อง

**ให้ Prometheus Agent scrape ทุกอย่างใน cluster จากภายใน**
จากนั้น **remote_write** ออกไปยัง Prometheus ใหญ่ที่อยู่นอก cluster

---

# 2) Deploy kube-state-metrics (ClusterIP)

ใช้ Helm ง่ายที่สุด:

```
helm repo add kube-state-metrics https://kubernetes.github.io/kube-state-metrics
helm install ksm kube-state-metrics/kube-state-metrics -n kube-system
```

หลังติดตั้งจะมี Service เช่น:

```
ksm-kube-state-metrics.kube-system.svc:8080
```

---

# 3) ติดตั้ง Prometheus Agent (ใน cluster)

Prometheus Agent ทำหน้าที่:

* scrape metric ทุกอย่างใน cluster
* ส่ง metrics ไปที่ Prometheus ใหญ่ด้วย remote_write
* ไม่มี UI / ไม่มี TSDB / ไม่มี alert rules
* ไลท์เวทมาก (production ใช้กันเยอะ)

## 3.1 สร้าง Namespace + RBAC

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: monitoring
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: prom-agent
  namespace: monitoring
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: prom-agent
rules:
  - apiGroups: [""]
    resources: ["nodes", "nodes/proxy", "endpoints", "pods", "services"]
    verbs: ["get", "list", "watch"]
  - nonResourceURLs: ["/metrics", "/metrics/cadvisor"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: prom-agent
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: prom-agent
subjects:
  - kind: ServiceAccount
    name: prom-agent
    namespace: monitoring
```

---

## 3.2 Prometheus Agent ConfigMap (scrape + remote_write)

แก้ URL ให้เป็น Prometheus ใหญ่ของคุณ

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: prom-agent-config
  namespace: monitoring
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
      evaluation_interval: 15s
      external_labels:
        cluster: "mcs-uat"

    remote_write:
      - url: "http://xxx.xxx.xxx.xxx:9090/api/v1/write"  # Prometheus ใหญ่

    scrape_configs:
      - job_name: 'kube-state-metrics'
        honor_labels: true
        static_configs:
          - targets:
            - 'ksm-kube-state-metrics.kube-system.svc.cluster.local:8080'

      - job_name: 'kubernetes-nodes-kubelet'
        scheme: https
        tls_config:
          insecure_skip_verify: true
        bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
        kubernetes_sd_configs:
          - role: node
        relabel_configs:
          - action: labelmap
            regex: __meta_kubernetes_node_label_(.+)
          - target_label: __address__
            replacement: kubernetes.default.svc:443
          - source_labels: [__meta_kubernetes_node_name]
            target_label: __metrics_path__
            replacement: /api/v1/nodes/${1}/proxy/metrics

      - job_name: 'kubernetes-nodes-cadvisor'
        scheme: https
        honor_timestamps: false   # ลด out-of-order
        tls_config:
          insecure_skip_verify: true
        bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
        kubernetes_sd_configs:
          - role: node
        relabel_configs:
          - action: labelmap
            regex: __meta_kubernetes_node_label_(.+)
          - target_label: __address__
            replacement: kubernetes.default.svc:443
          - source_labels: [__meta_kubernetes_node_name]
            target_label: __metrics_path__
            replacement: /api/v1/nodes/${1}/proxy/metrics/cadvisor
```

---

## 3.3 Deploy Prometheus Agent

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: prom-agent
  namespace: monitoring
spec:
  replicas: 1
  selector:
    matchLabels:
      app: prom-agent
  template:
    metadata:
      labels:
        app: prom-agent
    spec:
      serviceAccountName: prom-agent
      containers:
        - name: prom-agent
          image: prom/prometheus:v2.54.0
          args:
            - "--config.file=/etc/prometheus/prometheus.yml"
            - "--enable-feature=agent"
            - "--storage.agent.path=/prom-agent"
          ports:
            - containerPort: 9090
          volumeMounts:
            - name: config
              mountPath: /etc/prometheus
            - name: data
              mountPath: /prom-agent
      volumes:
        - name: config
          configMap:
            name: prom-agent-config
        - name: data
          emptyDir: {}
```

---

# 4) ตั้งค่า Prometheus ตัวใหญ่ (อยู่นอก cluster)

## 4.1 ต้องเปิด feature รับ remote_write

แก้ไฟล์ service ของ Prometheus:

```
--enable-feature=remote-write-receiver
```

ตัวอย่าง systemd:

```ini
ExecStart=/usr/local/bin/prometheus \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --enable-feature=remote-write-receiver
```

รีสตาร์ท:

```
systemctl daemon-reload
systemctl restart prometheus
```

---

## 4.2 ลบ job ที่ซ้ำซ้อนออกจาก Prometheus ใหญ่

ต้องลบ job เหล่านี้:

* kube-state-metrics
* kubernetes-nodes-kubelet
* kubernetes-nodes-cadvisor

เพราะงานนี้ agent ทำแทนแล้ว

ห้ามมี NodePort scrape ซ้ำ ไม่งั้น metric จะเพี้ยนหนักมาก

---

# 5) Grafana Dashboard (Template ID: 15661)

Dashboard นี้ประกอบด้วย:

* Node Overview (CPU/Memory/POD Count)
* Pod Usage Summary
* Cluster Resource
* Node Pressure/Condition
* Pod Status Summary

## 5.1 Data Source

* ให้ชี้ไปที่ Prometheus ตัวใหญ่

เพราะตอนนี้ metric ทั้งหมดมาจาก agent แล้ว

## 5.2 Variables ที่ใช้บน Template 15661

แนะนำกำหนด variables เหล่านี้:

* `$cluster` (optional)
* `$namespace`
* `$pod`
* `$node`
* `$container`

---

# 6) Query สำคัญที่ต้องแก้ใน Dashboard

## 6.1 จำนวน Pod ต่อ Node

```promql
count(kube_pod_info{cluster="mcs-uat"}) by (node)
```

## 6.2 Node Ready

```promql
max(kube_node_status_condition{cluster="mcs-uat", condition="Ready", status="true"}) by (node)
```

## 6.3 CPU Usage ของ Node

```promql
sum(irate(container_cpu_usage_seconds_total{cluster="mcs-uat", container!=""}[2m])) by (node)
```

## 6.4 Memory Working Set

```promql
sum(container_memory_working_set_bytes{cluster="mcs-uat", container!=""}) by (node)
```

## 6.5 FileSystem (แนะนำให้ใช้ Node Exporter แทน)

เพราะ container_fs_usage_bytes ถูก Deprecate

```promql
node_filesystem_avail_bytes{mountpoint="/"}
```

---

# 7) Optional: Monitoring PVC Storage

หากใช้ StatefulSet เช่น DB / NFS / MinIO

```promql
kubelet_volume_stats_used_bytes
kubelet_volume_stats_capacity_bytes
```

---

# 8) สรุป Architecture

```
                ┌───────────────────────┐
                │  Prometheus Server    │
                │     (นอก Cluster)    │
                └─────────┬─────────────┘
                          ▲ remote_write
                          │
                ┌─────────┴───────────┐
                │ Prometheus Agent    │ (ใน cluster)
                └─────────┬───────────┘
          scrape K8s API  │  scrape kube-state-metrics
                          │  scrape kubelet/cadvisor
```

---

# 9) จุดสำคัญที่ต้องตรวจให้ครบ

* [ ] Prometheus ใหญ่เปิด remote-write-receiver
* [ ] Agent ส่ง metrics ออกไปได้ (log ไม่มี 404)
* [ ] Prometheus ใหญ่ **ไม่ได้ scrape NodePort** ซ้ำแล้ว
* [ ] kube-state-metrics ทำงานปกติ
* [ ] cadvisor ลด out-of-order ด้วย honor_timestamps: false
* [ ] Grafana ใช้ datasource = Prometheus ใหญ่
* [ ] Dashboard 15661 ปรับ query ให้รวม label cluster=(ของคุณ)

---

# 10) Alert: ตรวจจับ Container / Pod Down (Production Ready)

ส่วนนี้คือแนวทาง **การทำ Alert Query เพื่อเช็คสถานะ container / pod** ว่ามีการ down, crash, หรือหายไปจากระบบ โดยออกแบบให้ใช้ได้จริงกับ Kubernetes + Prometheus + Grafana/Alertmanager

---

## 10.1 หลักคิดที่ถูกต้อง (สำคัญมาก)

❌ **ไม่ควร alert จาก metric CPU / Memory = 0**
เพราะ container ที่ idle ก็อาจเป็น 0 ได้ตามปกติ

✔ **ควร alert จาก “สถานะ” ของ Kubernetes** เช่น:

* Pod ไม่ Ready
* Container ไม่ Ready
* Pod หายไป (ถูกลบ / crash loop)

Metric หลักที่ใช้คือ:

* `kube_pod_status_ready`
* `kube_pod_container_status_ready`
* `kube_pod_container_status_running`
* `kube_pod_container_status_restarts_total`

---

## 10.2 Alert: Container ไม่ Ready (แนะนำที่สุด)

> กรณีนี้คือ **alert เมื่อ container ไม่ Ready ทุก pod (เช่น 3/3 pod down)** เท่านั้น
> เหมาะกับ Deployment / StatefulSet ที่มีหลาย replica และไม่อยาก alert ตอนล่มแค่บางตัว

---

### แนวคิด

* ปกติ Deployment มี replica หลายตัว (เช่น 3 pod)
* ถ้า pod ล่มแค่ 1 ตัว → Kubernetes ยังถือว่า service ใช้งานได้
* ต้องการ alert **เฉพาะกรณีที่ "ทุก pod ไม่ Ready"**

หลักคิดคือ:

```
จำนวน pod ที่ Ready == 0
แต่
จำนวน pod ทั้งหมด > 0
```

---

### PromQL (Container Down ทุก Pod)

```promql
(
  sum by (namespace, container) (
    kube_pod_container_status_ready {namespace="default",container!="",container!="POD"} == 1
)
== 0
and
(
  sum by (namespace, deployment) (
    kube_pod_info{cluster="mcs-uat"}
  ) > 0
)
```

> เงื่อนไขนี้จะ alert ก็ต่อเมื่อ **ทุก pod ของ deployment นั้นไม่ Ready**

---

### Alert Rule ตัวอย่าง

```yaml
- alert: K8sAllContainersDown
  expr: (
    sum by (namespace, container) (
      kube_pod_container_status_ready {namespace="default",container!="",container!="POD"} == 1
  ) == 0
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "All pods are down"
    description: "All containers in deployment {{ $labels.namespace }}/{{ $labels.deployment }} are NOT ready"
```

---

### ตัวอย่างพฤติกรรม

| สถานะ     | Pod Ready    | Alert       |
| --------- | ------------ | ----------- |
| 1/3 Ready | ยังมี Ready  | ❌ ไม่ alert |
| 2/3 Ready | ยังมี Ready  | ❌ ไม่ alert |
| 0/3 Ready | ทุก pod down | ✅ Alert     |

---

### หมายเหตุสำคัญ

* ต้องมี label `deployment` (มาจาก kube-state-metrics)
* ถ้าเป็น StatefulSet ให้เปลี่ยน `deployment` เป็น `statefulset`
* ถ้าเป็น DaemonSet ใช้ `desired == ready` แทนจะเหมาะกว่า

---

## 10.3 Alert: Pod Down / Pod ไม่ Ready

### ใช้เมื่อ:

* Pod ทั้งก้อนล่ม
* Node มีปัญหา
* Scheduling fail

### PromQL

```promql
kube_pod_status_ready{cluster="mcs-uat", condition="true"} == 0
```

### Alert Rule

```yaml
- alert: K8sPodNotReady
  expr: kube_pod_status_ready{cluster="mcs-uat", condition="true"} == 0
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Pod not ready"
    description: "Pod {{ $labels.namespace }}/{{ $labels.pod }} is NOT ready"
```

---

## 10.4 Alert: Container CrashLoopBackOff

### ใช้เมื่อ:

* container crash ซ้ำ ๆ
* application start ไม่ขึ้น

### PromQL

```promql
increase(
  kube_pod_container_status_restarts_total{
    cluster="mcs-uat",
    container!="",
    container!="POD"
  }[5m]
) > 0
```

### Alert Rule

```yaml
- alert: K8sContainerCrashLoop
  expr: increase(kube_pod_container_status_restarts_total{cluster="mcs-uat",container!="",container!="POD"}[5m]) > 0
  for: 1m
  labels:
    severity: warning
  annotations:
    summary: "Container restarting"
    description: "Container {{ $labels.container }} in pod {{ $labels.namespace }}/{{ $labels.pod }} is restarting"
```

---

## 10.5 Alert: Pod หายไป (สำคัญสำหรับ Stateful / Critical Service)

### แนวคิด

ถ้า Pod เคยมี แต่ตอนนี้ **ไม่มี metric แล้ว** → ถือว่าหาย

### PromQL

```promql
absent(
  kube_pod_info{cluster="mcs-uat", pod="my-critical-pod"}
)
```

ใช้กับ pod สำคัญเท่านั้น (เช่น database, ingress)

---

## 10.6 Alert: Deployment Replica ไม่ครบ

### ใช้เมื่อ:

* rolling update ค้าง
* pod crash จน replica ไม่ครบ

### PromQL

```promql
kube_deployment_status_replicas_available{cluster="mcs-uat"}
<
kube_deployment_spec_replicas{cluster="mcs-uat"}
```

---

## 10.7 Alert ระดับ Node ที่กระทบ Container

### Node Not Ready

```promql
kube_node_status_condition{cluster="mcs-uat", condition="Ready", status="true"} == 0
```

---

## 10.8 Best Practice (สำคัญมาก)

✔ ใช้ `for:` อย่างน้อย 1–2 นาที (กัน false positive)
✔ แยก severity:

* `critical` → app down จริง
* `warning` → crash / restart

✔ อย่า alert ทุก container พร้อมกัน (noise สูง)
✔ ใช้ label:

* `namespace`
* `pod`
* `container`

---

## 10.9 คำแนะนำสุดท้าย

ถ้าเลือกได้ **ให้ alert จาก Kubernetes status เสมอ**
ไม่ต้อง alert จาก CPU/MEM ว่า = 0

> Kubernetes บอกสถานะได้แม่นกว่า metric resource usage

---

# 11) ตัวอย่าง Alert Template (Alertmanager) ทั้ง 5 Strategy

ส่วนนี้เป็น **Alertmanager Template (Go Template)** สำหรับ Kubernetes โดยอิงรูปแบบเดียวกับ MongoDB template ที่คุณให้มา สามารถใช้ส่ง Slack / Line / MS Teams ได้ทันที

---

## Strategy 1: Container Down (All Pods of Container)

```gotemplate
{{ define "k8s-container-down.title" -}}
{{- if eq .Status "firing" -}}🚨 [CRITICAL] Container Down{{- else -}}✅ [RESOLVED] Container Recovered{{- end -}}
{{- end }}

{{ define "k8s-container-down.message" -}}
{{- $status := .Status -}}
{{- $cluster := or (index .CommonLabels "cluster") "Unknown" -}}

{{- if eq $status "firing" -}}
🚨 **Container DOWN (All Pods)** 🚨
{{- else -}}
✅ **Container Recovered** ✅
{{- end }}

**Cluster:** {{ $cluster }}

{{- range .Alerts }}
• Namespace: {{ .Labels.namespace }}
• Container: {{ .Labels.container }}
{{- end }}

{{- $latest := "" -}}
{{- if gt (len .Alerts) 0 -}}
  {{- if eq $status "resolved" -}}
    {{- $latest = (index .Alerts 0).EndsAt -}}
    {{- range .Alerts -}}
      {{- if and .EndsAt (gt .EndsAt.Unix $latest.Unix) -}}{{- $latest = .EndsAt -}}{{- end -}}
    {{- end -}}
  {{- else -}}
    {{- $latest = (index .Alerts 0).StartsAt -}}
    {{- range .Alerts -}}
      {{- if and .StartsAt (gt .StartsAt.Unix $latest.Unix) -}}{{- $latest = .StartsAt -}}{{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- if $latest -}}
{{"\n\n"}}**Time:** {{ $latest.Format "02/01/2006 15:04" }}
{{- end -}}
{{- end }}
```

---

## Strategy 2: Pod Down

```gotemplate
{{ define "k8s-pod-down.title" -}}
{{- if eq .Status "firing" -}}🚨 [CRITICAL] Pod Down{{- else -}}✅ [RESOLVED] Pod Recovered{{- end -}}
{{- end }}

{{ define "k8s-pod-down.message" -}}
{{- $cluster := or (index .CommonLabels "cluster") "Unknown" -}}

🚨 **Pod Status Alert** 🚨

**Cluster:** {{ $cluster }}

{{- range .Alerts }}
• Namespace: {{ .Labels.namespace }}
• Pod: {{ .Labels.pod }}
{{- end }}

{{- $latest := "" -}}
{{- if gt (len .Alerts) 0 -}}
  {{- if eq $status "resolved" -}}
    {{- $latest = (index .Alerts 0).EndsAt -}}
    {{- range .Alerts -}}
      {{- if and .EndsAt (gt .EndsAt.Unix $latest.Unix) -}}{{- $latest = .EndsAt -}}{{- end -}}
    {{- end -}}
  {{- else -}}
    {{- $latest = (index .Alerts 0).StartsAt -}}
    {{- range .Alerts -}}
      {{- if and .StartsAt (gt .StartsAt.Unix $latest.Unix) -}}{{- $latest = .StartsAt -}}{{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- if $latest -}}
{{"\n\n"}}**Time:** {{ $latest.Format "02/01/2006 15:04" }}
{{- end -}}
{{- end }}
```

---

## Strategy 3: CrashLoopBackOff

```gotemplate
{{ define "k8s-crashloop.title" -}}
{{- if eq .Status "firing" -}}⚠️ [WARNING] CrashLoopBackOff{{- else -}}✅ [RESOLVED] CrashLoop Resolved{{- end -}}
{{- end }}

{{ define "k8s-crashloop.message" -}}
{{- $cluster := or (index .CommonLabels "cluster") "Unknown" -}}

⚠️ **Container Restart Detected**

**Cluster:** {{ $cluster }}

{{- range .Alerts }}
• Namespace: {{ .Labels.namespace }}
• Pod: {{ .Labels.pod }}
• Container: {{ .Labels.container }}
{{- end }}

{{- $latest := "" -}}
{{- if gt (len .Alerts) 0 -}}
  {{- if eq $status "resolved" -}}
    {{- $latest = (index .Alerts 0).EndsAt -}}
    {{- range .Alerts -}}
      {{- if and .EndsAt (gt .EndsAt.Unix $latest.Unix) -}}{{- $latest = .EndsAt -}}{{- end -}}
    {{- end -}}
  {{- else -}}
    {{- $latest = (index .Alerts 0).StartsAt -}}
    {{- range .Alerts -}}
      {{- if and .StartsAt (gt .StartsAt.Unix $latest.Unix) -}}{{- $latest = .StartsAt -}}{{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- if $latest -}}
{{"\n\n"}}**Time:** {{ $latest.Format "02/01/2006 15:04" }}
{{- end -}}
{{- end }}
```

---

## Strategy 4: Deployment Replica Not Ready

```gotemplate
{{ define "k8s-deployment-replica.title" -}}
{{- if eq .Status "firing" -}}⚠️ [WARNING] Deployment Replica Issue{{- else -}}✅ [RESOLVED] Deployment Healthy{{- end -}}
{{- end }}

{{ define "k8s-deployment-replica.message" -}}
{{- $cluster := or (index .CommonLabels "cluster") "Unknown" -}}

⚠️ **Deployment Replica Mismatch**

**Cluster:** {{ $cluster }}

{{- range .Alerts }}
• Namespace: {{ .Labels.namespace }}
• Deployment: {{ .Labels.deployment }}
{{- end }}

{{- $latest := "" -}}
{{- if gt (len .Alerts) 0 -}}
  {{- if eq $status "resolved" -}}
    {{- $latest = (index .Alerts 0).EndsAt -}}
    {{- range .Alerts -}}
      {{- if and .EndsAt (gt .EndsAt.Unix $latest.Unix) -}}{{- $latest = .EndsAt -}}{{- end -}}
    {{- end -}}
  {{- else -}}
    {{- $latest = (index .Alerts 0).StartsAt -}}
    {{- range .Alerts -}}
      {{- if and .StartsAt (gt .StartsAt.Unix $latest.Unix) -}}{{- $latest = .StartsAt -}}{{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- if $latest -}}
{{"\n\n"}}**Time:** {{ $latest.Format "02/01/2006 15:04" }}
{{- end -}}
{{- end }}
```

---

## Strategy 5: Node Not Ready

```gotemplate
{{ define "k8s-node-down.title" -}}
{{- if eq .Status "firing" -}}🚨 [CRITICAL] Node Down{{- else -}}✅ [RESOLVED] Node Recovered{{- end -}}
{{- end }}

{{ define "k8s-node-down.message" -}}
{{- $cluster := or (index .CommonLabels "cluster") "Unknown" -}}

🚨 **Node Status Alert** 🚨

**Cluster:** {{ $cluster }}

{{- range .Alerts }}
• Node: {{ .Labels.node }}
{{- end }}

{{- $latest := "" -}}
{{- if gt (len .Alerts) 0 -}}
  {{- if eq $status "resolved" -}}
    {{- $latest = (index .Alerts 0).EndsAt -}}
    {{- range .Alerts -}}
      {{- if and .EndsAt (gt .EndsAt.Unix $latest.Unix) -}}{{- $latest = .EndsAt -}}{{- end -}}
    {{- end -}}
  {{- else -}}
    {{- $latest = (index .Alerts 0).StartsAt -}}
    {{- range .Alerts -}}
      {{- if and .StartsAt (gt .StartsAt.Unix $latest.Unix) -}}{{- $latest = .StartsAt -}}{{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- if $latest -}}
{{"\n\n"}}**Time:** {{ $latest.Format "02/01/2006 15:04" }}
{{- end -}}
{{- end }}
```

---

## หมายเหตุการใช้งาน

* ใช้ร่วมกับ Alertmanager `templates:`
* สามารถ map ไป Slack / Line / Teams ได้
* รองรับ firing / resolved
* ใช้ `.CommonLabels` สำหรับข้อมูลร่วม

---

# 12) สรุป Alert Strategy ที่แนะนำ

| กรณี                      | Metric                                    | ระดับ    |
| ------------------------- | ----------------------------------------- | -------- |
| Container down            | kube_pod_container_status_ready           | Critical |
| Pod down                  | kube_pod_status_ready                     | Critical |
| CrashLoop                 | kube_pod_container_status_restarts_total  | Warning  |
| Deployment replica ไม่ครบ | kube_deployment_status_replicas_available | Warning  |
| Node down                 | kube_node_status_condition                | Critical |

---
