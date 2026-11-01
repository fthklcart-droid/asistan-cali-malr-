"""Görsel inceleme ve fatura okuma (Claude görüntü anlama)."""
from __future__ import annotations

import base64
import csv
import io
import os
from datetime import datetime
from pathlib import Path

import anthropic
from pydantic import BaseModel, Field

from ..claude_client import DEFAULT_MODEL
from ..config import ROOT, load_env
from .dosyalar import guvenli_yol

INVOICE_DIR = ROOT / "data" / "faturalar"
MEDIA = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp"}
MAX_EDGE = 2000


def _client() -> anthropic.Anthropic:
    load_env()
    key = os.getenv("ANTHROPIC_API_KEY", "").strip()
    if not key:
        raise ValueError("ANTHROPIC_API_KEY ayarlı değil.")
    return anthropic.Anthropic(api_key=key, timeout=180.0)


def _model() -> str:
    return os.getenv("ANTHROPIC_MODEL", "").strip() or DEFAULT_MODEL


def _image_block(yol: str) -> dict:
    p = guvenli_yol(yol)
    if not p.is_file():
        raise ValueError(f"Görsel bulunamadı: {p}")
    if p.suffix.lower() not in MEDIA and p.suffix.lower() not in (".bmp", ".tif", ".tiff", ".heic"):
        raise ValueError("Desteklenen görseller: png, jpg, webp, gif, bmp, tif")
    from PIL import Image
    img = Image.open(p)
    img = img.convert("RGB")
    if max(img.size) > MAX_EDGE:  # API boyut sınırını aşmamak için küçült
        s = MAX_EDGE / max(img.size)
        img = img.resize((round(img.width * s), round(img.height * s)))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=90)
    return {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                                        "data": base64.standard_b64encode(buf.getvalue()).decode()}}


def incele(yol: str, soru: str = "") -> dict:
    prompt = soru.strip() or "Bu görselde ne olduğunu Türkçe ve ayrıntılı açıkla. Görseldeki yazıları da aktar."
    resp = _client().messages.create(
        model=_model(), max_tokens=4000,
        messages=[{"role": "user", "content": [_image_block(yol), {"type": "text", "text": prompt}]}],
    )
    if resp.stop_reason == "refusal":
        return {"aciklama": "Görsel güvenlik nedeniyle incelenmedi."}
    return {"aciklama": "".join(b.text for b in resp.content if b.type == "text")}


class FaturaSatiri(BaseModel):
    urun: str = Field(description="Ürün/hizmet adı, faturada yazdığı gibi")
    miktar: float
    birim: str = Field(description="adet, kg, lt, paket vb.")
    birim_fiyat: float
    kdv_orani: float = Field(description="Yüzde olarak KDV oranı; yoksa 0")
    tutar: float


class Fatura(BaseModel):
    satici: str
    fatura_no: str
    tarih: str = Field(description="YYYY-AA-GG; okunamazsa boş")
    para_birimi: str
    satirlar: list[FaturaSatiri]
    ara_toplam: float
    kdv_toplam: float
    genel_toplam: float
    notlar: str = Field(description="Okunamayan veya şüpheli kısımlar")


def fatura_oku(yol: str) -> dict:
    """Fatura/fiş fotoğrafını kalemlere ayırır, data/faturalar altına CSV olarak kaydeder."""
    resp = _client().messages.parse(
        model=_model(), max_tokens=16000,
        messages=[{"role": "user", "content": [
            _image_block(yol),
            {"type": "text", "text": "Bu faturayı/fişi oku ve tüm kalemleri çıkar. Sayıları nokta ondalıklı ver. "
                                     "Okuyamadığın değerleri tahmin etme; 0 yaz ve notlar alanında belirt."}]}],
        output_format=Fatura,
    )
    if resp.stop_reason == "refusal" or resp.parsed_output is None:
        raise ValueError("Fatura okunamadı.")
    f: Fatura = resp.parsed_output
    INVOICE_DIR.mkdir(parents=True, exist_ok=True)
    stem = Path(yol).stem[:40]
    out = INVOICE_DIR / f"{datetime.now():%Y%m%d_%H%M%S}_{stem}.csv"
    with out.open("w", encoding="utf-8-sig", newline="") as fh:  # BOM: Excel Türkçe karakterleri doğru açar
        w = csv.writer(fh, delimiter=";")
        w.writerow(["Ürün", "Miktar", "Birim", "Birim Fiyat", "KDV %", "Tutar"])
        for s in f.satirlar:
            w.writerow([s.urun, s.miktar, s.birim, s.birim_fiyat, s.kdv_orani, s.tutar])
        w.writerow([]); w.writerow(["Ara toplam", "", "", "", "", f.ara_toplam])
        w.writerow(["KDV", "", "", "", "", f.kdv_toplam]); w.writerow(["Genel toplam", "", "", "", "", f.genel_toplam])
    toplam = round(sum(s.tutar for s in f.satirlar), 2)
    uyari = "" if abs(toplam - f.ara_toplam) < 0.05 or not f.ara_toplam else \
        f"Kalem toplamı ({toplam}) ara toplamla ({f.ara_toplam}) uyuşmuyor; kontrol edin."
    return {"csv": str(out), "satici": f.satici, "fatura_no": f.fatura_no, "tarih": f.tarih,
            "kalem_sayisi": len(f.satirlar), "genel_toplam": f.genel_toplam, "para_birimi": f.para_birimi,
            "notlar": f.notlar, "uyari": uyari}
