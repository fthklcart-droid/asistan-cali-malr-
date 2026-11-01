"""Saat/tarih ve güvenli hesap makinesi."""
from __future__ import annotations

import ast
import math
import operator
import re
from datetime import datetime

GUNLER = ["Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar"]
AYLAR = ["Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"]


def simdi() -> dict:
    n = datetime.now().astimezone()
    return {
        "tarih_saat": n.isoformat(timespec="seconds"),
        "okunur": f"{n.day} {AYLAR[n.month - 1]} {n.year} {GUNLER[n.weekday()]}, saat {n:%H:%M}",
        "saat_dilimi": str(n.tzinfo),
    }


_OPS = {
    ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv, ast.Mod: operator.mod, ast.Pow: operator.pow,
    ast.USub: operator.neg, ast.UAdd: operator.pos,
}
_FUNCS = {"sqrt": math.sqrt, "kok": math.sqrt, "abs": abs, "round": round, "yuvarla": round,
          "log": math.log, "log10": math.log10, "sin": math.sin, "cos": math.cos, "tan": math.tan,
          "min": min, "max": max}
_CONSTS = {"pi": math.pi, "e": math.e}


def _eval(node):
    if isinstance(node, ast.Expression):
        return _eval(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in _OPS:
        left, right = _eval(node.left), _eval(node.right)
        if isinstance(node.op, ast.Pow) and abs(right) > 1000:
            raise ValueError("Üs çok büyük.")
        return _OPS[type(node.op)](left, right)
    if isinstance(node, ast.UnaryOp) and type(node.op) in _OPS:
        return _OPS[type(node.op)](_eval(node.operand))
    if isinstance(node, ast.Name) and node.id in _CONSTS:
        return _CONSTS[node.id]
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in _FUNCS and not node.keywords:
        return _FUNCS[node.func.id](*[_eval(a) for a in node.args])
    raise ValueError("İzin verilmeyen ifade.")


def hesapla(ifade: str) -> dict:
    """Dört işlem, üs, yüzde (%), parantez ve temel fonksiyonlar. Türkçe ondalık virgülü kabul eder."""
    text = str(ifade).strip().replace("×", "*").replace("÷", "/").replace("^", "**")
    if len(text) > 300:
        raise ValueError("İfade çok uzun.")
    # Türkçe biçim yalnız virgül varsa uygulanır: "1.234,5" -> "1234.5" ("3.141" olduğu gibi kalır)
    if re.search(r"\d,\d", text):
        text = re.sub(r"(\d)\.(\d{3})(?!\d)", r"\1\2", text)
        text = re.sub(r"(\d),(\d)", r"\1.\2", text)
    # Yüzde: "%18" (işleçten sonra) ve "18%" (ardından sayı gelmiyorsa) -> (18/100); "10 % 3" mod olarak kalır
    text = re.sub(r"(^|[*+/(-])\s*%(\d+(?:\.\d+)?)", r"\1(\2/100)", text)
    text = re.sub(r"(\d+(?:\.\d+)?)%(?!\s*[\d(])", r"(\1/100)", text)
    result = _eval(ast.parse(text, mode="eval"))
    if isinstance(result, float) and result.is_integer() and abs(result) < 1e15:
        result = int(result)
    return {"ifade": ifade, "sonuc": result}
