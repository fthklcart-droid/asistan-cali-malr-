import json

import pytest

from app import desktop


def _write_config(tmp_path, monkeypatch, data):
    cfg = tmp_path / "apps.json"
    cfg.write_text(json.dumps(data), encoding="utf-8")
    monkeypatch.setattr(desktop, "CONFIG_PATH", cfg)


def test_rejects_non_allowlisted_app():
    with pytest.raises(ValueError):
        desktop.resolve_app("powershell")


def test_configured_path_with_env_var(tmp_path, monkeypatch):
    exe = tmp_path / "Photoshop.exe"
    exe.write_bytes(b"")
    monkeypatch.setenv("FKJ_TEST_DIR", str(tmp_path))
    _write_config(tmp_path, monkeypatch, {"photoshop": "%FKJ_TEST_DIR%/Photoshop.exe"})
    monkeypatch.setattr(desktop, "CANDIDATES", {})
    assert desktop.resolve_app("photoshop") == str(exe)


def test_falls_back_to_newest_detected_version(tmp_path, monkeypatch):
    for year in ("2024", "2026", "2025"):
        d = tmp_path / f"Adobe Photoshop {year}"
        d.mkdir()
        (d / "Photoshop.exe").write_bytes(b"")
    _write_config(tmp_path, monkeypatch, {"photoshop": str(tmp_path / "yok" / "Photoshop.exe")})
    monkeypatch.setattr(desktop, "CANDIDATES", {"photoshop": [str(tmp_path / "Adobe Photoshop *" / "Photoshop.exe")]})
    assert "2026" in desktop.resolve_app("photoshop")


def test_missing_app_raises_file_not_found(tmp_path, monkeypatch):
    _write_config(tmp_path, monkeypatch, {})
    monkeypatch.setattr(desktop, "CANDIDATES", {})
    with pytest.raises(FileNotFoundError):
        desktop.launch_app("capcut")


def test_bom_in_config_is_tolerated(tmp_path, monkeypatch):
    cfg = tmp_path / "apps.json"
    cfg.write_text('{"browser": "x"}', encoding="utf-8-sig")
    monkeypatch.setattr(desktop, "CONFIG_PATH", cfg)
    assert desktop._load_config() == {"browser": "x"}
