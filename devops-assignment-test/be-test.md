# test-python-backend-api - เอกสารอธิบายการทำงาน

## ภาพรวม

แอปพลิเคชัน Backend สร้างด้วย **FastAPI** (Python) เชื่อมต่อ **MongoDB** รันบน port 10104
ใช้สำหรับทดสอบ Jenkins CI/CD pipeline ครบวงจร รวมถึง CronJob smoke test

---

## โครงสร้างไฟล์

```
test-python-backend-api/
  app/
    __init__.py             # Package init
    config.py               # โหลดค่าจาก config.yaml
    database.py             # เชื่อมต่อ MongoDB
    logger.py               # ตั้งค่า logging (console + file)
    schemas.py              # Pydantic models สำหรับ request/response
    routers/
      root.py               # GET / — หน้าแรก
      health.py             # Health check endpoints
      db_check.py           # ทดสอบ write/read MongoDB
      search.py             # ค้นหา logs พร้อม authentication
  scripts/
    cronjob_smoke_test.py   # CronJob สำหรับ smoke test อัตโนมัติ
  tests/
    smoke-test.json         # Postman collection สำหรับ smoke test
  config.yaml               # ค่าตั้งค่าแอป (server, MongoDB, security)
  main.py                   # Entry point ของแอป
  Dockerfile                # Docker image build
  Jenkinsfile               # CI/CD pipeline config
  requirements.txt          # Python dependencies
```

---

## การทำงานของแอปพลิเคชัน

### ขั้นตอนการทำงาน

```
1. python main.py
   โหลดค่าจาก config.yaml → สร้าง FastAPI app → เชื่อมต่อ MongoDB

2. Uvicorn server เริ่มทำงาน
   listen ที่ 0.0.0.0:10104

3. รับ request จาก client
   /              → ข้อมูลแอป
   /health/*      → health check
   /api/v1/*      → API endpoints
   /docs          → Swagger UI
```

---

## API Endpoints

### Health Check

| Endpoint            | Method | คำอธิบาย                                         |
|---------------------|--------|--------------------------------------------------|
| `/health`           | GET    | ตรวจสอบระบบ + เชื่อมต่อ MongoDB (legacy)           |
| `/health/live`      | GET    | Liveness check — แอปเปิดทำงานอยู่                  |
| `/health/ready`     | GET    | Readiness check — เชื่อมต่อ MongoDB ได้             |
| `/health/startup`   | GET    | Startup check — แอปเริ่มต้นสำเร็จ                  |

### Root

| Endpoint | Method | คำอธิบาย                    |
|----------|--------|-----------------------------|
| `/`      | GET    | แสดงข้อมูลแอป (ชื่อ, port)   |

### API v1

| Endpoint                              | Method | คำอธิบาย                                    | Authentication |
|---------------------------------------|--------|---------------------------------------------|----------------|
| `/api/v1/db-write-read`              | POST   | ทดสอบเขียน/อ่าน MongoDB                      | ไม่ต้อง         |
| `/api/v1/search-post/{page}/{limit}` | POST   | ค้นหา logs จาก MongoDB                       | ไม่ต้อง         |
| `/api/v1/search-secure/{page}/{limit}`| POST  | ค้นหา logs (ต้องมี Bearer token)              | ต้อง            |

### เอกสาร API อัตโนมัติ

| Endpoint        | คำอธิบาย                    |
|-----------------|----------------------------|
| `/docs`         | Swagger UI (interactive)   |
| `/redoc`        | ReDoc (read-only)          |
| `/openapi.json` | OpenAPI spec (JSON)        |

---

## ส่วนประกอบหลัก

### config.yaml — ค่าตั้งค่าแอป

```yaml
mongodb:
  server: "172.30.11.11:27017,..."   # MongoDB replica set
  username: "admin"
  password: "***"
  authsource: "admin"

server:
  name: "appblueprint"
  public: "0.0.0.0"
  port: 10104
  secret_key: "***"

security:
  api_key: "***"                     # Bearer token สำหรับ secure endpoints
```

ค่าตั้งค่าถูก mount เข้า pod ผ่าน **Kubernetes Secret** (`test-config-api`) ที่ path `/app/config.yaml`

### database.py — การเชื่อมต่อ MongoDB

- ใช้ **PyMongo** เชื่อมต่อ MongoDB replica set
- Connection timeout: 2 วินาที
- Database: `workshop_test_db`
- Collection หลัก: `smoke_logs`

### logger.py — ระบบ Logging

- ส่ง log ไปทั้ง **console** (stdout) และ **ไฟล์** (`/app/logs/app.log`)
- ใช้ **RotatingFileHandler**: ไฟล์ log สูงสุด 10MB, เก็บ backup 5 ไฟล์
- กรอง `/health` endpoint ออกจาก access log (ลด noise)
- รูปแบบ: `timestamp - name - level - message`

