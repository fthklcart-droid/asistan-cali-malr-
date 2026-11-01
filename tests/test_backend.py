from types import SimpleNamespace as NS

import pytest
from fastapi.testclient import TestClient

from app import approvals, audit, control, policy, tasks, usage, workspaces
from app.server import app


# ------------------------------------------------------------------ kimlik doğrulama
def test_auth_required_except_health():
    anon = TestClient(app)
    assert anon.get("/health").status_code == 200
    for path in ("/status", "/approvals", "/api/v1/dashboard", "/api/v1/tasks"):
        assert anon.get(path).status_code == 401
    assert anon.get("/api/v1/tasks", headers={"X-Jarvis-Token": "yanlis-token"}).status_code == 401


# ------------------------------------------------------------------ görevler
def test_classification():
    assert tasks.classify("Photoshop'ta logoyu düzenle") == "tasarim"
    assert tasks.classify("CapCut ile reels montajı") == "video"
    assert tasks.classify("Müşteriye WhatsApp mesajı at") == "iletisim"
    assert tasks.classify("PDF faturaları klasöre taşı") == "dosya"
    assert tasks.classify("Merhaba") == "genel"


def test_task_lifecycle_and_state_machine(api):
    t = api.post("/api/v1/tasks", json={"instruction": "Photoshop'ta kataloğu hazırla"}).json()
    assert t["status"] == "queued" and t["category"] == "tasarim"
    assert api.post(f"/api/v1/tasks/{t['id']}/status", json={"status": "done"}).status_code == 409  # queued → done yok
    assert api.post(f"/api/v1/tasks/{t['id']}/status", json={"status": "running"}).json()["status"] == "running"
    api.post(f"/api/v1/tasks/{t['id']}/events", json={"type": "agent", "message": "→ Sol tıkla"})
    done = api.post(f"/api/v1/tasks/{t['id']}/status", json={"status": "done", "result": "Hazır"}).json()
    assert done["status"] == "done" and done["result"] == "Hazır"
    detail = api.get(f"/api/v1/tasks/{t['id']}").json()
    assert [e["type"] for e in detail["events"]] == ["created", "status", "agent", "status"]
    assert api.post(f"/api/v1/tasks/{t['id']}/cancel").status_code == 409  # bitmiş görev iptal edilemez
    assert api.get("/api/v1/tasks/yok").status_code == 404


def test_plan_without_key_records_plan(api, monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "")
    t = api.post("/api/v1/tasks", json={"instruction": "Chrome'u aç", "plan": True}).json()
    assert t["status"] == "planned" and "ANTHROPIC_API_KEY" in t["plan"]


def test_task_action_approval_flow(api, monkeypatch):
    monkeypatch.setattr("app.actions.launch_app", lambda a: f"{a} açıldı")
    t = api.post("/api/v1/tasks", json={"instruction": "Tarayıcıyı aç"}).json()
    item = api.post(f"/api/v1/tasks/{t['id']}/actions", json={"action": "launch_app", "app_id": "browser"}).json()
    assert item["task_id"] == t["id"] and item["policy"]["effect"] == "approve"
    assert api.get(f"/api/v1/tasks/{t['id']}").json()["status"] == "awaiting_approval"
    assert api.post(f"/api/v1/approvals/{item['id']}", json={"approved": True}).json()["status"] == "executed"
    task = api.get(f"/api/v1/tasks/{t['id']}").json()
    assert task["status"] == "done" and task["result"] == "browser açıldı"
    events = [e["event"] for e in api.get("/api/v1/audit").json()]
    assert {"gorev_olusturuldu", "onay_istendi", "onay_verildi", "eylem_calisti"} <= set(events)
    assert api.get("/api/v1/audit/verify").json()["ok"] is True


