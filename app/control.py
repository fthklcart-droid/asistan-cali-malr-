"""Global acil durdurma (kill switch).

Etkinleştirildiğinde: bekleyen tüm onaylar geçersiz olur, çalışan bilgisayar kontrolü ve sohbet döngüleri
bir sonraki adımda durur, yeni eylem/Claude çağrısı politika motorunca reddedilir. Durum veritabanında
tutulur (köprü ve arayüz aynı durumu görür) ve kısa süreli önbelleklenir.
"""
from __future__ import annotations

import json
import threading
import time

from . import approvals, audit, db

_KEY = "control.kill_switch"
_cache: tuple[float, dict] | None = None
_lock = threading.Lock()
_TTL = 1.0


def state() -> dict:
    global _cache
    with _lock:
        if _cache and time.monotonic() - _cache[0] < _TTL and _cache[1].get("_db") == str(db.DB_PATH):
            return _cache[1]
    raw = db.get_setting(_KEY, "")
    data = json.loads(raw) if raw else {"engaged": False, "reason": "", "since": None}
    data["_db"] = str(db.DB_PATH)
    with _lock:
        _cache = (time.monotonic(), data)
    return data


def engaged() -> bool:
    return bool(state().get("engaged"))


def _invalidate():
    global _cache
    with _lock:
        _cache = None


def engage(reason: str = "Kullanıcı acil durdurma", actor: str = "kullanici") -> dict:
    data = {"engaged": True, "reason": reason, "since": db.now()}
    db.set_setting(_KEY, json.dumps(data, ensure_ascii=False))
    _invalidate()
    expired = approvals.expire_all("acil durdurma")
    from . import tasks  # döngüsel içe aktarmayı önlemek için geç yükleme
    cancelled = tasks.cancel_active("Acil durdurma etkinleştirildi")
    audit.record("acil_durdurma", "sistem", {"reason": reason, "expired_approvals": expired,
                                              "cancelled_tasks": cancelled}, actor)
    return {**data, "expired_approvals": expired, "cancelled_tasks": cancelled}


def release(actor: str = "kullanici") -> dict:
    data = {"engaged": False, "reason": "", "since": None}
    db.set_setting(_KEY, json.dumps(data))
    _invalidate()
    audit.record("acil_durdurma_kaldirildi", "sistem", {}, actor)
    return data


def public_state() -> dict:
    return {k: v for k, v in state().items() if not k.startswith("_")}
