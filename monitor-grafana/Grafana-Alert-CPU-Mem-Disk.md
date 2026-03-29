# คู่มือการตั้งค่า Grafana Alert สำหรับ CPU, Memory, Disk และส่งแจ้งเตือนเข้า Discord (อัปเดต: แสดง Usage พร้อม Used/Total + หน่วยตาม label `unit`)

## 1. บทนำ

คู่มือนี้แสดงขั้นตอนการตั้งค่า **Grafana Alert** เพื่อติดตามการใช้งาน **CPU, Memory และ Disk** พร้อมระบบแจ้งเตือนผ่าน **Discord** โดยครอบคลุมตั้งแต่การสร้าง Alert Rule, Notification Template, Contact Point, Notification Policy และ **การแสดงผลค่าแบบเต็ม** เช่น `Usage: 50.00% ( 8.00 GB of 16 GB )` หรือ `Usage: 85.00% ( 3.40 of 4 Core )` ในข้อความแจ้งเตือน โดยใช้หน่วยจาก **label `unit`**

---

## 2. สร้าง Notification Template

**Notification Template** คือโค้ดที่ควบคุมรูปแบบข้อความแจ้งเตือน (Go Template) ซึ่งอ้างอิงค่าจาก Alert ได้แบบไดนามิก

### วิธีสร้าง

1. ไปที่ **Alerting > Notification templates**
2. คลิก **New template**
3. ตั้งชื่อ `discord-alert-format`
4. วางโค้ดด้านล่าง (รองรับ `%` เดิม และข้อความเต็มจาก `value`) :

```gotemplate
{{ define "discord-alert-format.title" }}{{ end }}

{{ define "discord-alert-format.message" }}
{{- $status := .Status -}}
{{- $res := or .CommonLabels.resource "Resource" -}}
{{- $sev := or .CommonLabels.severity "Severity" -}}
{{- $unit := or .CommonLabels.unit "Unit" -}}
{{- $name := or .CommonLabels.alertname "" -}}
{{- $isWarn := or (eq $sev "WARNING") (eq $sev "warning") -}}

{{- $isErrorName := match " - Error$" $name -}}
{{- $val := "" -}}
{{- if .CommonAnnotations -}}
  {{- $tmp := index .CommonAnnotations "value" -}}{{- if $tmp }}{{- $val = $tmp -}}{{- end -}}
{{- end -}}
{{- $isNA := eq $val "N/A" -}}
{{- if and (not $isNA) (gt (len .Alerts) 0) -}}
  {{- range .Alerts -}}
    {{- if eq (index .Annotations "value") "N/A" -}}{{- $isNA = true -}}{{- end -}}
  {{- end -}}
{{- end -}}
{{- $isError := or $isErrorName $isNA -}}

{{- $level := "unknown" -}}
{{- if .CommonAnnotations -}}
  {{- $lvl := index .CommonAnnotations "level" -}}{{- if $lvl }}{{- $level = $lvl -}}{{- end -}}
{{- else if gt (len .Alerts) 0 -}}
  {{- $lvl := index (index .Alerts 0).Annotations "level" -}}{{- if $lvl }}{{- $level = $lvl -}}{{- end -}}
{{- end -}}

{{- if $isError }}
  {{- if eq $status "firing" }}
❌ **[ERROR] {{ $res }} Query failed or returned N/A** ❌
  {{- else if eq $status "resolved" }}
✅ **[RESOLVED] {{ $res }} Query Working Normally** ✅
  {{- end }}
{{- else if eq $status "firing" -}}
  {{- if $isWarn -}}⚠️ **[{{ $sev }}] High {{ $res }} Usage** ⚠️
  {{- else -}}🚨 **[{{ $sev }}] High {{ $res }} Usage** 🚨{{- end -}}
{{- else if eq $status "resolved" -}}
  {{- if and $isWarn (eq $level "critical") -}}
🔺 **[Escalated to CRITICAL] High {{ $res }} Usage** 🔺
  {{- else -}}
✅ **[{{ $sev }} RESOLVED] High {{ $res }} Usage** ✅
  {{- end -}}
{{- end }}

{{- if $isError }}
{{"\n"}}**Project:** Project Name {{"\n"}} 
  {{- if eq $status "firing" }}
The query for **{{ $res }}** failed or returned invalid data.  
Please check the alert rule’s query or data source connectivity. {{"\n"}}
  {{- else if eq $status "resolved" }}
The query for **{{ $res }}** previously failed or returned invalid data.  
Now the query is working normally. {{"\n"}}
No action required. Monitoring has returned to normal. {{"\n"}}
  {{- end }}

{{- else }}

  {{- if eq $status "firing" }}
{{"\n"}}**Detail : {{ $res }} used more than {{ if $isWarn }}80%{{ else }}90%{{ end }}**
  {{- else if and $isWarn (eq $status "resolved") (eq $level "critical") }}
{{"\n"}}**Detail : Escalated to CRITICAL because usage is more than 90%**
  {{- end }}

**Project:** Project Name {{"\n"}}

{{- range .Alerts }}
• **Service Name:** {{ or .Labels.nodename "-" }}
  **Service URL:** {{ reReplaceAll ":\\d+" "" (or .Labels.instance "-") }}
  {{- with (index .Labels "unit") }}{{- $unit = . -}}{{- end }}
  **Usage:** {{ index .Annotations "value" }}% ( {{ index .Annotations "used" }} of {{ index .Annotations "total" }} {{ $unit }} ){{"\n"}}
{{- end }}


  {{- if eq $status "resolved" -}}
    {{- if or (eq $sev "CRITICAL") (and $isWarn (ne $level "critical")) }}
The alert(s) on **{{ $res }}** with severity **[{{ $sev }}]** has been resolved. {{"\n"}}
    {{- end }}
  {{- end }}

{{- end }}

{{- $latest := "" -}}
{{- if gt (len .Alerts) 0 -}}
  {{- if or $isError (eq $status "resolved") -}}
    {{- $latest = (index .Alerts 0).EndsAt -}}
    {{- range .Alerts -}}{{- if and .EndsAt (gt .EndsAt.Unix $latest.Unix) -}}{{- $latest = .EndsAt -}}{{- end -}}{{- end -}}
  {{- else -}}
    {{- $latest = (index .Alerts 0).StartsAt -}}
    {{- range .Alerts -}}{{- if and .StartsAt (gt .StartsAt.Unix $latest.Unix) -}}{{- $latest = .StartsAt -}}{{- end -}}{{- end -}}
  {{- end -}}
{{- end -}}
{{- if $latest -}}
{{"\n"}}**Time:** {{ $latest.Format "02/01/2006 15:04" }}
{{- end -}}
{{ end }}
```

