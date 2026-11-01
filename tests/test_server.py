from fastapi.testclient import TestClient

from app import desktop
from app.server import app

client = TestClient(app)


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["ok"] is True


def test_status_never_leaks_keys(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-secret-value")
    r = client.get("/status")
    assert r.status_code == 200
    body = r.text
    assert "sk-secret-value" not in body
    assert r.json()["anthropic_key"] is True


def test_rejects_unknown_app():
    r = client.post("/actions/request", json={"action": "launch_app", "app_id": "cmd"})
    assert r.status_code == 422


def test_task_without_key_returns_hint(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "")
    r = client.post("/task", json={"instruction": "Photoshop'u aç"})
    assert r.status_code == 200
    assert "ANTHROPIC_API_KEY" in r.json()["plan"]


def test_approve_launches_only_allowed_app(monkeypatch):
    launched = []
    monkeypatch.setattr("app.server.launch_app", lambda app_id: launched.append(app_id) or f"{app_id} başlatıldı")
    item = client.post("/actions/request", json={"action": "launch_app", "app_id": "browser"}).json()
    r = client.post(f"/approvals/{item['id']}", json={"approved": True})
    assert r.status_code == 200
    assert launched == ["browser"]
    assert client.post(f"/approvals/{item['id']}", json={"approved": True}).status_code == 404


def test_missing_app_returns_turkish_error(monkeypatch):
    monkeypatch.setattr(desktop, "resolve_app", lambda app_id: None)
    item = client.post("/actions/request", json={"action": "launch_app", "app_id": "capcut"}).json()
    r = client.post(f"/approvals/{item['id']}", json={"approved": True})
    assert r.status_code == 400
    assert "bulunamadı" in r.json()["detail"]
