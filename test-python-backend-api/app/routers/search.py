from typing import Optional
from fastapi import APIRouter, HTTPException, Path, Header, Depends
from app.database import db
from app.schemas import SearchBody
from app.config import settings
from app.logger import get_logger

router = APIRouter()
logger = get_logger()

def verify_bearer_token(authorization: str = Header(..., description="Format: Bearer <token>")):
    try:
        scheme, token = authorization.split()
        if scheme.lower() != "bearer":
            logger.warning("Invalid authentication scheme attempted")
            raise HTTPException(status_code=401, detail="Invalid authentication scheme")
        
        expected_key = settings['security']['api_key']
        if token != expected_key:
            logger.warning("Invalid API token provided")
            raise HTTPException(status_code=401, detail="Invalid Token")
        return token
    except ValueError:
        logger.warning("Invalid Authorization header format")
        raise HTTPException(status_code=401, detail="Invalid Authorization header format")

def execute_search(page: int, limit: int, payload: SearchBody):
    try:
        logger.info(f"Executing search: page={page}, limit={limit}, keyword='{payload.keyword}'")
        collection = db["smoke_logs"]
        
        query_filter = {}
        if payload.keyword:
             query_filter["message"] = {"$regex": payload.keyword, "$options": "i"}
        if payload.status:
            query_filter["status"] = payload.status

        skip_count = (page - 1) * limit

        cursor = collection.find(query_filter).sort("timestamp", -1).skip(skip_count).limit(limit)

        results = []
        for doc in cursor:
            doc["_id"] = str(doc["_id"])
            results.append(doc)

        logger.info(f"Search completed. Found {len(results)} results.")
        return {
            "status": "success",
            "message_code": 200,
            "page": page,
            # "limit": limit,
            "total": len(results),
            "data": results
        }
    except Exception as e:
        logger.error(f"Search execution failed: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Search failed: {str(e)}")


@router.post("/search-post/{page}/{limit}")
def search_log_post(
    payload: Optional[SearchBody] = None,
    page: int = Path(..., ge=1),
    limit: int = Path(..., ge=1, le=100)
):
    
    if payload is None:
        payload = SearchBody()
        
    return execute_search(page, limit, payload)

@router.post("/search-secure/{page}/{limit}", dependencies=[Depends(verify_bearer_token)])
def search_log_secure(
    payload: SearchBody,
    page: int = Path(..., ge=1),
    limit: int = Path(..., ge=1, le=100)
):
    return execute_search(page, limit, payload)