from fastapi import FastAPI, HTTPException

from . import approvals
from .claude_client import ClaudeError, plan_task
from .config import has_key, host, load_env, port
from .desktop import app_status, launch_app
from .models import ActionRequest, ApprovalDecision, TaskRequest

load_env()
app = FastAPI(title="FK JARVIS Local Bridge", version="0.2.0")


@app.get("/health")
def health():
    return {"ok": True, "service": "fk-jarvis-local-bridge", "host": host(), "port": port()}


@app.get("/status")
def status():
    """Yapılandırma durumu. Anahtarların kendisi asla döndürülmez."""
    return {
        "anthropic_key": has_key("ANTHROPIC_API_KEY"),
        "gemini_key": has_key("GEMINI_API_KEY"),
        "apps": app_status(),
    }


@app.post("/task")
def create_task(body: TaskRequest):
    try:
        plan = plan_task(body.instruction)
    except ClaudeError as exc:
        raise HTTPException(status_code=502, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Claude bağlantı hatası: {type(exc).__name__}: {exc}")
    return {"plan": plan, "note": "Bu starter sürümü plan üretir; bilgisayar eylemleri ayrıca onaylanmalıdır."}


@app.post("/actions/request")
def request_action(body: ActionRequest):
    return approvals.create(body.model_dump())


@app.get("/approvals")
def get_approvals():
    return approvals.list_pending()


@app.post("/approvals/{action_id}")
def decide_approval(action_id: str, body: ApprovalDecision):
    item = approvals.decide(action_id, body.approved)
    if not item:
        raise HTTPException(status_code=404, detail="Bekleyen onay bulunamadı.")
    if body.approved:
        action = item["action"]
        try:
            result = launch_app(action["app_id"])
        except Exception as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        item["result"] = result
    return item


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=host(), port=port())