def test_rejected_and_failed_actions_update_task(api, monkeypatch):
    t1 = tasks.create("Premiere'i aç")
    a1 = api.post(f"/api/v1/tasks/{t1['id']}/actions", json={"action": "launch_app", "app_id": "premiere"}).json()
    api.post(f"/api/v1/approvals/{a1['id']}", json={"approved": False})
    assert tasks.get(t1["id"])["status"] == "cancelled"

    def boom(_):
        raise FileNotFoundError("capcut bulunamadı")
    monkeypatch.setattr("app.actions.launch_app", boom)
    t2 = tasks.create("CapCut'ı aç")
    a2 = api.post(f"/api/v1/tasks/{t2['id']}/actions", json={"action": "launch_app", "app_id": "capcut"}).json()
    r = api.post(f"/api/v1/approvals/{a2['id']}", json={"approved": True})
    assert r.status_code == 400 and "bulunamadı" in r.json()["detail"]
    assert tasks.get(t2["id"])["status"] == "failed" and approvals.get(a2["id"])["status"] == "failed"


# ------------------------------------------------------------------ acil durdurma
def test_kill_switch_blocks_everything(api):
    t = tasks.create("Uzun görev")
    tasks.transition(t["id"], "running")
    pending = api.post("/api/v1/approvals", json={"action": "launch_app", "app_id": "browser"}).json()
    state = api.post("/api/v1/control", json={"engaged": True, "reason": "test"}).json()
    assert state["engaged"] and state["expired_approvals"] == 1 and state["cancelled_tasks"] == 1
    assert approvals.get(pending["id"])["status"] == "expired"
    assert tasks.get(t["id"])["status"] == "cancelled"
    blocked = api.post("/api/v1/approvals", json={"action": "launch_app", "app_id": "browser"})
    assert blocked.status_code == 403 and "Acil durdurma" in blocked.json()["detail"]
    assert not policy.evaluate_model_call("sohbet").allowed
    assert api.get("/api/v1/dashboard").json()["kill_switch"]["engaged"] is True
    api.post("/api/v1/control", json={"engaged": False})
    assert api.post("/api/v1/approvals", json={"action": "launch_app", "app_id": "browser"}).status_code == 200


def test_kill_switch_stops_computer_agent():
    from tests.test_computer_agent import CB, FakeClient, FakeExecutor, resp, tool
    control.engage("test")
    client = FakeClient([resp([tool("t1", "screenshot")])])
    result = __import__("app.computer_agent", fromlist=["run_agent"]).run_agent(
        "x", FakeExecutor(), CB(), "auto", client=client, sleep=lambda s: None)
    assert result.status == "stopped" and client.calls == []
    control.release()


# ------------------------------------------------------------------ maliyet ve bütçe
def test_cost_estimate_and_budget(api):
    assert usage.estimate_cost("claude-opus-5-5", 1_000_000, 100_000) == pytest.approx(6.0)
    assert usage.estimate_cost("claude-sonnet-5-5", 0, 0, cache_read=1_000_000, web_searches=3) == pytest.approx(0.23)
    assert usage.estimate_cost("bilinmeyen-model", 10, 10) is None
    usage.record("claude-opus-5-5", "sohbet", NS(input_tokens=500_000, output_tokens=50_000,
                                                  cache_read_input_tokens=0, cache_creation_input_tokens=0,
                                                  server_tool_use=NS(web_search_requests=2)))
    today = api.get("/api/v1/usage/today").json()
    assert today["calls"] == 1 and today["cost_usd"] == pytest.approx(3.02) and today["web_searches"] == 2
    assert policy.evaluate_model_call("x").allowed  # bütçe yok = sınırsız
    api.put("/api/v1/usage/budget", json={"daily_budget_usd": 3})
    assert not policy.evaluate_model_call("x").allowed
    with pytest.raises(policy.PolicyDenied, match="bütçe"):
        policy.require_model_call("x")


def test_chat_refuses_when_budget_exceeded():
    from app.sohbet import Sohbet
    usage.set_daily_budget(0.01)
    usage.record("claude-opus-5-5", "x", {"input_tokens": 100_000, "output_tokens": 0})
    with pytest.raises(RuntimeError, match="bütçe"):
        Sohbet(client=object()).send("merhaba", lambda t: None)


