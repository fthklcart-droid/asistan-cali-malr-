"""Politika motoru: her eylem ve model çağrısı çalışmadan önce buradan geçer.

Kararlar:
  allow   — doğrudan çalışabilir (yalnızca okuma / model çağrısı)
  approve — kullanıcı onayı şart (dış dünyaya veya bilgisayara etki eden her eylem)
  deny    — çalıştırılamaz (acil durdurma, bilinmeyen eylem, günlük sınır, bütçe)

Kural: Bu motor hiçbir eylemi onaysız "allow" etmez; eylemler için en iyi sonuç "approve"dur.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

from . import control, db, usage

# Eylem türü → risk sınıfı
ACTION_RISK = {
    "launch_app": "local",        # bilgisayarda program başlatır
    "photoshop": "local",         # yerel dosya açar/dışa aktarır
    "whatsapp_send": "external",  # başka bir kişiye mesaj gider
    "youtube_upload": "external", # internete içerik yükler
}
# Günlük üst sınırlar (ayarlar tablosunda "policy.limit.<tür>" ile değiştirilebilir; 0 = sınırsız)
DEFAULT_DAILY_LIMITS = {"whatsapp_send": 50, "youtube_upload": 10}


@dataclass
class Decision:
    effect: str
    risk: str
    reason: str = ""

    @property
    def allowed(self) -> bool:
        return self.effect != "deny"

    def as_dict(self) -> dict:
        return asdict(self)


def daily_limit(kind: str) -> int:
    raw = db.get_setting(f"policy.limit.{kind}", "")
    try:
        return int(raw) if raw != "" else DEFAULT_DAILY_LIMITS.get(kind, 0)
    except ValueError:
        return DEFAULT_DAILY_LIMITS.get(kind, 0)


def executed_today(kind: str) -> int:
    with db.session(write=False) as conn:
        return conn.execute("SELECT COUNT(*) FROM approvals WHERE kind = ? AND status = 'executed' "
                            "AND substr(decided_at,1,10) = date('now','localtime')", (kind,)).fetchone()[0]


def evaluate_action(action: dict) -> Decision:
    kind = str(action.get("action", ""))
    risk = ACTION_RISK.get(kind)
    if risk is None:
        return Decision("deny", "unknown", f"Bilinmeyen eylem türü: {kind}")
    if control.engaged():
        return Decision("deny", risk, "Acil durdurma etkin. Önce Genel Bakış'tan kaldırın.")
    limit = daily_limit(kind)
    if limit and executed_today(kind) >= limit:
        return Decision("deny", risk, f"Bugün için {kind} sınırına ({limit}) ulaşıldı.")
    reason = "Dış dünyaya etki eder; açık onay gerekir." if risk == "external" else "Bilgisayarda işlem yapar; onay gerekir."
    if kind == "youtube_upload" and action.get("privacy") == "public":
        reason = "Video HERKESE AÇIK yayınlanacak; dikkatle onaylayın."
    return Decision("approve", risk, reason)


def evaluate_model_call(purpose: str) -> Decision:
    if control.engaged():
        return Decision("deny", "model", "Acil durdurma etkin.")
    try:
        usage.check_budget()
    except usage.BudgetExceeded as exc:
        return Decision("deny", "model", str(exc))
    return Decision("allow", "model", purpose)


class PolicyDenied(RuntimeError):
    pass


def require_model_call(purpose: str) -> None:
    d = evaluate_model_call(purpose)
    if not d.allowed:
        raise PolicyDenied(d.reason)
