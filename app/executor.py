"""Yerel bilgisayar yürütücüsü: ekran görüntüsü, fare ve klavye.

Claude Computer Use toolset (computer_toolset_20260801) üyelerini Windows üzerinde uygular.
Claude, küçültülmüş ekran görüntüsünün piksel uzayında koordinat verir; burada gerçek
ekran koordinatına ölçeklenir.
"""
from __future__ import annotations

import base64
import io
import math
import sys
import time

# Claude'a gönderilecek ekran görüntüsü üst sınırı (önerilen ~1366x768 seviyesinde tutulur).
MAX_LONG_EDGE = 1366
MAX_PIXELS = 1366 * 768

KEY_ALIASES = {
    "return": "enter", "enter": "enter", "escape": "esc", "esc": "esc", "ctrl": "ctrl", "control": "ctrl",
    "cmd": "win", "super": "win", "meta": "win", "win": "win", "windows": "win", "alt": "alt", "shift": "shift",
    "backspace": "backspace", "delete": "delete", "del": "delete", "tab": "tab", "space": "space",
    "page_up": "pageup", "pageup": "pageup", "prior": "pageup", "page_down": "pagedown", "pagedown": "pagedown",
    "next": "pagedown", "home": "home", "end": "end", "insert": "insert", "up": "up", "down": "down",
    "left": "left", "right": "right", "arrowup": "up", "arrowdown": "down", "arrowleft": "left",
    "arrowright": "right", "caps_lock": "capslock", "print": "printscreen", "minus": "-", "plus": "+",
    "equal": "=", "comma": ",", "period": ".", "slash": "/", "backslash": "\\", "semicolon": ";",
}

_dpi_done = False


def make_dpi_aware() -> None:
    """Windows ölçeklendirmesinde (%125, %150) koordinatların kaymaması için."""
    global _dpi_done
    if _dpi_done or sys.platform != "win32":
        return
    _dpi_done = True
    try:
        import ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass


def scale_factor(width: int, height: int) -> float:
    return min(1.0, MAX_LONG_EDGE / max(width, height), math.sqrt(MAX_PIXELS / (width * height)))


def normalize_key(combo: str) -> list[str]:
    parts = [p.strip() for p in combo.replace(" ", "").split("+") if p.strip()]
    out = []
    for p in parts:
        low = p.lower()
        out.append(KEY_ALIASES.get(low, low if len(low) > 1 else p.lower()))
    return out


class ActionError(Exception):
    pass