### schemas.py — Pydantic Models

| Model            | ฟิลด์                                         | ใช้กับ                    |
|------------------|-----------------------------------------------|--------------------------|
| `DBCheckPayload` | `triggered_by`, `message`                     | `/api/v1/db-write-read`  |
| `SearchBody`     | `keyword`, `status`, `days_back`              | `/api/v1/search-*`       |

### search.py — การ Authentication

- ใช้ **Bearer Token** ใน header `Authorization`
- ตรวจสอบ token กับ `security.api_key` ใน config.yaml
- เฉพาะ endpoint `/api/v1/search-secure` เท่านั้นที่ต้องใช้ token

---

## CronJob Smoke Test

ไฟล์ `scripts/cronjob_smoke_test.py` รันเป็น **Kubernetes CronJob** ทุก 1 นาที

### ขั้นตอนการทำงาน

```
1. Health Check
   GET /health/ready → ตรวจสอบว่าระบบพร้อม
   ลองใหม่สูงสุด 3 ครั้ง (ห่าง 5 วินาที)

2. รัน Smoke Tests
   อ่าน test cases จาก tests/smoke-test.json (Postman collection format)
   รันทีละ test case: ส่ง request → ตรวจสอบ status code 2xx

3. ผลลัพธ์
   ทุก test ผ่าน → exit code 0
   มี test ล้มเหลว → exit code 1
```

### การตั้งค่าใน Jenkinsfile

```groovy
cronjobs: [
  [
    name: 'smoke-test-task',
    schedule: '*/1 * * * *',           // รันทุก 1 นาที
    command: ['python', 'scripts/cronjob_smoke_test.py'],
    env: [
      BASE_URL: 'http://test-python-backend-api-service-stable-prd:10104'
    ]
  ]
]
```

---

## Dockerfile

```
Base image : python:3.11-alpine
ขั้นตอน    : pip install requirements.txt → copy source code
Expose     : port 10104
CMD        : python main.py
Timezone   : Asia/Bangkok
```

> หมายเหตุ: ยังเป็น single-stage build (ไม่ใช่ multi-stage)

---

## Jenkinsfile — การตั้งค่า Pipeline

### ข้อมูลพื้นฐาน

| การตั้งค่า        | ค่า                                       |
|------------------|-------------------------------------------|
| ชื่อโปรเจค        | `test-be-api`                             |
| Project Zone     | `Test-API-Zone`                           |
| ภาษา             | Python                                    |
| ประเภท           | Backend                                   |
| Environment      | prd                                       |
| Pipeline Mode    | `build-deploy-all` (build + deploy ทั้งหมด)|
| Shared Library   | `jenkins-shared-library@test-deploy`      |
| GitLab Source    | `devops/test-python-backend-api`          |

### Deployment

| การตั้งค่า           | ค่า                                    |
|---------------------|----------------------------------------|
| Namespace           | default                                |
| Container Port      | 10104                                  |
| NodePort            | 30021                                  |
| Image Pull Secret   | test-pull-secret                       |
| CPU Request/Limit   | 350m / 750m                            |
| Memory Req/Limit    | 256Mi / 512Mi                          |
| Config Injection    | config.yaml ผ่าน Secret volume mount   |
| Auto Scaling        | VPA (mode: Off)                        |
| Auto Sync           | No (ต้อง manual approve)               |

### Health Probes

| Probe          | Path      | Port  | รายละเอียด                             |
|----------------|-----------|-------|---------------------------------------|
| Startup Probe  | `/health` | 10104 | failureThreshold: 20, period: 6s     |
| Readiness Probe| `/health` | 10104 | initialDelay: 5s, period: 10s        |
| Liveness Probe | `/health` | 10104 | initialDelay: 30s, period: 20s       |

### Canary Rollout

```
25% traffic → หยุดรอ 15 วินาที → Newman Analysis
    ↓
50% traffic → Newman + Prometheus Analysis → หยุดรอ 15 วินาที
    ↓
100% traffic → rollout เสร็จสมบูรณ์
```

### Smoke Test (Newman)

- ใช้ไฟล์: `tests/smoke-test.json` (Postman collection)
- Secret: `test-api-secret` (สำหรับ auth token)
- Timeout: 10 วินาที
- จำนวนครั้ง: 3
- ช่วงห่าง: 5 วินาที
- ยอมรับ failure: 1 ครั้ง

---

## Dependencies

| Package   | เวอร์ชัน  | หน้าที่                        |
|-----------|----------|-------------------------------|
| fastapi   | 0.109.0  | Web framework                 |
| uvicorn   | 0.27.0   | ASGI server                   |
| pymongo   | 4.6.1    | MongoDB driver                |
| pyyaml    | 6.0.1    | อ่านไฟล์ config.yaml           |
| requests  | 2.31.0   | HTTP client (สำหรับ smoke test)|
