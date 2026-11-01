import pytest


@pytest.fixture(autouse=True)
def isolated_data(tmp_path, monkeypatch):
    """Testler gerçek data/ klasörüne yazmasın."""
    from app import history
    from app.beceriler import dosyalar, hafiza, planlayici
    monkeypatch.setattr(history, "DB_PATH", tmp_path / "jarvis.db")
    monkeypatch.setattr(hafiza, "DB_PATH", tmp_path / "hafiza.db")
    monkeypatch.setattr(planlayici, "DB_PATH", tmp_path / "planlayici.db")
    monkeypatch.setattr(dosyalar, "NOTES_DIR", tmp_path / "notlar")
    yield
