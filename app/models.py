from pydantic import BaseModel, Field
from typing import Literal

class TaskRequest(BaseModel):
    instruction: str = Field(min_length=3, max_length=4000)

class ActionRequest(BaseModel):
    action: Literal["launch_app"]
    app_id: Literal["photoshop", "premiere", "lightroom", "capcut", "browser"]
    description: str = Field(default="Uygulamayı başlat", max_length=300)

class ApprovalDecision(BaseModel):
    approved: bool
