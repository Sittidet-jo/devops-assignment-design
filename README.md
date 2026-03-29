# Terraform - K3s Multi-Node Cluster บน AWS EC2

Terraform configuration สำหรับสร้าง K3s Cluster แบบ Multi-Node บน AWS
ประกอบด้วย 1 Server (Master) และ 2 Agent (Worker)

## สถาปัตยกรรม

```text
AWS (ap-southeast-1)
+------------------------------------------------+
|  Security Group (k3s-sg)                       |
|  ขาเข้า: SSH(22), K3s API(6443),               |
|          HTTP(80), HTTPS(443)                  |
|  ขาออก: ทั้งหมด                                 |
|                                                |
|  +------------------------------------------+  |
|  |  k3s-server (Master)                     |  |
|  |  EC2 t3.micro / Amazon Linux 2023        |  |
|  |  รัน K3s Server + Control Plane           |  |
|  +------------------------------------------+  |
|          |                    |                 |
|  +----------------+  +----------------+        |
|  | k3s-agent-1    |  | k3s-agent-2    |        |
|  | (Worker)       |  | (Worker)       |        |
|  +----------------+  +----------------+        |
+------------------------------------------------+
```

## โครงสร้างไฟล์

| ไฟล์ | คำอธิบาย |
|---|---|
| `provider.tf` | กำหนดค่า Terraform และ AWS provider |
| `variables.tf` | ประกาศตัวแปร (region, instance type, key name, จำนวน agent) |
| `main.tf` | สร้าง EC2 สำหรับ Server 1 ตัว และ Agent ตาม agent_count |
| `security.tf` | Security Group เปิด port SSH, K3s API, HTTP, HTTPS |
| `output.tf` | แสดง Public IP ของ Server และ Agent ทุกตัว |
| `terraform.tfvars` | ไฟล์กำหนดค่าตัวแปร |

## ตัวแปร

| ตัวแปร | คำอธิบาย | ค่าเริ่มต้น |
|---|---|---|
| `aws_region` | AWS region | `ap-southeast-1` |
| `instance_type` | ประเภท EC2 instance | `t3.micro` |
| `key_name` | ชื่อ EC2 key pair | *(จำเป็นต้องระบุ)* |
| `instance_name` | prefix ชื่อ EC2 instance | `devops-assignment-k3s` |
| `agent_count` | จำนวน Agent (Worker) node | `2` |

## สิ่งที่ต้องเตรียมก่อนใช้งาน

- Terraform >= 1.5.0
- ติดตั้งและตั้งค่า AWS CLI พร้อม credentials
  ```bash
  aws configure
  ```
- มี EC2 Key Pair ชื่อ `devops-assignment-key` ใน region `ap-southeast-1`
  - สร้างผ่าน AWS Console: **EC2 > Key Pairs > Create key pair**
  - หรือสร้างผ่าน CLI:
    ```bash
    aws ec2 create-key-pair --key-name devops-assignment-key --query 'KeyMaterial' --output text > devops-assignment-key.pem
    chmod 400 devops-assignment-key.pem
    ```

## วิธีใช้งาน

### ขั้นตอนที่ 1: สร้าง Infrastructure ด้วย Terraform

```bash
# เตรียมพร้อม (ดาวน์โหลด provider)
terraform init

# ตรวจสอบการเปลี่ยนแปลงก่อน apply
terraform plan

# สร้าง resource จริง (พิมพ์ yes เพื่อยืนยัน)
terraform apply
```

หลัง apply สำเร็จจะได้ output แบบนี้:

```
server_public_ip = "13.x.x.x"
agent_public_ips = [
  "54.x.x.x",
  "18.x.x.x",
]
```

### ขั้นตอนที่ 2: ติดตั้ง K3s บน Server (Master)

SSH เข้า Server:

```bash
ssh -i devops-assignment-key.pem ec2-user@<SERVER_PUBLIC_IP>
```

ติดตั้ง K3s Server:

```bash
curl -sfL https://get.k3s.io | sh -
```

รอสักครู่แล้วตรวจสอบว่า Server พร้อมใช้งาน:

```bash
sudo kubectl get nodes
```

ควรเห็น node สถานะ `Ready`

### ขั้นตอนที่ 3: ดึง Token จาก Server

ยังอยู่บน Server ให้รันคำสั่ง:

```bash
sudo cat /var/lib/rancher/k3s/server/node-token
```

คัดลอก token ที่ได้ไว้ใช้ในขั้นตอนถัดไป

### ขั้นตอนที่ 4: ติดตั้ง K3s บน Agent (Worker) แต่ละตัว

SSH เข้า Agent แต่ละตัว:

```bash
ssh -i devops-assignment-key.pem ec2-user@<AGENT_PUBLIC_IP>
```

ติดตั้ง K3s Agent โดยชี้ไปที่ Server:

```bash
curl -sfL https://get.k3s.io | K3S_URL=https://<SERVER_PUBLIC_IP>:6443 K3S_TOKEN=<TOKEN> sh -
```

แทนที่ `<SERVER_PUBLIC_IP>` และ `<TOKEN>` ด้วยค่าจริง

**ทำซ้ำขั้นตอนนี้กับ Agent ทุกตัว**

### ขั้นตอนที่ 5: ตรวจสอบ Cluster

กลับไปที่ Server แล้วรัน:

```bash
sudo kubectl get nodes
```

ผลลัพธ์ควรแสดง node ทั้ง 3 ตัว:

```
NAME          STATUS   ROLES                  AGE   VERSION
ip-x-x-x-x   Ready    control-plane,master   5m    v1.x.x+k3s1
ip-x-x-x-x   Ready    <none>                 2m    v1.x.x+k3s1
ip-x-x-x-x   Ready    <none>                 1m    v1.x.x+k3s1
```

## ทดสอบ Deploy แอปพลิเคชัน

สร้างไฟล์ทดสอบบน Server:

```bash
sudo kubectl create deployment nginx --image=nginx
sudo kubectl expose deployment nginx --port=80 --type=NodePort
sudo kubectl get svc nginx
```

เข้าเว็บผ่าน `http://<PUBLIC_IP>:<NODE_PORT>` เพื่อตรวจสอบว่าทำงานได้

## ผลลัพธ์ (Output)

| ชื่อ | คำอธิบาย |
|---|---|
| `server_public_ip` | Public IP ของ K3s Server (Master) |
| `agent_public_ips` | Public IP ของ K3s Agent (Worker) ทุกตัว |

## ปรับจำนวน Agent

แก้ไขค่า `agent_count` ใน `terraform.tfvars`:

```hcl
agent_count = 3
```

แล้วรัน:

```bash
terraform apply
```

## ลบ Resource ทั้งหมด

```bash
terraform destroy
```


sudo cat /var/lib/rancher/k3s/server/node-token