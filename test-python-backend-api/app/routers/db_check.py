from fastapi import APIRouter, HTTPException
from datetime import datetime
import uuid
from app.database import db
from app.schemas import DBCheckPayload
from app.logger import get_logger

router = APIRouter()
logger = get_logger()

@router.post("/db-write-read")
def test_db_write_read(payload: DBCheckPayload):
    logger.info(f"Starting DB write-read check triggered by: {payload.triggered_by}")
    try:
        collection = db["smoke_logs"]
        
        test_id = str(uuid.uuid4())
        mock_data = {
            "test_id": test_id,
            "triggered_by": payload.triggered_by,
            "message": payload.message,
            "timestamp": datetime.now().isoformat(),
            "status": "success",
            "check_type": "write-read-verification"
        }
        
        insert_result = collection.insert_one(mock_data)
        logger.info(f"Insert successful. ID: {insert_result.inserted_id}")
        
        retrieved_doc = collection.find_one({"_id": insert_result.inserted_id})
        
        if retrieved_doc:
            logger.info(f"Read successful for ID: {insert_result.inserted_id}")
            retrieved_doc["_id"] = str(retrieved_doc["_id"])
            return {
                "test_result": "PASS",
                "message_code": 200,
                "operation": "insert_and_find",
                "data": retrieved_doc
            }
        else:
            logger.error(f"Write successful but Read failed for ID: {insert_result.inserted_id}")
            raise HTTPException(status_code=500, detail="Write successful but Read failed")
            
    except Exception as e:
        logger.error(f"DB Check failed: {str(e)}")
        raise HTTPException(status_code=500, detail=f"DB Check failed: {str(e)}")