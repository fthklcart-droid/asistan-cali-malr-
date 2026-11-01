import json

import pytest

pytest.importorskip("PySide6.QtWidgets")


def test_selftest_passes_and_writes_report(tmp_path):
    from app import main
    report = tmp_path / "rapor.json"
    assert main.cli(["--selftest", str(report)]) == 0
    data = json.loads(report.read_text(encoding="utf-8"))
    assert data["ok"] is True and data["failed"] == []
    assert data["checks"]["bridge"]["ok"] and "görev" in data["checks"]["bridge"]["detail"]
    assert data["checks"]["window"]["detail"] == "10 sayfa"
