from pydantic import BaseModel
from typing import Optional

class DBCheckPayload(BaseModel):
    triggered_by: str = "jenkins-pipeline"
    message: str = "Testing Write/Read capability"

class SearchBody(BaseModel):
    keyword: Optional[str] = None
    status: Optional[str] = "success"
    days_back: int = 7
