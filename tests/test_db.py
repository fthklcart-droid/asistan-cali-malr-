import sqlite3
import threading

from app import approvals, audit, db, device, history


def test_migrations_are_idempotent_and_versioned(tmp_path):
    path = tmp_path / "x.db"
    assert db.migrate(path) == db.SCHEMA_VERSION
    assert db.migrate(path) == db.SCHEMA_VERSION  # ikinci çalıştırma bir şey yapmaz
    conn = sqlite3.connect(path)
    tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"events", "memory", "todos", "reminders", "tasks", "task_events", "approvals",
            "audit_log", "usage", "settings", "workspaces", "schema_version"} <= tables
    assert [r[0] for r in conn.execute("SELECT version FROM schema_version ORDER BY version")] == \
        [v for v, _, _ in db.MIGRATIONS]


def test_legacy_files_are_imported(tmp_path, monkeypatch):
    old = sqlite3.connect(tmp_path / "hafiza.db")
    old.execute("CREATE TABLE hafiza (id INTEGER PRIMARY KEY, ts TEXT, kategori TEXT, bilgi TEXT)")
    old.execute("INSERT INTO hafiza (ts, kategori, bilgi) VALUES ('2026-10-01T10:00:00', 'tercih', 'Çay severim')")
    old.commit(); old.close()
    old = sqlite3.connect(tmp_path / "planlayici.db")
    old.execute("CREATE TABLE gorev (id INTEGER PRIMARY KEY, metin TEXT, durum TEXT, tarih TEXT, olusturma TEXT)")
    old.execute("INSERT INTO gorev (metin, durum, tarih, olusturma) VALUES ('Eski görev', 'bekliyor', '', 'x')")
    old.commit(); old.close()
    monkeypatch.setattr(db, "DB_PATH", tmp_path / "jarvis.db")
    from app.beceriler import hafiza, planlayici
    assert hafiza.tumu()[0]["bilgi"] == "Çay severim"
    assert planlayici.gorev_listele()["gorevler"][0]["metin"] == "Eski görev"
    assert (tmp_path / "hafiza.db.imported").exists() and not (tmp_path / "hafiza.db").exists()


def test_failed_migration_rolls_back(tmp_path, monkeypatch):
    def broken(conn):
        conn.execute("CREATE TABLE half_done (x INTEGER)")
        raise RuntimeError("boom")
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS + [(99, "bozuk", broken)])
    path = tmp_path / "y.db"
    try:
        db.migrate(path)
    except RuntimeError:
        pass
    conn = sqlite3.connect(path)
    assert conn.execute("SELECT name FROM sqlite_master WHERE name='half_done'").fetchone() is None
    assert conn.execute("SELECT MAX(version) FROM schema_version").fetchone()[0] == db.SCHEMA_VERSION


def test_settings_and_backup(tmp_path):
    db.set_setting("a", "1"); db.set_setting("a", "2")
    assert db.get_setting("a") == "2" and db.get_setting("yok", "x") == "x"
    history.log("test", "yedek")
    copy = db.backup(tmp_path / "yedek" / "jarvis.db")
    assert sqlite3.connect(copy).execute("SELECT COUNT(*) FROM events").fetchone()[0] == 1


def test_audit_chain_detects_tampering():
    for i in range(5):
        audit.record("deneme", f"hedef-{i}", {"n": i})
    assert audit.verify() == {"ok": True, "entries": 5, "broken_at": None}
    with db.session() as conn:
        conn.execute("UPDATE audit_log SET data = '{\"n\": 99}' WHERE id = 3")
    result = audit.verify()
    assert result["ok"] is False and result["broken_at"] == 3


def test_audit_chain_detects_deletion():
    for i in range(4):
        audit.record("deneme", str(i))
    with db.session() as conn:
        conn.execute("DELETE FROM audit_log WHERE id = 2")
    assert audit.verify()["broken_at"] == 3


def test_approvals_persist_and_cannot_be_decided_twice():
    item = approvals.create({"action": "launch_app", "app_id": "browser"}, summary="tarayıcı")
    assert approvals.list_pending()[0]["id"] == item["id"]
    results = []
    threads = [threading.Thread(target=lambda: results.append(approvals.decide(item["id"], True))) for _ in range(8)]
    for t in threads: t.start()
    for t in threads: t.join()
    assert sum(1 for r in results if r) == 1  # yarışta yalnız bir karar kazanır
    done = approvals.finish(item["id"], result="açıldı")
    assert done["status"] == "executed" and approvals.list_pending() == []
    other = approvals.create({"action": "launch_app", "app_id": "capcut"})
    assert approvals.expire_all() == 1 and approvals.get(other["id"])["status"] == "expired"


def test_device_identity_is_stable():
    first = device.token()
    device.reset_cache()
    assert device.token() == first and len(first) >= 40
    assert device.check(first) and not device.check("yanlış") and not device.check(None)