> **หมายเหตุ:** Template นี้จะโชว์ `Annotations.value` ถ้ามี (เช่น `Usage: 50.00% ( 8.00 GB of 16 GB )`) หากไม่มีจะ fallback เป็น `%` จาก `Annotations.value` ตามเดิม

---

## 3. การสร้าง Contact Point สำหรับ Discord

1. ไปที่ **Alerting > Contact points**
2. สร้าง **New contact point** ชื่อ `Discord Alerts`
3. เลือก Integration **Discord** และใส่ Webhook URL ของช่องทาง
4. Title → `discord-alert-format.title`, Message → `discord-alert-format.message`, ใส่ Avatar URL ตามต้องการ

---

## 4. การตั้งค่า Alert Rule (% Usage)

### 4.1 CPU (Core)

* **A (Usage %)**

```promql
100 - (avg by(instance)(rate(node_cpu_seconds_total{job="node",mode="idle"}[5m])) * 100)
* on(instance) group_left(nodename) node_uname_info
```

* **B (Used Core)**

```promql
(count(node_cpu_seconds_total{mode="system"}) by (instance))
* (1 - avg by(instance)(rate(node_cpu_seconds_total{mode="idle"}[5m])))
```

* **C (Total Core)**

```promql
count(node_cpu_seconds_total{mode="system"}) by (instance)
```

### 4.2 Memory (GB)

* **A (Usage %)**

```promql
(
  1 - (
    avg_over_time(node_memory_MemAvailable_bytes{job="node"}[3m])
    / avg_over_time(node_memory_MemTotal_bytes{job="node"}[3m])
  )
) * 100
* on(instance) group_left(nodename)
node_uname_info
```

* **B (Used GB)**

```promql
(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes) / 1024 / 1024 / 1024
```

* **C (Total GB)**

```promql
node_memory_MemTotal_bytes / 1024 / 1024 / 1024
```

### 4.3 Disk (GB) — ระบุ mountpoint ที่ต้องการ (เช่น `/`)

* **A (Usage %)**

```promql
(
  node_filesystem_size_bytes{job="node", fstype=~"ext.?|xfs", mountpoint="/"}
- node_filesystem_avail_bytes{job="node", fstype=~"ext.?|xfs", mountpoint="/"}
)
/ node_filesystem_size_bytes{job="node", fstype=~"ext.?|xfs", mountpoint="/"} * 100
* on(instance) group_left(nodename) node_uname_info
```

