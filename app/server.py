"""FK JARVIS yerel köprü (FastAPI, yalnızca 127.0.0.1).

Kimlik doğrulama: /health dışındaki her istek `X-Jarvis-Token` başlığı ister (data/device.json).
CORS kapalıdır; tarayıcıdaki web sayfaları köprüye istek gönderemez.

Uç noktalar:
  /api/v1/...   sürümlü API (görevler, onaylar, denetim, kullanım, kontrol, çalışma klasörleri, panel)
  /status, /task, /actions/request, /approvals, /history   — masaüstü arayüzünün eski uç noktaları (aynı servisler)
"""
from __future__ import annotations

import time
from typing import Literal

from fastapi import APIRouter, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from . import (approvals, audit, control, db, device, history, orchestrator, tasks, usage, workspaces)
from .config import has_key, host, load_env, port
from .desktop import app_status
from .models import ActionRequest, ApprovalDecision, TaskRequest

load_env()
VERSION = "0.3.0"
STARTED = time.time()
app = FastAPI(title="FK JARVIS Local Bridge", version=VERSION)
v1 = APIRouter(prefix="/api/v1")

PUBLIC_PATHS = {"/health"}


@app.middleware("http")
async def require_token(request: Request, call_next):
    if request.url.path not in PUBLIC_PATHS and not device.check(request.headers.get(device.HEADER)):
        return JSONResponse(status_code=401, content={"detail": "Yetkisiz istek: geçerli X-Jarvis-Token gerekli."})
    return await call_next(request)


def _raise(exc: Exception):
    if isinstance(exc, orchestrator.OrchestratorError):
        raise HTTPException(status_code=exc.status_code, detail=str(exc))
    if isinstance(exc, tasks.TaskError):
        raise HTTPException(status_code=404 if "bulunamadı" in str(exc) else 409, detail=str(exc))
    raise exc


# =============================================================== genel
@app.get("/health")
def health():
    return {"ok": True, "service": "fk-jarvis-local-bridge", "host": host(), "port": port(), "version": VERSION}


@app.get("/status")
def status():
    """Yapılandırma durumu. Anahtarların kendisi asla döndürülmez."""
    return {
        "anthropic_key": has_key("ANTHROPIC_API_KEY"),
        "gemini_key": has_key("GEMINI_API_KEY"),
        "whatsapp": has_key("WHATSAPP_TOKEN") and has_key("WHATSAPP_PHONE_NUMBER_ID"),
        "youtube": has_key("YOUTUBE_CLIENT_SECRET_FILE"),
        "apps": app_status(),
        "kill_switch": control.public_state(),
    }


# =============================================================== eski uç noktalar (masaüstü arayüzü)
@app.post("/task")
def legacy_task(body: TaskRequest):
    """Görev oluşturur ve hemen planlar (geriye dönük uyumluluk)."""
    task = tasks.create(body.instruction, source="ui")
    try:
        task = orchestrator.plan(task["id"])
    except Exception as exc:
        _raise(exc)
    return {"plan": task["plan"], "note": "Plan Görev Panosu'na kaydedildi; eylemler ayrıca onaylanır.",
            "task_id": task["id"]}


@app.post("/actions/request")
def legacy_request_action(body: ActionRequest):
    try:
        return orchestrator.request_action(body.model_dump())
    except Exception as exc:
        _raise(exc)


@app.get("/approvals")
def legacy_approvals():
    return approvals.list_pending()


@app.post("/approvals/{action_id}")
def legacy_decide(action_id: str, body: ApprovalDecision):
    try:
        return orchestrator.decide(action_id, body.approved)
    except Exception as exc:
        _raise(exc)


@app.get("/history")
def legacy_history(limit: int = 200):
    return history.recent(min(max(limit, 1), 1000))


# =============================================================== /api/v1
class TaskCreate(BaseModel):
    instruction: str = Field(min_length=3, max_length=8000)
    title: str = Field(default="", max_length=200)
    source: str = Field(default="api", max_length=30)
    plan: bool = False


class TaskTransition(BaseModel):
    status: Literal["running", "done", "failed", "cancelled", "awaiting_approval"]
    message: str = Field(default="", max_length=2000)
    result: str = Field(default="", max_length=50000)
    error: str = Field(default="", max_length=5000)


class TaskEvent(BaseModel):
    type: str = Field(min_length=1, max_length=40)
    message: str = Field(default="", max_length=4000)


class KillSwitch(BaseModel):
    engaged: bool
    reason: str = Field(default="Kullanıcı acil durdurma", max_length=300)


class Budget(BaseModel):
    daily_budget_usd: float = Field(ge=0, le=10000)


class WorkspaceIn(BaseModel):
    path: str = Field(min_length=2, max_length=1000)
    label: str = Field(default="", max_length=100)


