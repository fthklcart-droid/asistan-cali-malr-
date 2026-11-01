"""Photoshop otomasyonu (Windows COM + ExtendScript).

Yalnızca aşağıdaki sabit, güvenli komutlar çalıştırılabilir; serbest betik çalıştırılmaz.
Photoshop'un açık olması gerekmez; COM gerekirse başlatır.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

COMMANDS = {
    "info": "Etkin belge bilgisini göster",
    "open": "Dosya aç",
    "auto_tone": "Otomatik ton + otomatik kontrast (etkin katman)",
    "resize_long_edge": "Uzun kenarı verilen piksele göre yeniden boyutlandır",
    "export_jpeg": "JPEG olarak dışa aktar (orijinal dosyaya dokunmaz)",
    "export_png": "PNG olarak dışa aktar (orijinal dosyaya dokunmaz)",
}


class PhotoshopError(RuntimeError):
    pass


def _js_str(value: str) -> str:
    return json.dumps(str(value))  # JS dize sabiti olarak güvenli kaçış


def build_script(command: str, path: str = "", value: int = 0) -> str:
    """Komut için ExtendScript üretir (test edilebilir, saf fonksiyon)."""
    if command not in COMMANDS:
        raise PhotoshopError(f"Bilinmeyen Photoshop komutu: {command}")
    need_doc = "if (app.documents.length === 0) { 'HATA: Açık belge yok.'; } else { var d = app.activeDocument; "
    if command == "info":
        return need_doc + ("d.name + ' | ' + Math.round(d.width.as('px')) + 'x' + Math.round(d.height.as('px')) "
                           "+ ' px | ' + d.resolution + ' dpi | katman: ' + d.artLayers.length; }")
    if command == "open":
        if not path or not Path(path).is_file():
            raise PhotoshopError(f"Dosya bulunamadı: {path}")
        return f"var f = new File({_js_str(Path(path).as_posix())}); app.open(f); 'Açıldı: ' + f.name;"
    if command == "auto_tone":
        return need_doc + "d.activeLayer.autoLevels(); d.activeLayer.autoContrast(); 'Otomatik ton ve kontrast uygulandı.'; }"
    if command == "resize_long_edge":
        if not 16 <= int(value) <= 30000:
            raise PhotoshopError("Piksel değeri 16-30000 arasında olmalı.")
        return need_doc + (
            f"var t = {int(value)}; var w = d.width.as('px'), h = d.height.as('px'); "
            "if (w >= h) { d.resizeImage(UnitValue(t, 'px'), null, null, ResampleMethod.BICUBICAUTOMATIC); } "
            "else { d.resizeImage(null, UnitValue(t, 'px'), null, ResampleMethod.BICUBICAUTOMATIC); } "
            "'Yeni boyut: ' + Math.round(d.width.as('px')) + 'x' + Math.round(d.height.as('px')); }")
    if command in ("export_jpeg", "export_png"):
        if not path:
            raise PhotoshopError("Dışa aktarma için hedef dosya yolu gerekli.")
        ext = ".jpg" if command == "export_jpeg" else ".png"
        target = Path(path)
        if target.suffix.lower() not in (ext, ".jpeg" if ext == ".jpg" else ext):
            target = target.with_suffix(ext)
        if target.exists():
            raise PhotoshopError(f"Hedef dosya zaten var (üzerine yazılmaz): {target}")
        if command == "export_jpeg":
            opts = "var o = new JPEGSaveOptions(); o.quality = 11; o.embedColorProfile = true;"
        else:
            opts = "var o = new PNGSaveOptions();"
        return need_doc + (f"{opts} d.saveAs(new File({_js_str(target.as_posix())}), o, true, Extension.LOWERCASE); "
                           f"'Dışa aktarıldı: ' + {_js_str(str(target))}; }}")
    raise PhotoshopError(command)


def run(command: str, path: str = "", value: int = 0) -> str:
    script = build_script(command, path, value)
    if sys.platform != "win32":
        raise PhotoshopError("Photoshop otomasyonu yalnızca Windows'ta çalışır.")
    try:
        import pythoncom
        import win32com.client
    except ImportError as exc:
        raise PhotoshopError("pywin32 paketi eksik. setup_windows.bat'ı yeniden çalıştırın.") from exc
    pythoncom.CoInitialize()
    try:
        try:
            app = win32com.client.Dispatch("Photoshop.Application")
        except Exception as exc:
            raise PhotoshopError(f"Photoshop'a bağlanılamadı (kurulu mu?): {exc}") from exc
        result = str(app.DoJavaScript(script))
    finally:
        pythoncom.CoUninitialize()
    if result.startswith("HATA:"):
        raise PhotoshopError(result[5:].strip())
    return result
