from uuid import uuid4
from datetime import datetime, timezone
from threading import Lock

_lock = Lock()
_pending: dict[str, dict] = {}


def create(action: dict) -> dict:
    item = {
        "id": str(uuid4()),
        "action": action,
        "status": "pending",
        "created_at": datetime.now(timezone.utc).isoformat(),
    }
    with _lock:
        _pending[item["id"]] = item
    return item


def list_pending() -> list[dict]:
    with _lock:
        return [dict(v) for v in _pending.values() if v["status"] == "pending"]


def decide(action_id: str, approved: bool) -> dict | None:
    with _lock:
        item = _pending.get(action_id)
        if not item or item["status"] != "pending":
            return None
        item["status"] = "approved" if approved else "rejected"
        return dict(item)
