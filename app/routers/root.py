from fastapi import APIRouter
from app.config import settings
from app.logger import get_logger

router = APIRouter()
logger = get_logger()

@router.get("/")
def read_root():
    server_cfg = settings['server']
    logger.info(f"Root endpoint accessed. Server: {server_cfg['name']}")
    return {
        "message": f"Welcome to {server_cfg['name']} API (Production Structure)",
        "message_code": 200,
        "port": server_cfg['port']
    }