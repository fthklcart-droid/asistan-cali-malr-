"""Orkestratör: eylemlerin ve görev planlamanın tek giriş noktası.

Eylem yolu (mimari belgedeki yaşam döngüsü 5-7. adımlar):
    istek ─► politika motoru ─► onay kuyruğu ─► (kullanıcı onayı) ─► politika (yeniden) ─► yürütme
          ─► sonuç (onay kaydı + görev zaman çizelgesi + etkinlik akışı + denetim kaydı)
"""
from __future__ import annotations

from . import actions, approvals, audit, history, policy, tasks


class OrchestratorError(RuntimeError):
    def __init__(self, message: str, status_code: int = 400):
        super().__init__(message)
        self.status_code = status_code


def request_action(action: dict, task_id: str | None = None, actor: str = "kullanici") -> dict:
    decision = policy.evaluate_action(action)
    if decision.effect == "deny":
        audit.record("eylem_reddedildi_politika", str(action.get("action")), {"reason": decision.reason}, actor)
        raise OrchestratorError(decision.reason, 403)
    if task_id and not tasks.get(task_id):
        raise OrchestratorError("Görev bulunamadı.", 404)
    summary = actions.summarize(action)
    action = {**action, "summary": summary}
    item = approvals.create(action, task_id=task_id, risk=decision.risk, summary=summary)
    item["policy"] = decision.as_dict()
    history.log("onay-istegi", summary, action, "bekliyor")
    audit.record("onay_istendi", item["id"], {"kind": action.get("action"), "risk": decision.risk,
                                               "task_id": task_id}, actor)
    if task_id:
        task = tasks.get(task_id)
        tasks.add_event(task_id, "approval_requested", f"Onay istendi: {summary}", {"approval_id": item["id"]})
        if task["status"] in ("queued", "planned", "running", "awaiting_approval"):
            tasks.transition(task_id, "awaiting_approval", "Kullanıcı onayı bekleniyor")
    return item


def _after_approval(item: dict) -> None:
    task_id = item.get("task_id")
    if not task_id:
        return
    task = tasks.get(task_id)
    if not task or task["status"] not in tasks.ACTIVE:
        return
    tasks.add_event(task_id, "approval_" + item["status"], f"{item['summary']} → {item['status']}",
                    {"approval_id": item["id"], "result": item["result"], "error": item["error"]})
    still_pending = any(a["status"] == "pending" for a in approvals.for_task(task_id))
    if still_pending:
        return
    if item["status"] == "failed":
        tasks.transition(task_id, "failed", "Eylem başarısız oldu", error=item["error"])
    elif item["status"] == "rejected":
        tasks.transition(task_id, "cancelled", "Kullanıcı eylemi reddetti")
    elif item["status"] == "executed" and task["status"] == "awaiting_approval":
        tasks.transition(task_id, "done", "Onaylanan eylem tamamlandı", result=item["result"])


def decide(approval_id: str, approved: bool, actor: str = "kullanici") -> dict:
    current = approvals.get(approval_id)
    if not current or current["status"] != "pending":
        raise OrchestratorError("Bekleyen onay bulunamadı.", 404)
    item = approvals.decide(approval_id, approved)
    if item is None:  # aynı anda başka bir yerden karara bağlandı
        raise OrchestratorError("Bekleyen onay bulunamadı.", 404)
    summary = item["summary"]
    if not approved:
        history.log("eylem", summary, "", "reddedildi")
        audit.record("onay_reddedildi", approval_id, {"kind": item["action"].get("action")}, actor)
        _after_approval(item)
        return item
    audit.record("onay_verildi", approval_id, {"kind": item["action"].get("action")}, actor)
    # Onay ile yürütme arasında acil durdurma/sınır değişmiş olabilir: politika yeniden sorulur.
    decision = policy.evaluate_action(item["action"])
    if decision.effect == "deny":
        item = approvals.finish(approval_id, error=decision.reason)
        history.log("eylem", summary, decision.reason, "hata")
        audit.record("eylem_engellendi", approval_id, {"reason": decision.reason}, "politika")
        _after_approval(item)
        raise OrchestratorError(decision.reason, 403)
    try:
        result = actions.execute(item["action"])
    except Exception as exc:
        item = approvals.finish(approval_id, error=str(exc))
        history.log("eylem", summary, str(exc), "hata")
        audit.record("eylem_hata", approval_id, {"error": str(exc)[:500]}, "sistem")
        _after_approval(item)
        raise OrchestratorError(str(exc), 400) from exc
    item = approvals.finish(approval_id, result=str(result))
    history.log("eylem", summary, str(result), "tamam")
    audit.record("eylem_calisti", approval_id, {"kind": item["action"].get("action")}, "sistem")
    _after_approval(item)
    return item


def plan(task_id: str) -> dict:
    """Görevi Claude ile planlar; plan görev kaydına yazılır."""
    from .claude_client import ClaudeError, plan_task
    task = tasks.get(task_id)
    if not task:
        raise OrchestratorError("Görev bulunamadı.", 404)
    d = policy.evaluate_model_call("plan")
    if not d.allowed:
        raise OrchestratorError(d.reason, 403)
    tasks.transition(task_id, "planning", "Claude planlıyor")
    try:
        text = plan_task(task["instruction"])
    except ClaudeError as exc:
        tasks.transition(task_id, "failed", "Planlama başarısız", error=str(exc))
        history.log("plan", task["instruction"], str(exc), "hata")
        raise OrchestratorError(str(exc), 502) from exc
    except Exception as exc:
        tasks.transition(task_id, "failed", "Planlama başarısız", error=str(exc))
        raise OrchestratorError(f"Claude bağlantı hatası: {type(exc).__name__}: {exc}", 502) from exc
    history.log("plan", task["instruction"], text, "tamam")
    return tasks.transition(task_id, "planned", "Plan hazır", plan=text)
