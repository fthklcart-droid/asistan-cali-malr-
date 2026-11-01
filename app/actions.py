"""Onaylanmış eylemleri yürütür. Yeni eylem türleri yalnızca buraya ve models.py'ye eklenir."""
from __future__ import annotations

from .desktop import launch_app


def summarize(action: dict) -> str:
    kind = action.get("action")
    if kind == "launch_app":
        return f"{action['app_id']} uygulamasını aç"
    if kind == "whatsapp_send":
        return f"WhatsApp → {action['to']}: {action['body'][:80]}"
    if kind == "youtube_upload":
        return f"YouTube ({action.get('privacy', 'private')}) ← {action['title']}"
    if kind == "photoshop":
        from .connectors.photoshop import COMMANDS
        extra = action.get("path") or (action.get("value") or "")
        return f"Photoshop: {COMMANDS.get(action['command'], action['command'])} {extra}".strip()
    return str(kind)


def execute(action: dict) -> str:
    kind = action.get("action")
    if kind == "launch_app":
        return launch_app(action["app_id"])
    if kind == "whatsapp_send":
        from .connectors import whatsapp
        return whatsapp.send_text(action["to"], action["body"])
    if kind == "youtube_upload":
        from .connectors import youtube
        return youtube.upload(action["file_path"], action["title"], action.get("video_description", ""),
                              action.get("privacy", "private"))
    if kind == "photoshop":
        from .connectors import photoshop
        return photoshop.run(action["command"], action.get("path", ""), int(action.get("value") or 0))
    raise ValueError(f"Bilinmeyen eylem: {kind}")