* **B (Used GB)**

```promql
sum by (instance)(
  (node_filesystem_size_bytes{job="node", fstype=~"ext.?|xfs", mountpoint="/"}
 - node_filesystem_avail_bytes{job="node", fstype=~"ext.?|xfs", mountpoint="/"})
/ 1024 / 1024 / 1024
)
* on(instance) group_left(nodename) node_uname_info
```

* **C (Total GB)**

```promql
sum by (instance)(
  node_filesystem_size_bytes{job="node", fstype=~"ext.?|xfs", mountpoint="/"} 
/ 1024 / 1024 / 1024
)
* on(instance) group_left(nodename) node_uname_info
```

> **Grafana ≤ 9.3.2**: หากอ้าง `$values.A` ไม่ได้ ให้ทำ Reduce(A) → B แล้วใช้ B/C ใน Template หรือปรับชื่อตัวแปรตามที่ตั้งไว้ใน Rule

---

## 5. Label ที่ใช้ใน Alert

```yaml
resource: CPU | Memory | Disk
severity: WARNING | CRITICAL
unit: GB | Core   # ใช้เลือกหน่วยใน template
```

---

## 6. ค่า Threshold (แนะนำ)

| Resource | WARNING (%) | Pending | CRITICAL (%) | Pending | Keep firing for |
| -------- | ----------: | ------: | -----------: | ------: | ------: |
| CPU      |      80–<90 |     10m |          ≥90 |      5m | 0s |
| Memory   |      80–<90 |     10m |          ≥90 |      5m | 0s |
| Disk     |      80–<90 |     30m |          ≥90 |     15m | 0s |

---

## 7. Configure notifications

1. ไปที่ Alert Rule → Notifications

2. ที่หัวข้อ Recipient

    * Alertmanager: เลือกเป็น grafana

    * Contact point: เลือก Discord Alerts (ที่สร้างไว้ในข้อ 3)

3. หากยังไม่มี Contact point สามารถกด View or create contact points เพื่อสร้างใหม่ได้

> การตั้งค่านี้ทำให้เมื่อ Alert Rule ทำงาน ระบบจะส่งข้อความไปยัง Discord ผ่าน Contact point ที่เลือก

## 8. Configure notification message

ไปที่ Alert Rule → Annotations & labels แล้วเพิ่ม Custom annotations ตามด้านล่างนี้:

level

```gotemplate
{{ if ge $values.A.Value 90.0 }}critical{{ else }}unknow{{ end }}
```

total

```gotemplate
{{ with $values.C }}{{ printf "%.2f" .Value }}{{ else }}N/A{{ end }}
```

used

```gotemplate
{{ with $values.B }}{{ printf "%.2f" .Value }}{{ else }}N/A{{ end }}
```

value

```gotemplate
{{ with $values.A }}{{ printf "%.2f" .Value }}{{ else }}N/A{{ end }}
```

ทั้งหมดนี้เพื่อให้ Template ใช้ Annotations.value, Annotations.used, Annotations.total, และ Annotations.level ได้ตรงตามที่ต้องการ

### การตั้งค่าหน่วยด้วย Label `unit`

* สำหรับ **Memory/Disk** ให้ตั้งค่า **label** ของกฎเป็น `unit=GB`
* สำหรับ **CPU** ให้ตั้งค่า **label** ของกฎเป็น `unit=Core`

> ไปที่ Alert rule → **Labels** (หรือ Annotations & labels) → เพิ่ม `unit` เป็นค่าตาม resource นั้น ๆ เพื่อให้ template เลือกหน่วยถูกต้องโดยไม่ต้องแก้โค้ด

---

## 9. การตั้ง Notification Policy

1. **Alerting > Notification policies**
2. Default contact point → `Discord Alerts`
3. Group by: `grafana_folder`, `alertname`, `resource`, `severity`
4. ระยะเวลา: **Group wait** 30s, **Group interval** 5m, **Repeat interval** 1d

---

## 10. สรุป

* ใช้ Query แยกเป็น **A,B,C** แล้วรวมเป็นข้อความเดียวด้วย **Annotation Template กลาง** (ด้านบน)
* ตั้ง label `unit` ต่อกฎ เพื่อให้ข้อความเลือกหน่วยได้อัตโนมัติ (Memory/Disk = GB, CPU = Core)
* Template Discord รองรับ `value` และ fallback เป็นเลข `%` อัตโนมัติถ้าไม่ได้ตั้งค่า
