"""Push-to-talk Turkish Windows voice commands. Uses Google Web Speech for transcription."""
from __future__ import annotations

import requests
from PySide6.QtCore import QThread, Signal

from .config import base_url

ALIASES = {
    "photoshop": ("photoshop", "fotoşop", "foto shop", "fotoşopu", "photoshopu", "fotoşap"),
    "premiere": ("premiere", "premiyer", "premier", "prömiyer"),
    "lightroom": ("lightroom", "laytrum", "light room", "laytrım", "lightroomu"),
    "capcut": ("capcut", "cap cut", "cap kat", "kepkat", "kapkat", "capcutu"),
    "browser": ("tarayıcı", "chrome", "krom", "google chrome", "internet", "edge"),
}


def turkish_lower(text: str) -> str:
    """Türkçe'ye uygun küçük harf: 'İ' -> 'i', 'I' -> 'ı' (str.lower() 'İ'yi 'i̇' yapar)."""
    return text.replace("İ", "i").replace("I", "ı").lower().strip()


def match_app(text: str) -> str | None:
    """Duyulan cümleden izinli uygulama kimliğini bulur."""
    normalized = turkish_lower(text)
    for key, words in ALIASES.items():
        if any(word in normalized for word in words):
            return key
    return None


class WindowsVoiceCommandWorker(QThread):
    status = Signal(str)
    transcript = Signal(str)
    error = Signal(str)

    def run(self):
        try:
            import sounddevice as sd
            import speech_recognition as sr
        except Exception as exc:
            self.error.emit(f"Ses paketleri yüklenemedi ({exc}). setup_windows.bat dosyasını yeniden çalıştırın.")
            return
        try:
            self.status.emit("Mikrofon açık — 4 saniye konuşun…")
            try:
                audio = sd.rec(int(4 * 16000), samplerate=16000, channels=1, dtype="int16")
                sd.wait()
            except Exception as exc:
                self.error.emit(
                    f"Mikrofon açılamadı: {exc}\n\nWindows Ayarlar > Gizlilik ve güvenlik > Mikrofon "
                    "bölümünden masaüstü uygulamalarının mikrofona erişimini açın."
                )
                return
            recognizer = sr.Recognizer()
            data = sr.AudioData(audio.tobytes(), 16000, 2)
            self.status.emit("Türkçe konuşma tanınıyor…")
            text = recognizer.recognize_google(data, language="tr-TR")
            self.transcript.emit("Duyulan komut: " + text)
            app_id = match_app(text)
            if not app_id:
                self.status.emit("Komut anlaşıldı; izinli uygulama adı bulunamadı. Görevi metin alanına yazın.")
                return
            payload = {"action": "launch_app", "app_id": app_id, "description": f"Sesli komutla {app_id} uygulamasını aç"}
            response = requests.post(base_url() + "/actions/request", json=payload, timeout=8)
            response.raise_for_status()
            self.status.emit(f"{app_id} için onay isteği oluşturuldu. Açmak için listeden seçip onaylayın.")
        except sr.UnknownValueError:
            self.error.emit("Konuşma anlaşılamadı. Daha net ve kısa tekrar deneyin.")
        except sr.RequestError as exc:
            self.error.emit(f"Türkçe ses tanıma servisine ulaşılamadı: {exc}. İnternet bağlantısını kontrol edin.")
        except requests.RequestException as exc:
            self.error.emit(f"Yerel köprüye ulaşılamadı: {exc}")
        except Exception as exc:
            self.error.emit(f"Sesli komut hatası: {exc}")
