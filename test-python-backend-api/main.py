import uvicorn
import logging
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from app.config import settings
from app.routers import root, health, db_check, search
from app.logger import get_logger

logger = get_logger()

class EndpointFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        return record.getMessage().find("/health") == -1

logging.getLogger("uvicorn.access").addFilter(EndpointFilter())

app = FastAPI(
    title=settings['server']['name'],
    description="List API เป็นระบบ Backend Sample App เพื่อใช้ทดสอบการ Smoke Test Curl API (DevOps Workshop)",
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json"
)

@app.on_event("startup")
async def startup_event():
    logger.info("Application starting up...")
    logger.info(f"Using server: {settings['server']['name']} at {settings['server']['public']}:{settings['server']['port']}")

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    error_messages = []
    
    for error in exc.errors():
        location = " -> ".join([str(x) for x in error['loc']])
        
        message = error['msg']
        
        if message == "Field required":
            message = "Missing Required Field"
        
        error_messages.append(f"[{location}]: {message}")

    final_message = "; ".join(error_messages)
    logger.error(f"Validation Error: {final_message}")

    return JSONResponse(
        status_code=422,
        content={
            "status": "error",
            "message_code": 422,
            "detail": f"Validation Failed: {final_message}",
        },
    )

@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException):
    logger.warning(f"HTTP Exception: {exc.status_code} - {exc.detail}")
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "status": "error",
            "message_code": exc.status_code,
            "detail": exc.detail
        },
    )

api_v1_prefix = "/api/v1"

app.include_router(root.router)
app.include_router(health.router)
app.include_router(db_check.router, prefix=api_v1_prefix)
app.include_router(search.router, prefix=api_v1_prefix)

if __name__ == "__main__":
    uvicorn.run(
        "main:app", 
        host=settings['server']['public'], 
        port=settings['server']['port'], 
        reload=True
    )