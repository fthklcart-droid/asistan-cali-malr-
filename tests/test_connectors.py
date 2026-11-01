import json
from types import SimpleNamespace as NS

import pytest

from app.connectors import photoshop, whatsapp, youtube


def test_whatsapp_number_normalization():
    assert whatsapp.normalize_number("+90 532 123 45 67") == "905321234567"
    assert whatsapp.normalize_number("0532 123 45 67") == "905321234567"
    assert whatsapp.normalize_number("5321234567") == "905321234567"
    with pytest.raises(whatsapp.WhatsAppError):
        whatsapp.normalize_number("123")


def test_whatsapp_send(monkeypatch):
    monkeypatch.setenv("WHATSAPP_TOKEN", "tok")
    monkeypatch.setenv("WHATSAPP_PHONE_NUMBER_ID", "111")
    sent = {}

    class Session:
        def post(self, url, json, headers, timeout):
            sent.update(url=url, json=json, headers=headers)
            return NS(status_code=200, json=lambda: {"messages": [{"id": "wamid.X"}]}, text="")

    assert "wamid.X" in whatsapp.send_text("0532 123 45 67", "Merhaba", session=Session())
    assert sent["url"].endswith("/111/messages") and sent["json"]["to"] == "905321234567"
    assert sent["headers"]["Authorization"] == "Bearer tok"


def test_whatsapp_requires_config(monkeypatch):
    monkeypatch.setenv("WHATSAPP_TOKEN", "")
    with pytest.raises(whatsapp.WhatsAppError):
        whatsapp.send_text("905321234567", "x")


def test_photoshop_scripts(tmp_path):
    assert "autoLevels" in photoshop.build_script("auto_tone")
    # Betik enjeksiyonu: kaçışlanmış dize JS'de tek bir dize sabiti olarak kalmalı
    hostile = 'x"); app.quit(); ("'
    assert photoshop._js_str(hostile) == json.dumps(hostile) and '\\"' in photoshop._js_str(hostile)
    img = tmp_path / "a'b;c (1).jpg"  # Windows'ta geçerli, betik için zor karakterler
    img.write_bytes(b"")
    script = photoshop.build_script("open", str(img))
    assert json.dumps(img.as_posix()) in script
    out = photoshop.build_script("export_png", str(tmp_path / "cikti.jpg"))
    assert "cikti.png" in out
    with pytest.raises(photoshop.PhotoshopError):
        photoshop.build_script("export_jpeg", str(img))  # var olan dosyanın üzerine yazmaz
    with pytest.raises(photoshop.PhotoshopError):
        photoshop.build_script("delete_all")


def test_youtube_validation(tmp_path):
    with pytest.raises(youtube.YouTubeError):
        youtube.validate(str(tmp_path / "yok.mp4"), "Başlık", "private")
    video = tmp_path / "v.mp4"; video.write_bytes(b"0")
    youtube.validate(str(video), "Başlık", "unlisted")
    with pytest.raises(youtube.YouTubeError):
        youtube.validate(str(video), "x" * 101, "private")
