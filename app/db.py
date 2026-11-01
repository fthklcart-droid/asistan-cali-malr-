"""FK JARVIS veritabanı: tek SQLite dosyası (data/jarvis.db), sürümlü şema göçleri.

Kullanım:
    from app import db
    with db.session() as conn:
        conn.execute("SELECT ...")

- Her `session()` yeni bir bağlantı açar, işlemi commit eder ve kapatır (iş parçacığı güvenli).
- WAL günlüğü: arayüz ve köprü aynı anda okuyup yazabilir.
- Şema `MIGRATIONS` listesindeki sırayla uygulanır; uygulanan sürüm `schema_version` tablosunda tutulur.
  Var olan bir göç ASLA değiştirilmez; yeni değişiklik yeni bir göç olarak eklenir.
"""
from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterator

from .config import ROOT

DB_PATH: Path = ROOT / "data" / "jarvis.db"

_init_lock = threading.Lock()
_initialized: set[str] = set()


def now() -> str:
    return datetime.now().isoformat(timespec="seconds")


# --------------------------------------------------------------------------- göçler
def _script(conn: sqlite3.Connection, sql: str) -> None:
    """Çok deyimli SQL'i işlem içinde çalıştırır.

    sqlite3.executescript() açık işlemi önce sessizce commit ettiği için göçleri atomik olmaktan çıkarır;
    bu yüzden deyimler tek tek çalıştırılır (göç SQL'inde dize içinde ';' kullanılmaz).
    """
    for statement in sql.split(";"):
        if statement.strip():
            conn.execute(statement)


def _m1_base(conn: sqlite3.Connection) -> None:
    """v1: etkinlik akışı, hafıza, yapılacaklar, hatırlatmalar (önceki ayrı dosyaların şeması)."""
    _script(conn, """
    CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, kind TEXT NOT NULL,
        title TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT '');
    CREATE INDEX IF NOT EXISTS ix_events_ts ON events(ts);
    CREATE TABLE IF NOT EXISTS memory (
        id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, kategori TEXT NOT NULL, bilgi TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS todos (
        id INTEGER PRIMARY KEY AUTOINCREMENT, metin TEXT NOT NULL, durum TEXT NOT NULL DEFAULT 'bekliyor',
        tarih TEXT DEFAULT '', olusturma TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS reminders (
        id INTEGER PRIMARY KEY AUTOINCREMENT, metin TEXT NOT NULL, zaman TEXT NOT NULL,
        gosterildi INTEGER NOT NULL DEFAULT 0);
    CREATE INDEX IF NOT EXISTS ix_reminders_due ON reminders(gosterildi, zaman);
    """)


def _m2_import_legacy(conn: sqlite3.Connection) -> None:
    """v2: eski sürümlerin ayrı dosyalarını (hafiza.db, planlayici.db) içe aktar, dosyaları yeniden adlandır."""
    folder = Path(conn.execute("PRAGMA database_list").fetchone()[2]).parent
    legacy = [
        (folder / "hafiza.db", [("hafiza", "memory", "ts, kategori, bilgi")]),
        (folder / "planlayici.db", [("gorev", "todos", "metin, durum, tarih, olusturma"),
                                    ("hatirlatma", "reminders", "metin, zaman, gosterildi")]),
    ]
    for path, tables in legacy:
        if not path.is_file():
            continue
        old = sqlite3.connect(path)
        try:
            for src, dst, cols in tables:
                try:
                    rows = old.execute(f"SELECT {cols} FROM {src}").fetchall()
                except sqlite3.Error:
                    continue
                marks = ",".join("?" * len(cols.split(",")))
                conn.executemany(f"INSERT INTO {dst} ({cols}) VALUES ({marks})", rows)
        finally:
            old.close()
        path.rename(path.with_suffix(".db.imported"))


