"""WhatsApp Business Cloud API: metin mesajı gönderme."""
from __future__ import annotations

import os
import re

import requests

from ..config import load_env

GRAPH = "https://graph.facebook.com"


class WhatsAppError(RuntimeError):
    pass


def normalize_number(number: str) -> str:
    """'+90 532 123 45 67' / '0532...' -> '905321234567' (uluslararası, yalnız rakam)."""
    digits = re.sub(r"\D", "", number or "")
    if digits.startswith("00"):
        digits = digits[2:]
    if len(digits) == 11 and digits.startswith("0"):  # Türkiye yerel biçimi
        digits = "90" + digits[1:]
    if len(digits) == 10 and digits.startswith("5"):
        digits = "90" + digits
    if not 8 <= len(digits) <= 15:
        raise WhatsAppError(f"Geçersiz telefon numarası: {number}")
    return digits


def is_configured() -> bool:
    load_env()
    return bool(os.getenv("WHATSAPP_TOKEN", "").strip() and os.getenv("WHATSAPP_PHONE_NUMBER_ID", "").strip())


def send_text(to: str, body: str, session: requests.Session | None = None) -> str:
    load_env()
    token = os.getenv("WHATSAPP_TOKEN", "").strip()
    phone_id = os.getenv("WHATSAPP_PHONE_NUMBER_ID", "").strip()
    version = os.getenv("WHATSAPP_API_VERSION", "").strip() or "v23.0"
    if not token or not phone_id:
        raise WhatsAppError("WhatsApp ayarlı değil: Ayarlar sekmesinde WHATSAPP_TOKEN ve WHATSAPP_PHONE_NUMBER_ID girin.")
    if not body.strip():
        raise WhatsAppError("Mesaj metni boş.")
    payload = {"messaging_product": "whatsapp", "recipient_type": "individual",
               "to": normalize_number(to), "type": "text", "text": {"preview_url": False, "body": body}}
    http = session or requests
    r = http.post(f"{GRAPH}/{version}/{phone_id}/messages", json=payload,
                  headers={"Authorization": f"Bearer {token}"}, timeout=20)
    try:
        data = r.json()
    except ValueError:
        data = {}
    if r.status_code >= 400:
        msg = (data.get("error") or {}).get("message") or r.text[:300]
        raise WhatsAppError(f"WhatsApp API hatası ({r.status_code}): {msg}")
    msg_id = ((data.get("messages") or [{}])[0]).get("id", "?")
    return f"WhatsApp mesajı gönderildi (kimlik: {msg_id})."