def test_chat_records_usage():
    from tests.test_sohbet import FakeClient, FakeStream
    from app.sohbet import Sohbet
    final = NS(content=[NS(type="text", text="Selam")], stop_reason="end_turn", model="claude-opus-5-5",
               usage=NS(input_tokens=1000, output_tokens=100, cache_read_input_tokens=0,
                        cache_creation_input_tokens=0, server_tool_use=None))
    Sohbet(FakeClient([FakeStream(final, ["Selam"])])).send("merhaba", lambda t: None)
    s = usage.summary()
    assert s["calls"] == 1 and s["input_tokens"] == 1000 and s["cost_usd"] == pytest.approx(0.006)


# ------------------------------------------------------------------ politika
def test_daily_limit(api, monkeypatch):
    from app import db
    db.set_setting("policy.limit.launch_app", "1")
    monkeypatch.setattr("app.actions.launch_app", lambda a: "ok")
    a = api.post("/api/v1/approvals", json={"action": "launch_app", "app_id": "browser"}).json()
    api.post(f"/api/v1/approvals/{a['id']}", json={"approved": True})
    r = api.post("/api/v1/approvals", json={"action": "launch_app", "app_id": "browser"})
    assert r.status_code == 403 and "sınır" in r.json()["detail"]
    assert policy.evaluate_action({"action": "rm -rf"}).effect == "deny"
    assert "HERKESE AÇIK" in policy.evaluate_action({"action": "youtube_upload", "privacy": "public"}).reason


# ------------------------------------------------------------------ çalışma klasörleri
def test_workspaces_api_and_file_access(api, tmp_path, monkeypatch):
    from pathlib import Path

    from app.beceriler import dosyalar
    monkeypatch.setattr(Path, "home", lambda: tmp_path / "home")
    proj = tmp_path / "Projeler"; proj.mkdir()
    (proj / "brief.txt").write_text("Katalog briefi", encoding="utf-8")
    with pytest.raises(dosyalar.DosyaHatasi, match="çalışma klasör"):
        dosyalar.oku(str(proj / "brief.txt"))
    w = api.post("/api/v1/workspaces", json={"path": str(proj), "label": "Projeler"}).json()
    assert dosyalar.oku(str(proj / "brief.txt"))["icerik"] == "Katalog briefi"
    assert api.post("/api/v1/workspaces", json={"path": str(tmp_path / "yok")}).status_code == 400
    assert api.delete(f"/api/v1/workspaces/{w['id']}").json() == {"ok": True}
    with pytest.raises(dosyalar.DosyaHatasi):
        dosyalar.oku(str(proj / "brief.txt"))
    assert any(e["event"] == "calisma_klasoru_eklendi" for e in audit.recent())


# ------------------------------------------------------------------ panel
def test_dashboard_aggregates(api):
    tasks.create("Bir")
    t = tasks.create("İki"); tasks.transition(t["id"], "running")
    api.post("/api/v1/approvals", json={"action": "launch_app", "app_id": "browser"})
    d = api.get("/api/v1/dashboard").json()
    assert d["tasks"]["queued"] == 1 and d["tasks"]["running"] == 1 and d["tasks"]["active"] == 2
    assert d["approvals"]["pending"] == 1 and d["audit"]["ok"] is True
    assert d["usage_today"]["calls"] == 0 and d["system"]["schema_version"] >= 3
    assert len(d["recent_tasks"]) == 2
    s = api.get("/api/v1/system").json()
    assert s["device_id"] and s["version"]


def test_interrupted_tasks_are_recovered_on_startup():
    t = tasks.create("Yarıda kalacak görev")
    tasks.transition(t["id"], "running")
    keep = tasks.create("Sırada bekleyen")
    with TestClient(app):  # 'with' bloğu uygulama açılışını (lifespan) çalıştırır
        pass
    assert tasks.get(t["id"])["status"] == "failed" and "yarıda" in tasks.get(t["id"])["error"]
    assert tasks.get(keep["id"])["status"] == "queued"
    assert audit.recent(1)[0]["event"] == "yarim_gorevler_kurtarildi"
