from typing import Annotated, Literal, Union

from pydantic import BaseModel, Field

AppId = Literal["photoshop", "premiere", "lightroom", "capcut", "browser"]


class TaskRequest(BaseModel):
    instruction: str = Field(min_length=3, max_length=4000)


class LaunchAppAction(BaseModel):
    action: Literal["launch_app"]
    app_id: AppId
    description: str = Field(default="Uygulamayı başlat", max_length=300)


class WhatsAppSendAction(BaseModel):
    action: Literal["whatsapp_send"]
    to: str = Field(min_length=8, max_length=25)
    body: str = Field(min_length=1, max_length=4096)
    description: str = Field(default="WhatsApp mesajı gönder", max_length=300)


class YouTubeUploadAction(BaseModel):
    action: Literal["youtube_upload"]
    file_path: str = Field(min_length=3, max_length=1000)
    title: str = Field(min_length=1, max_length=100)
    video_description: str = Field(default="", max_length=5000)
    privacy: Literal["private", "unlisted", "public"] = "private"
    description: str = Field(default="YouTube'a video yükle", max_length=300)


class PhotoshopAction(BaseModel):
    action: Literal["photoshop"]
    command: Literal["info", "open", "auto_tone", "resize_long_edge", "export_jpeg", "export_png"]
    path: str = Field(default="", max_length=1000)
    value: int = Field(default=0, ge=0, le=30000)
    description: str = Field(default="Photoshop komutu", max_length=300)


ActionRequest = Annotated[
    Union[LaunchAppAction, WhatsAppSendAction, YouTubeUploadAction, PhotoshopAction],
    Field(discriminator="action"),
]


class ApprovalDecision(BaseModel):
    approved: bool