@v1.get("/system")
def system():
    return {"version": VERSION, "schema_version": db.schema_version(), "device_id": device.device_id(),
            "uptime_s": int(time.time() - STARTED), "kill_switch": control.public_state()}


@v1.get("/dashboard")
def dashboard():
    return {
        "tasks": tasks.counts(),
        "recent_tasks": tasks.list_tasks(limit=8),
        "approvals": {"pending": len(approvals.list_pending()), "by_status": approvals.counts()},
        "usage_today": usage.summary(),
        "audit": audit.verify(),
        "kill_switch": control.public_state(),
        "system": {"version": VERSION, "schema_version": db.schema_version(),
                   "uptime_s": int(time.time() - STARTED)},
    }


@v1.get("/tasks")
def list_tasks(status: str | None = None, active: bool = False, limit: int = 100):
    return tasks.list_tasks(status=status, active_only=active, limit=limit)


@v1.post("/tasks", status_code=201)
def create_task(body: TaskCreate):
    try:
        task = tasks.create(body.instruction, source=body.source, title=body.title)
        return orchestrator.plan(task["id"]) if body.plan else task
    except Exception as exc:
        _raise(exc)


@v1.get("/tasks/{task_id}")
def get_task(task_id: str):
    task = tasks.get(task_id, with_events=True)
    if not task:
        raise HTTPException(404, "Görev bulunamadı.")
    task["approvals"] = approvals.for_task(task_id)
    return task


@v1.post("/tasks/{task_id}/plan")
def plan_task(task_id: str):
    try:
        return orchestrator.plan(task_id)
    except Exception as exc:
        _raise(exc)


@v1.post("/tasks/{task_id}/cancel")
def cancel_task(task_id: str):
    try:
        return tasks.cancel(task_id)
    except Exception as exc:
        _raise(exc)


@v1.post("/tasks/{task_id}/status")
def task_status(task_id: str, body: TaskTransition):
    try:
        return tasks.transition(task_id, body.status, body.message, result=body.result or None,
                                error=body.error or None)
    except Exception as exc:
        _raise(exc)


@v1.post("/tasks/{task_id}/events", status_code=201)
def task_event(task_id: str, body: TaskEvent):
    if not tasks.get(task_id):
        raise HTTPException(404, "Görev bulunamadı.")
    tasks.add_event(task_id, body.type, body.message)
    return {"ok": True}


@v1.post("/tasks/{task_id}/actions", status_code=201)
def task_action(task_id: str, body: ActionRequest):
    try:
        return orchestrator.request_action(body.model_dump(), task_id=task_id)
    except Exception as exc:
        _raise(exc)


@v1.get("/approvals")
def list_approvals(status: Literal["pending", "all"] = "pending", limit: int = 100):
    return approvals.list_pending() if status == "pending" else approvals.list_recent(min(max(limit, 1), 1000))


@v1.post("/approvals")
def request_action(body: ActionRequest):
    try:
        return orchestrator.request_action(body.model_dump())
    except Exception as exc:
        _raise(exc)


@v1.post("/approvals/{approval_id}")
def decide(approval_id: str, body: ApprovalDecision):
    try:
        return orchestrator.decide(approval_id, body.approved)
    except Exception as exc:
        _raise(exc)


@v1.get("/audit")
def audit_log(limit: int = 200):
    return audit.recent(min(max(limit, 1), 2000))


@v1.get("/audit/verify")
def audit_verify():
    return audit.verify()


@v1.get("/usage")
def usage_history(days: int = 7):
    return usage.history(days)


@v1.get("/usage/today")
def usage_today():
    return usage.summary()


@v1.put("/usage/budget")
def set_budget(body: Budget):
    usage.set_daily_budget(body.daily_budget_usd)
    audit.record("butce_degisti", "usage", {"daily_budget_usd": body.daily_budget_usd})
    return usage.summary()


@v1.get("/control")
def get_control():
    return control.public_state()


@v1.post("/control")
def set_control(body: KillSwitch):
    return control.engage(body.reason) if body.engaged else control.release()


@v1.get("/workspaces")
def list_workspaces():
    return workspaces.list_all()


@v1.post("/workspaces", status_code=201)
def add_workspace(body: WorkspaceIn):
    try:
        return workspaces.add(body.path, body.label)
    except ValueError as exc:
        raise HTTPException(400, str(exc))


@v1.delete("/workspaces/{workspace_id}")
def remove_workspace(workspace_id: int):
    if not workspaces.remove(workspace_id):
        raise HTTPException(404, "Çalışma klasörü bulunamadı.")
    return {"ok": True}


@v1.get("/history")
def events(limit: int = 200):
    return history.recent(min(max(limit, 1), 1000))


app.include_router(v1)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=host(), port=port())
