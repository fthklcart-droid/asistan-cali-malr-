"""Push-to-talk Turkish Windows voice commands. Uses Google Web Speech for transcription."""
from __future__ import annotations

from PySide6.QtCore import QThread, Signal

from . import api_client
from .settings_store import device_arg

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
    unmatched = Signal(str)  # uygulama adı içermeyen komut: Claude'a görev olarak gönderilebilir

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
                audio = sd.rec(int(4 * 16000), samplerate=16000, channels=1, dtype="int16",
                               device=device_arg("AUDIO_INPUT_DEVICE"))
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
                self.status.emit("Komut görev alanına aktarıldı. Claude ile planlayabilir veya bilgisayarda yaptırabilirsiniz.")
                self.unmatched.emit(text)
                return
            payload = {"action": "launch_app", "app_id": app_id, "description": f"Sesli komutla {app_id} uygulamasını aç"}
            api_client.post("/api/v1/approvals", payload, timeout=8)
            self.status.emit(f"{app_id} için onay isteği oluşturuldu. Açmak için listeden seçip onaylayın.")
        except sr.UnknownValueError:
            self.error.emit("Konuşma anlaşılamadı. Daha net ve kısa tekrar deneyin.")
        except sr.RequestError as exc:
            self.error.emit(f"Türkçe ses tanıma servisine ulaşılamadı: {exc}. İnternet bağlantısını kontrol edin.")
        except api_client.ApiError as exc:
            self.error.emit(f"Onay isteği oluşturulamadı: {exc}")
        except Exception as exc:
            self.error.emit(f"Sesli komut hatası: {exc}")


class DictationWorker(QThread):
    """Sohbet için sesli giriş: 6 saniye dinler, Türkçe metne çevirir."""
    status = Signal(str)
    error = Signal(str)
    text_ready = Signal(str)

    def run(self):
        try:
            import sounddevice as sd
            import speech_recognition as sr
        except Exception as exc:
            self.error.emit(f"Ses paketleri yüklenemedi ({exc}).")
            return
        try:
            self.status.emit("🎤 Dinliyorum (6 sn)…")
            audio = sd.rec(int(6 * 16000), samplerate=16000, channels=1, dtype="int16",
                           device=device_arg("AUDIO_INPUT_DEVICE"))
            sd.wait()
            self.status.emit("Yazıya çevriliyor…")
            text = sr.Recognizer().recognize_google(sr.AudioData(audio.tobytes(), 16000, 2), language="tr-TR")
            self.status.emit("")
            self.text_ready.emit(text)
        except sr.UnknownValueError:
            self.error.emit("Konuşma anlaşılamadı, tekrar deneyin.")
        except sr.RequestError as exc:
            self.error.emit(f"Ses tanıma servisine ulaşılamadı: {exc}")
        except Exception as exc:
            self.error.emit(f"Mikrofon hatası: {exc}")
