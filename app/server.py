from fastapi import FastAPI, HTTPException

from . import actions, approvals, history
from .claude_client import ClaudeError, plan_task
from .config import has_key, host, load_env, port
from .desktop import app_status
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
        "whatsapp": has_key("WHATSAPP_TOKEN") and has_key("WHATSAPP_PHONE_NUMBER_ID"),
        "youtube": has_key("YOUTUBE_CLIENT_SECRET_FILE"),
        "apps": app_status(),
    }


@app.post("/task")
def create_task(body: TaskRequest):
    try:
        plan = plan_task(body.instruction)
    except ClaudeError as exc:
        history.log("plan", body.instruction, str(exc), "hata")
        raise HTTPException(status_code=502, detail=str(exc))
    except Exception as exc:
        history.log("plan", body.instruction, str(exc), "hata")
        raise HTTPException(status_code=502, detail=f"Claude bağlantı hatası: {type(exc).__name__}: {exc}")
    history.log("plan", body.instruction, plan, "tamam")
    return {"plan": plan, "note": "Bu starter sürümü plan üretir; bilgisayar eylemleri ayrıca onaylanmalıdır."}


@app.post("/actions/request")
def request_action(body: ActionRequest):
    action = body.model_dump()
    action["summary"] = actions.summarize(action)
    item = approvals.create(action)
    history.log("onay-istegi", action["summary"], action, "bekliyor")
    return item


@app.get("/approvals")
def get_approvals():
    return approvals.list_pending()


@app.post("/approvals/{action_id}")
def decide_approval(action_id: str, body: ApprovalDecision):
    item = approvals.decide(action_id, body.approved)
    if not item:
        raise HTTPException(status_code=404, detail="Bekleyen onay bulunamadı.")
    action = item["action"]
    summary = action.get("summary") or actions.summarize(action)
    if not body.approved:
        history.log("eylem", summary, "", "reddedildi")
        return item
    try:
        result = actions.execute(action)
    except Exception as exc:
        history.log("eylem", summary, str(exc), "hata")
        raise HTTPException(status_code=400, detail=str(exc))
    history.log("eylem", summary, result, "tamam")
    item["result"] = result
    return item


@app.get("/history")
def get_history(limit: int = 200):
    return history.recent(min(max(limit, 1), 1000))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=host(), port=port())
