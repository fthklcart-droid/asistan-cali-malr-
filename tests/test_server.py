from fastapi.testclient import TestClient

from app import desktop
from app.server import app


def test_health(api):
    r = api.get("/health")
    assert r.status_code == 200
    assert r.json()["ok"] is True


def test_status_never_leaks_keys(api, monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-secret-value")
    r = api.get("/status")
    assert r.status_code == 200
    body = r.text
    assert "sk-secret-value" not in body
    assert r.json()["anthropic_key"] is True


def test_rejects_unknown_app(api):
    r = api.post("/actions/request", json={"action": "launch_app", "app_id": "cmd"})
    assert r.status_code == 422


def test_task_without_key_returns_hint(api, monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "")
    r = api.post("/task", json={"instruction": "Photoshop'u aç"})
    assert r.status_code == 200
    assert "ANTHROPIC_API_KEY" in r.json()["plan"]


def test_approve_launches_only_allowed_app(api, monkeypatch):
    launched = []
    monkeypatch.setattr("app.actions.launch_app", lambda app_id: launched.append(app_id) or f"{app_id} başlatıldı")
    item = api.post("/actions/request", json={"action": "launch_app", "app_id": "browser"}).json()
    r = api.post(f"/approvals/{item['id']}", json={"approved": True})
    assert r.status_code == 200
    assert launched == ["browser"]
    assert api.post(f"/approvals/{item['id']}", json={"approved": True}).status_code == 404


def test_missing_app_returns_turkish_error(api, monkeypatch):
    monkeypatch.setattr(desktop, "resolve_app", lambda app_id: None)
    item = api.post("/actions/request", json={"action": "launch_app", "app_id": "capcut"}).json()
    r = api.post(f"/approvals/{item['id']}", json={"approved": True})
    assert r.status_code == 400
    assert "bulunamadı" in r.json()["detail"]


def test_whatsapp_action_validation_and_history(api, monkeypatch):
    bad = api.post("/actions/request", json={"action": "whatsapp_send", "to": "1", "body": ""})
    assert bad.status_code == 422
    sent = []
    monkeypatch.setattr("app.connectors.whatsapp.send_text", lambda to, body: sent.append((to, body)) or "gönderildi")
    item = api.post("/actions/request", json={"action": "whatsapp_send", "to": "05321234567", "body": "Selam"}).json()
    assert "WhatsApp" in item["action"]["summary"]
    assert api.post(f"/approvals/{item['id']}", json={"approved": True}).json()["result"] == "gönderildi"
    assert sent == [("05321234567", "Selam")]
    kinds = [e["kind"] for e in api.get("/history").json()]
    assert "eylem" in kinds and "onay-istegi" in kinds
