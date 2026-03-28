# test-jenkins-app - เอกสารอธิบายการทำงาน

## ภาพรวม

แอปพลิเคชัน Frontend สร้างด้วย **React** และ serve ผ่าน **Express.js** บน port 8080
ใช้สำหรับทดสอบ Jenkins CI/CD pipeline ครบวงจร (build, test, scan, deploy, canary rollout)

---

## โครงสร้างไฟล์

```
test-jenkins-app/
  src/
    App.js                # React component หลัก
    App.css               # stylesheet
    App.test.js           # Unit test (Jest)
    index.js              # React entry point
    setupTests.js         # ตั้งค่า Jest
    reportWebVitals.js    # Web Vitals metrics
  public/
    index.html            # HTML template
  e2e/
    app.spec.js           # E2E test (Playwright)
  server.js               # Express server สำหรับ production
  Dockerfile              # Multi-stage Docker build
  Jenkinsfile             # CI/CD pipeline config
  package.json            # dependencies และ scripts
  playwright.config.js    # ตั้งค่า Playwright
```

---

## การทำงานของแอปพลิเคชัน

### ขั้นตอนการทำงาน

```
1. npm run build
   React (react-scripts) คอมไพล์ JSX → static files (HTML, CSS, JS)
   ผลลัพธ์อยู่ในโฟลเดอร์ build/

2. node server.js
   Express.js รับ request จาก client

3. การจัดการ request
   GET /ready  → ตอบ "ok" (Kubernetes readiness probe)
   GET /live   → ตอบ "ok" (Kubernetes liveness probe)
   GET /*      → serve static files จาก build/
                  ถ้าไม่เจอไฟล์ → ส่ง index.html (SPA fallback)
```

### server.js - Express Server

| Endpoint    | หน้าที่                                        |
|-------------|-----------------------------------------------|
| `GET /ready`| Health check สำหรับ Kubernetes readiness probe |
| `GET /live` | Health check สำหรับ Kubernetes liveness probe  |
| `GET /*`    | Serve static files + SPA fallback (index.html) |

Server listen บน port **8080** โดยใช้ environment variables:
- `HOSTNAME`: `0.0.0.0` (bind ทุก interface)
- `PORT`: `8080`

### src/App.js - React Component

หน้าเว็บแสดง logo และ link "Test Integration"
มี bug และ code smell ที่จงใจใส่ไว้สำหรับทดสอบ SonarQube:
- Self-assignment (`value = value`) → SonarQube จับเป็น Major Bug
- เงื่อนไขที่เป็นจริงเสมอ (`if (true === true)`) → Code Smell

---

## Dockerfile - Multi-stage Build

```
Stage 1: build
  Base image : node:20-alpine
  ขั้นตอน    : npm ci → npm run build
  ผลลัพธ์    : static files ในโฟลเดอร์ /app/build

Stage 2: run (production)
  Base image : node:20-alpine
  ขั้นตอน    : npm ci --omit=dev (ติดตั้งเฉพาะ express)
  คัดลอก     : build/ จาก stage 1 + server.js
  Expose     : port 8080
  CMD        : node server.js
```

ข้อดีของ multi-stage:
- Image ขนาดเล็ก (ไม่มี devDependencies และ source code)
- แยก build tools ออกจาก production image
- ใช้ Alpine Linux เป็น base (ขนาดเล็ก + ปลอดภัย)

---

## Scripts

| คำสั่ง               | คำอธิบาย                                          |
|----------------------|--------------------------------------------------|
| `npm run build`      | Build React เป็น static files ใน `build/`         |
| `npm run start`      | รัน React dev server (development)               |
| `npm run start:server`| รัน Express server (production)                  |
| `npm run test`       | รัน Jest unit tests + สร้าง JUnit XML report     |
| `npm run lint`       | รัน ESLint ตรวจสอบ code quality                   |

---

## Testing

### Unit Test (Jest + React Testing Library)
- ไฟล์: `src/App.test.js`
- ผลลัพธ์: JUnit XML report ที่ `test-results/junit.xml`
- รันด้วย: `npm run test`

### E2E Test (Playwright)
- ไฟล์: `e2e/app.spec.js`
- ทดสอบ:
  - หน้าเว็บมี title ที่ถูกต้อง
  - มีข้อความ "Learn Jenkins on Udemy" ปรากฏ
  - แสดง version ที่ถูกต้อง (จาก `REACT_APP_VERSION`)
- รันด้วย: `npx playwright test`

---

## Jenkinsfile - การตั้งค่า Pipeline

### ข้อมูลพื้นฐาน

| การตั้งค่า       | ค่า                                  |
|-----------------|--------------------------------------|
| ชื่อโปรเจค       | `test-jenkins-app`                   |
| ภาษา            | JavaScript                           |
| ประเภท          | Frontend                             |
| Environment     | dev                                  |
| Shared Library  | `my-devops-library@feat/newman`      |
| GitLab Source   | `devops/test-jenkins-app`            |

### Deployment

| การตั้งค่า          | ค่า                    |
|--------------------|------------------------|
| Namespace          | default                |
| Container Port     | 8080                   |
| NodePort           | 30001                  |
| Image Pull Secret  | test-pull-secret       |
| CPU Request/Limit  | 250m / 1000m           |
| Memory Req/Limit   | 512Mi / 1Gi            |

### Health Probes

| Probe          | Path | Port | รายละเอียด                              |
|----------------|------|------|----------------------------------------|
| Startup Probe  | `/`  | 8080 | failureThreshold: 30, period: 10s      |
| Readiness Probe| `/`  | 8080 | initialDelay: 5s, period: 5s           |
| Liveness Probe | `/`  | 8080 | initialDelay: 30s, period: 10s         |

### Auto Scaling (KEDA)

| การตั้งค่า      | ค่า        |
|----------------|-----------|
| Min Replicas   | 2         |
| Max Replicas   | 6         |
| CPU Trigger    | > 75%     |
| Memory Trigger | > 75%     |
| Polling        | ทุก 30 วินาที |
| Cooldown       | 300 วินาที   |

### Canary Rollout

```
25% traffic → หยุดรอ 15 วินาที → Prometheus Analysis
    ↓
50% traffic → Prometheus Analysis → หยุดรอ (manual approve)
    ↓
100% traffic → rollout เสร็จสมบูรณ์
```

### Smoke Test

ทดสอบ `GET /` หลัง deploy:
- Timeout: 10 วินาที
- จำนวนครั้ง: 3
- ช่วงห่าง: 5 วินาที
- ยอมรับ failure: 1 ครั้ง

---

## Dependencies หลัก

### Production
| Package    | เวอร์ชัน  | หน้าที่                   |
|------------|----------|--------------------------|
| react      | ^18.2.0  | UI framework             |
| react-dom  | ^18.2.0  | React DOM rendering      |
| express    | ^4.18.2  | Production HTTP server   |

### Development
| Package           | หน้าที่                               |
|-------------------|--------------------------------------|
| react-scripts     | Build toolchain (Webpack, Babel)     |
| jest              | Unit testing framework               |
| @playwright/test  | E2E testing framework                |
| eslint            | Code linting                         |
| semantic-release  | Auto versioning (conventional commits)|
