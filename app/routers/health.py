from fastapi import APIRouter, HTTPException, Response
from pymongo.errors import ConnectionFailure
from app.database import client, mongo_cfg

router = APIRouter()

# Startup & Liveness (เช็คแค่ App เปิดติด)
@router.get("/health/live")
def liveness_check():
    return {"status": "alive", "message_code": 200}

# Readiness (เช็ค Database)
@router.get("/health/ready")
def readiness_check():
    try:
        # Ping check (Timeout สั้นๆ พอ เช่น 1-2 วิ)
        client.admin.command('ping')
        return {
            "status": "ready",
            "message_code": 200,
            "database": "connected"
        }
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"Database not ready: {str(e)}")

# Startup (Optional - สำหรับ App ใหญ่มักใช้คู่กับ Liveness)
@router.get("/health/startup")
def startup_check():
    return {"status": "started", "message_code": 200}

@router.get("/health")
def health_check_legacy():
    return readiness_check()