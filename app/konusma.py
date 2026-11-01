"""Sesli yanıt (metinden konuşma). Windows'un yerleşik SAPI sesleriyle çevrimdışı çalışır.

Türkçe ses için: Ayarlar > Saat ve dil > Dil ve bölge > Türkçe > Dil seçenekleri > "Konuşma" paketini yükleyin.
"""
from __future__ import annotations

import queue
import re
import threading

_queue: "queue.Queue[str | None]" = queue.Queue()
_thread: threading.Thread | None = None
_lock = threading.Lock()
_engine_error: str = ""


def temizle(text: str) -> str:
    """Markdown, bağlantı ve emoji gibi okunmaması gereken parçaları ayıklar."""
    text = re.sub(r"```.*?```", " (kod bloğu) ", text, flags=re.S)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)          # [metin](url) -> metin
    text = re.sub(r"https?://\S+", "bağlantı", text)
    text = re.sub(r"[*_#>`|~]+", " ", text)
    text = re.sub(r"[\U0001F000-\U0001FFFF☀-➿]", "", text)
    return re.sub(r"\s+", " ", text).strip()


def _pick_turkish_voice(engine) -> None:
    try:
        for v in engine.getProperty("voices"):
            blob = (str(getattr(v, "name", "")) + " " + str(getattr(v, "id", "")) + " " +
                    " ".join(map(str, getattr(v, "languages", []) or []))).lower()
            if any(k in blob for k in ("turkish", "türkçe", "tr-tr", "tolga", "emel", "tr_tr")):
                engine.setProperty("voice", v.id)
                return
    except Exception:
        pass


def _worker():
    global _engine_error
    try:
        import pyttsx3
        engine = pyttsx3.init()
        _pick_turkish_voice(engine)
        engine.setProperty("rate", 185)
    except Exception as exc:
        _engine_error = f"Ses motoru başlatılamadı: {exc}"
        return
    while True:
        text = _queue.get()
        if text is None:
            break
        try:
            engine.say(text)
            engine.runAndWait()
        except Exception as exc:
            _engine_error = str(exc)


def soyle(text: str) -> str:
    """Metni sıraya ekler. Hata varsa açıklamasını döndürür (yoksa boş)."""
    global _thread
    clean = temizle(text)[:3000]
    if not clean:
        return ""
    with _lock:
        if _thread is None or not _thread.is_alive():
            _thread = threading.Thread(target=_worker, name="jarvis-tts", daemon=True)
            _thread.start()
    _queue.put(clean)
    return _engine_error


def sustur() -> None:
    """Bekleyen konuşmaları iptal eder."""
    try:
        while True:
            _queue.get_nowait()
    except queue.Empty:
        pass