class LocalExecutor:
    """pyautogui + mss tabanlı yürütücü. Testlerde sahte bir sınıfla değiştirilebilir."""

    def __init__(self):
        make_dpi_aware()
        try:
            import mss  # noqa: F401
            import pyautogui
        except Exception as exc:
            raise RuntimeError(f"Ekran kontrol paketleri yüklenemedi: {exc}. setup_windows.bat'ı yeniden çalıştırın.") from exc
        self.pg = pyautogui
        pyautogui.FAILSAFE = True  # fareyi ekranın sol üst köşesine götürmek acil durdurmadır
        pyautogui.PAUSE = 0.05
        self.screen_w, self.screen_h = pyautogui.size()
        self.scale = scale_factor(self.screen_w, self.screen_h)

    # --- koordinat dönüşümü ---
    def to_screen(self, coord) -> tuple[int, int]:
        if not coord or len(coord) != 2:
            raise ActionError("Geçersiz koordinat.")
        x = round(coord[0] / self.scale)
        y = round(coord[1] / self.scale)
        return max(0, min(self.screen_w - 1, x)), max(0, min(self.screen_h - 1, y))

    def to_model(self, x: int, y: int) -> tuple[int, int]:
        return round(x * self.scale), round(y * self.scale)

    # --- ekran ---
    def _grab(self, region=None):
        import mss
        from PIL import Image
        with mss.mss() as sct:
            mon = sct.monitors[1]
            if region:
                left, top, right, bottom = region
                mon = {"left": mon["left"] + left, "top": mon["top"] + top,
                       "width": max(1, right - left), "height": max(1, bottom - top)}
            shot = sct.grab(mon)
            return Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")

    @staticmethod
    def _png_b64(img) -> str:
        buf = io.BytesIO()
        img.save(buf, format="PNG", optimize=True)
        return base64.standard_b64encode(buf.getvalue()).decode()

    def screenshot(self) -> str:
        from PIL import Image
        img = self._grab()
        w = round(img.width * self.scale)
        h = round(img.height * self.scale)
        if (w, h) != img.size:
            img = img.resize((w, h), Image.LANCZOS)
        return self._png_b64(img)

    def zoom(self, region) -> str:
        from PIL import Image
        if not region or len(region) != 4:
            raise ActionError("zoom için region [x0, y0, x1, y1] gerekli.")
        x0, y0 = self.to_screen(region[:2])
        x1, y1 = self.to_screen(region[2:])
        img = self._grab((min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)))
        s = scale_factor(img.width, img.height)
        if s < 1:
            img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
        return self._png_b64(img)

    # --- eylemler ---
    def _with_modifiers(self, text, fn):
        mods = normalize_key(text) if text else []
        for m in mods:
            self.pg.keyDown(m)
        try:
            fn()
        finally:
            for m in reversed(mods):
                self.pg.keyUp(m)

    def run(self, name: str, args: dict) -> dict:
        """Bir toolset üyesini çalıştırır. Dönüş: {"text": ...} veya {"image": base64png}."""
        pg = self.pg
        coord = args.get("coordinate")
        if name == "screenshot":
            return {"image": self.screenshot()}
        if name == "zoom":
            return {"image": self.zoom(args.get("region"))}
        if name == "cursor_position":
            x, y = self.to_model(*pg.position())
            return {"text": f"X={x}, Y={y}"}
        if name in ("left_click", "right_click", "middle_click", "double_click", "triple_click"):
            pos = self.to_screen(coord) if coord else None
            button = {"right_click": "right", "middle_click": "middle"}.get(name, "left")
            clicks = {"double_click": 2, "triple_click": 3}.get(name, 1)

            def click():
                if pos:
                    pg.click(pos[0], pos[1], clicks=clicks, interval=0.08, button=button)
                else:
                    pg.click(clicks=clicks, interval=0.08, button=button)
            self._with_modifiers(args.get("text"), click)
            return {"text": "OK"}
        if name == "left_click_drag":
            sx, sy = self.to_screen(args.get("start_coordinate"))
            ex, ey = self.to_screen(coord)

            def drag():
                pg.moveTo(sx, sy)
                pg.mouseDown()
                pg.moveTo(ex, ey, duration=0.4)
                pg.mouseUp()
            self._with_modifiers(args.get("text"), drag)
            return {"text": "OK"}
        if name == "mouse_move":
            x, y = self.to_screen(coord)
            pg.moveTo(x, y, duration=0.15)
            return {"text": "OK"}
        if name == "left_mouse_down":
            pg.mouseDown()
            return {"text": "OK"}
        if name == "left_mouse_up":
            pg.mouseUp()
            return {"text": "OK"}
        if name == "scroll":
            amount = int(args.get("scroll_amount") or 3)
            direction = args.get("scroll_direction", "down")
            pos = self.to_screen(coord) if coord else None

            def scroll():
                if pos:
                    pg.moveTo(*pos)
                if direction in ("up", "down"):
                    pg.scroll(120 * amount * (1 if direction == "up" else -1))
                else:
                    pg.hscroll(120 * amount * (1 if direction == "right" else -1))
            self._with_modifiers(args.get("text"), scroll)
            return {"text": "OK"}
        if name == "type":
            self.type_text(str(args.get("text", "")))
            return {"text": "OK"}
        if name == "key":
            keys = normalize_key(str(args.get("text", "")))
            if not keys:
                raise ActionError("Tuş belirtilmedi.")
            for _ in range(max(1, min(100, int(args.get("repeat") or 1)))):
                pg.hotkey(*keys)
            return {"text": "OK"}
        if name == "hold_key":
            keys = normalize_key(str(args.get("text", "")))
            duration = min(300.0, float(args.get("duration") or 1))
            for k in keys:
                pg.keyDown(k)
            try:
                time.sleep(duration)
            finally:
                for k in reversed(keys):
                    pg.keyUp(k)
            return {"text": "OK"}
        if name == "wait":
            time.sleep(min(300.0, float(args.get("duration") or 1)))
            return {"text": "OK"}
        raise ActionError(f"Desteklenmeyen eylem: {name}")

    def type_text(self, text: str) -> None:
        """Metni panoya kopyalayıp Ctrl+V ile yapıştırır.

        pyautogui.write ABD klavye düzenini varsayar; Türkçe Q klavyede @, ", ; gibi işaretleri yanlış
        yazar ve ç, ğ, ı, ö, ş, ü hiç yazamaz. Yapıştırma her klavye düzeninde doğru çalışır.
        """
        if not text:
            return
        try:
            import pyperclip  # pyautogui ile birlikte gelir; iş parçacığından güvenle çağrılabilir
            previous = pyperclip.paste()
        except Exception:
            self.pg.write(text, interval=0.01)
            return
        pyperclip.copy(text)
        time.sleep(0.05)
        self.pg.hotkey("ctrl", "v")
        time.sleep(0.2)
        try:
            pyperclip.copy(previous or "")
        except Exception:
            pass
