"""YouTube Data API v3: video yükleme (varsayılan gizlilik: private).

İlk kullanımda tarayıcıda Google hesabı izni istenir; jeton data/youtube_token.json içinde saklanır.
"""
from __future__ import annotations

import os
from pathlib import Path

from ..config import ROOT, load_env

SCOPES = ["https://www.googleapis.com/auth/youtube.upload"]
TOKEN_PATH = ROOT / "data" / "youtube_token.json"
PRIVACY = ("private", "unlisted", "public")


class YouTubeError(RuntimeError):
    pass


def _credentials():
    try:
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
        from google_auth_oauthlib.flow import InstalledAppFlow
    except ImportError as exc:
        raise YouTubeError("YouTube paketleri eksik. setup_windows.bat'ı yeniden çalıştırın.") from exc
    load_env()
    creds = None
    if TOKEN_PATH.exists():
        creds = Credentials.from_authorized_user_file(str(TOKEN_PATH), SCOPES)
    if creds and creds.valid:
        return creds
    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
    else:
        secret = os.getenv("YOUTUBE_CLIENT_SECRET_FILE", "").strip().strip('"')
        if not secret or not Path(secret).is_file():
            raise YouTubeError(
                "YouTube için Google Cloud Console'dan 'Masaüstü uygulaması' OAuth istemcisi oluşturup "
                "client_secret.json dosyasının yolunu Ayarlar > YOUTUBE_CLIENT_SECRET_FILE alanına yazın."
            )
        flow = InstalledAppFlow.from_client_secrets_file(secret, SCOPES)
        creds = flow.run_local_server(port=0, open_browser=True)
    TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True)
    TOKEN_PATH.write_text(creds.to_json(), encoding="utf-8")
    return creds


def validate(file_path: str, title: str, privacy: str) -> None:
    if not Path(file_path).is_file():
        raise YouTubeError(f"Video dosyası bulunamadı: {file_path}")
    if not title.strip():
        raise YouTubeError("Video başlığı boş olamaz.")
    if len(title) > 100:
        raise YouTubeError("Video başlığı en fazla 100 karakter olabilir.")
    if privacy not in PRIVACY:
        raise YouTubeError(f"Gizlilik şunlardan biri olmalı: {', '.join(PRIVACY)}")


def upload(file_path: str, title: str, description: str = "", privacy: str = "private", tags: list[str] | None = None) -> str:
    validate(file_path, title, privacy)
    try:
        from googleapiclient.discovery import build
        from googleapiclient.http import MediaFileUpload
    except ImportError as exc:
        raise YouTubeError("YouTube paketleri eksik. setup_windows.bat'ı yeniden çalıştırın.") from exc
    youtube = build("youtube", "v3", credentials=_credentials(), cache_discovery=False)
    body = {
        "snippet": {"title": title, "description": description, "tags": tags or [], "defaultLanguage": "tr"},
        "status": {"privacyStatus": privacy, "selfDeclaredMadeForKids": False},
    }
    media = MediaFileUpload(file_path, chunksize=8 * 1024 * 1024, resumable=True)
    request = youtube.videos().insert(part="snippet,status", body=body, media_body=media)
    response = None
    while response is None:
        _, response = request.next_chunk()
    video_id = response.get("id")
    return f"YouTube'a yüklendi ({privacy}): https://youtu.be/{video_id}"
