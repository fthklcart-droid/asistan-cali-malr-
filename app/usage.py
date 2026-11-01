"""Model kullanımı, maliyet tahmini ve günlük bütçe.

Fiyatlar USD / 1M token (Anthropic birinci taraf API fiyatları). Bilinmeyen model için maliyet None
yazılır (token sayıları yine kaydedilir). Web araması: 1000 aramada 10 USD.
"""
from __future__ import annotations

import os
from datetime import date, timedelta

from . import db

# model: (girdi, çıktı, önbellek okuma, önbellek yazma [5 dk])
PRICES: dict[str, tuple[float, float, float, float]] = {
    "claude-opus-5-5": (4.00, 20.00, 0.20, 5.00),
    "claude-opus-5": (5.00, 25.00, 0.50, 6.25),
    "claude-sonnet-5-5": (2.00, 10.00, 0.20, 2.50),
    "claude-sonnet-5": (2.00, 10.00, 0.20, 2.50),
    "claude-haiku-5-5": (0.10, 0.50, 0.01, 0.125),
    "claude-fable-5-1": (10.00, 50.00, 0.25, 12.50),
    "claude-opus-4-8": (5.00, 25.00, 0.50, 6.25),
}
WEB_SEARCH_USD = 0.01


class BudgetExceeded(RuntimeError):
    pass


def _field(obj, name, default=0):
    value = getattr(obj, name, None) if obj is not None else None
    if value is None and isinstance(obj, dict):
        value = obj.get(name)
    return value if value is not None else default


def estimate_cost(model: str, inp: int, out: int, cache_read: int = 0, cache_write: int = 0,
                  web_searches: int = 0) -> float | None:
    price = PRICES.get(model)
    if price is None:
        # Sürüm son ekli kimlikler (ör. "claude-opus-5-5-xxxx") için önek eşleşmesi
        price = next((p for m, p in PRICES.items() if model.startswith(m)), None)
    if price is None:
        return None
    pi, po, pr, pw = price
    return round((inp * pi + out * po + cache_read * pr + cache_write * pw) / 1_000_000 + web_searches * WEB_SEARCH_USD, 6)


def record(model: str, purpose: str, usage_obj, provider: str = "anthropic") -> dict:
    """SDK'nın `response.usage` nesnesinden (veya sözlükten) kullanım kaydı oluşturur."""
    inp = int(_field(usage_obj, "input_tokens"))
    out = int(_field(usage_obj, "output_tokens"))
    cr = int(_field(usage_obj, "cache_read_input_tokens"))
    cw = int(_field(usage_obj, "cache_creation_input_tokens"))
    stu = _field(usage_obj, "server_tool_use", None)
    searches = int(_field(stu, "web_search_requests")) if stu else 0
    cost = estimate_cost(model, inp, out, cr, cw, searches)
    with db.session() as conn:
        conn.execute(
            "INSERT INTO usage (ts, provider, model, purpose, input_tokens, output_tokens, cache_read_tokens, "
            "cache_write_tokens, web_searches, cost_usd) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (db.now(), provider, model, purpose, inp, out, cr, cw, searches, cost))
    return {"model": model, "input_tokens": inp, "output_tokens": out, "cost_usd": cost}


def daily_budget() -> float:
    raw = db.get_setting("usage.daily_budget_usd", "") or os.getenv("JARVIS_DAILY_BUDGET_USD", "") or "0"
    try:
        return max(0.0, float(str(raw).replace(",", ".")))
    except ValueError:
        return 0.0


def set_daily_budget(value: float) -> None:
    db.set_setting("usage.daily_budget_usd", str(max(0.0, float(value))))


def summary(day: date | None = None) -> dict:
    day = day or date.today()
    with db.session(write=False) as conn:
        r = conn.execute(
            "SELECT COUNT(*), COALESCE(SUM(input_tokens),0), COALESCE(SUM(output_tokens),0), "
            "COALESCE(SUM(cache_read_tokens),0), COALESCE(SUM(web_searches),0), COALESCE(SUM(cost_usd),0) "
            "FROM usage WHERE substr(ts,1,10) = ?", (day.isoformat(),)).fetchone()
    budget = daily_budget()
    cost = round(r[5], 4)
    return {"date": day.isoformat(), "calls": r[0], "input_tokens": r[1], "output_tokens": r[2],
            "cache_read_tokens": r[3], "web_searches": r[4], "cost_usd": cost, "budget_usd": budget,
            "remaining_usd": round(budget - cost, 4) if budget else None,
            "exceeded": bool(budget) and cost >= budget}


def history(days: int = 7) -> list[dict]:
    today = date.today()
    return [summary(today - timedelta(days=i)) for i in range(max(1, min(days, 90)))]


def check_budget() -> None:
    s = summary()
    if s["exceeded"]:
        raise BudgetExceeded(f"Günlük Claude bütçesi doldu ({s['cost_usd']:.2f} / {s['budget_usd']:.2f} USD). "
                             "Ayarlar'dan bütçeyi artırabilir veya yarını bekleyebilirsiniz.")