def _m3_core(conn: sqlite3.Connection) -> None:
    """v3: çekirdek: görevler, görev zaman çizelgesi, kalıcı onaylar, denetim kaydı, kullanım, ayarlar,
    çalışma klasörleri."""
    _script(conn, """
    CREATE TABLE IF NOT EXISTS tasks (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, instruction TEXT NOT NULL,
        category TEXT NOT NULL DEFAULT 'genel', status TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'ui',
        plan TEXT NOT NULL DEFAULT '', result TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS ix_tasks_status ON tasks(status, updated_at);
    CREATE TABLE IF NOT EXISTS task_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
        ts TEXT NOT NULL, type TEXT NOT NULL, message TEXT NOT NULL DEFAULT '', data TEXT NOT NULL DEFAULT '');
    CREATE INDEX IF NOT EXISTS ix_task_events_task ON task_events(task_id, id);
    CREATE TABLE IF NOT EXISTS approvals (
        id TEXT PRIMARY KEY, task_id TEXT REFERENCES tasks(id) ON DELETE SET NULL,
        kind TEXT NOT NULL, action TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '',
        risk TEXT NOT NULL DEFAULT 'local', status TEXT NOT NULL DEFAULT 'pending',
        created_at TEXT NOT NULL, decided_at TEXT, result TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '');
    CREATE INDEX IF NOT EXISTS ix_approvals_status ON approvals(status, created_at);
    CREATE TABLE IF NOT EXISTS audit_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, actor TEXT NOT NULL, event TEXT NOT NULL,
        target TEXT NOT NULL DEFAULT '', data TEXT NOT NULL DEFAULT '', prev_hash TEXT NOT NULL, hash TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS usage (
        id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL,
        purpose TEXT NOT NULL, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
        cache_read_tokens INTEGER NOT NULL DEFAULT 0, cache_write_tokens INTEGER NOT NULL DEFAULT 0,
        web_searches INTEGER NOT NULL DEFAULT 0, cost_usd REAL);
    CREATE INDEX IF NOT EXISTS ix_usage_ts ON usage(ts);
    CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS workspaces (
        id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL UNIQUE, label TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL);
    """)


MIGRATIONS: list[tuple[int, str, Callable[[sqlite3.Connection], None]]] = [
    (1, "temel tablolar", _m1_base),
    (2, "eski veritabanı dosyalarını içe aktar", _m2_import_legacy),
    (3, "çekirdek: görevler, onaylar, denetim, kullanım, ayarlar, çalışma klasörleri", _m3_core),
]
SCHEMA_VERSION = MIGRATIONS[-1][0]


def _connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=15, isolation_level=None)  # işlemleri elle yönetiriz
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 15000")
    return conn


def migrate(path: Path | None = None) -> int:
    """Eksik göçleri sırayla uygular; uygulanan son sürümü döndürür."""
    path = Path(path or DB_PATH)
    conn = _connect(path)
    try:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, name TEXT, applied_at TEXT)")
        applied = {r[0] for r in conn.execute("SELECT version FROM schema_version")}
        for version, name, fn in MIGRATIONS:
            if version in applied:
                continue
            conn.execute("BEGIN IMMEDIATE")
            try:
                fn(conn)
                conn.execute("INSERT INTO schema_version (version, name, applied_at) VALUES (?, ?, ?)",
                             (version, name, now()))
                conn.execute("COMMIT")
            except BaseException:
                conn.execute("ROLLBACK")
                raise
        return conn.execute("SELECT MAX(version) FROM schema_version").fetchone()[0] or 0
    finally:
        conn.close()


def _ensure(path: Path) -> None:
    key = str(path.resolve())
    if key in _initialized and path.exists():
        return
    with _init_lock:
        if key in _initialized and path.exists():
            return
        migrate(path)
        _initialized.add(key)


@contextmanager
def session(write: bool = True) -> Iterator[sqlite3.Connection]:
    """Bağlantı açar; yazma oturumlarında BEGIN IMMEDIATE ile tek işlem olarak commit eder."""
    path = Path(DB_PATH)
    _ensure(path)
    conn = _connect(path)
    try:
        conn.execute("BEGIN IMMEDIATE" if write else "BEGIN")
        try:
            yield conn
        except BaseException:
            conn.execute("ROLLBACK")
            raise
        conn.execute("COMMIT")
    finally:
        conn.close()


def schema_version() -> int:
    with session(write=False) as conn:
        return conn.execute("SELECT MAX(version) FROM schema_version").fetchone()[0] or 0


# --------------------------------------------------------------------------- ayarlar (anahtar/değer)
def get_setting(key: str, default: str = "") -> str:
    with session(write=False) as conn:
        row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    return row[0] if row else default


def set_setting(key: str, value: str) -> None:
    with session() as conn:
        conn.execute("INSERT INTO settings (key, value) VALUES (?, ?) "
                     "ON CONFLICT(key) DO UPDATE SET value = excluded.value", (key, str(value)))


def backup(target: Path) -> Path:
    """Çalışan veritabanının tutarlı bir kopyasını alır (SQLite online backup)."""
    _ensure(Path(DB_PATH))
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    src = _connect(Path(DB_PATH))
    dst = sqlite3.connect(target)
    try:
        src.backup(dst)
    finally:
        dst.close(); src.close()
    return target
