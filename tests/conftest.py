import pytest


@pytest.fixture(autouse=True)
def isolated_data(tmp_path, monkeypatch):
    """Her test kendi geçici veritabanı, cihaz kimliği ve not klasörüyle çalışır; gerçek data/ kullanılmaz."""
    from app import db, device
    from app.beceriler import dosyalar
    monkeypatch.setattr(db, "DB_PATH", tmp_path / "jarvis.db")
    monkeypatch.setattr(device, "DEVICE_PATH", tmp_path / "device.json")
    device.reset_cache()
    monkeypatch.setattr(dosyalar, "NOTES_DIR", tmp_path / "notlar")
    yield
    device.reset_cache()


@pytest.fixture
def api():
    """Kimliği doğrulanmış test istemcisi."""
    from fastapi.testclient import TestClient

    from app import device
    from app.server import app
    return TestClient(app, headers=device.auth_headers())
