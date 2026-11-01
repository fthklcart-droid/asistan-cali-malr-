"""Gemini Live voice worker for FK JARVIS (Windows local microphone/speaker)."""
from __future__ import annotations

import asyncio
import os
import threading

from PySide6.QtCore import QThread, Signal

from .config import load_env

# GEMINI_LIVE_MODEL boşsa sırayla denenir. Google model adlarını sık değiştirir;
# hesabınızda etkin olan modeli .env içine yazmanız önerilir.
FALLBACK_LIVE_MODELS = (
    "gemini-3.1-flash-live-preview",
    "gemini-2.5-flash-native-audio-preview-12-2025",
    "gemini-2.5-flash-native-audio-preview-09-2025",
)

SYSTEM_INSTRUCTION = (
    "Sen FK JARVIS'sin. Kullanıcıyla doğal, akıcı ve anlaşılır Türkçe konuş. "
    "Kısa ve işe dönük yanıt ver. Kullanıcının sesli komutlarını doğru anlamaya çalış. "
    "Bilgisayarda işlem yapacağını iddia etme; gerçek araç çağrısı ve onay olmadan "
    "harici işlem gerçekleştirdiğini söyleme. Para harcama, mesaj gönderme, yayınlama, "
    "dosya silme ve form gönderme öncesinde kullanıcı onayı gerektiğini belirt."
)


class GeminiVoiceWorker(QThread):
    """Streams microphone PCM to Gemini Live and plays native audio replies."""
    status = Signal(str)
    transcript = Signal(str)
    error = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._stop_event = threading.Event()

    def stop(self):
        self._stop_event.set()

    def run(self):
        try:
            asyncio.run(self._live_session())
        except Exception as exc:  # surfaced in UI, never silently swallowed
            self.error.emit(f"Gemini ses bağlantısı: {exc}")

    async def _live_session(self):
        load_env()
        api_key = os.getenv("GEMINI_API_KEY", "").strip()
        if not api_key:
            raise RuntimeError(".env dosyasında GEMINI_API_KEY bulunamadı (Google AI Studio'dan alınır).")

        try:
            import sounddevice as sd
            from google import genai
            from google.genai import types
        except ImportError as exc:
            raise RuntimeError("Ses paketleri eksik. setup_windows.bat dosyasını yeniden çalıştır.") from exc

        configured = os.getenv("GEMINI_LIVE_MODEL", "").strip()
        models = (configured,) if configured else FALLBACK_LIVE_MODELS
        voice_name = os.getenv("GEMINI_VOICE_NAME", "").strip() or "Kore"
        client = genai.Client(api_key=api_key)

        config = {
            "response_modalities": ["AUDIO"],
            "system_instruction": SYSTEM_INSTRUCTION,
            "speech_config": {
                "voice_config": {"prebuilt_voice_config": {"voice_name": voice_name}}
            },
            "input_audio_transcription": {},
            "output_audio_transcription": {},
        }

        last_error: Exception | None = None
        for model in models:
            if self._stop_event.is_set():
                break
            self.status.emit(f"Gemini Live bağlanıyor… ({model})")
            try:
                async with client.aio.live.connect(model=model, config=config) as session:
                    await self._converse(session, sd, types)
                self.status.emit("Gemini sesli asistan durduruldu.")
                return
            except _SessionStarted as started:
                # Oturum açıldıktan sonra oluşan hata: başka model denemenin anlamı yok.
                raise started.original
            except Exception as exc:
                last_error = exc
                self.status.emit(f"{model} ile bağlanılamadı, sıradaki deneniyor…")
        if last_error:
            hint = "" if configured else " .env içindeki GEMINI_LIVE_MODEL alanına hesabınızda etkin Live model kimliğini yazın."
            raise RuntimeError(f"{last_error}.{hint}")

    async def _converse(self, session, sd, types):
        try:
            await self._converse_inner(session, sd, types)
        except Exception as exc:
            raise _SessionStarted(exc) from exc

    async def _converse_inner(self, session, sd, types):
        self.status.emit("Gemini sesli asistan aktif. Konuşabilirsiniz (yankı olmaması için kulaklık önerilir).")
        audio_queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=40)
        loop = asyncio.get_running_loop()

        def mic_callback(indata, frames, time_info, status):
            chunk = bytes(indata)

            def enqueue():
                if not audio_queue.full():
                    audio_queue.put_nowait(chunk)

            loop.call_soon_threadsafe(enqueue)

        try:
            mic = sd.RawInputStream(samplerate=16000, blocksize=1600, channels=1, dtype="int16", callback=mic_callback)
            speaker = sd.RawOutputStream(samplerate=24000, channels=1, dtype="int16")
        except Exception as exc:
            raise RuntimeError(
                f"Mikrofon/hoparlör açılamadı: {exc}. Windows Ayarlar > Gizlilik ve güvenlik > Mikrofon "
                "bölümünden masaüstü uygulamalarına izin verin."
            ) from exc

        with mic, speaker:
            async def send_microphone():
                while not self._stop_event.is_set():
                    try:
                        chunk = await asyncio.wait_for(audio_queue.get(), timeout=0.2)
                    except asyncio.TimeoutError:
                        continue
                    await session.send_realtime_input(
                        audio=types.Blob(data=chunk, mime_type="audio/pcm;rate=16000")
                    )

            async def receive_responses():
                # session.receive() her model turu bittiğinde (turn_complete) sona erer;
                # görüşmenin sürmesi için kullanıcı durdurana kadar tekrar çağrılır.
                while not self._stop_event.is_set():
                    async for response in session.receive():
                        if self._stop_event.is_set():
                            return
                        content = getattr(response, "server_content", None)
                        if not content:
                            continue
                        incoming = getattr(content, "input_transcription", None)
                        outgoing = getattr(content, "output_transcription", None)
                        if incoming and getattr(incoming, "text", ""):
                            self.transcript.emit(f"Sen: {incoming.text}")
                        if outgoing and getattr(outgoing, "text", ""):
                            self.transcript.emit(f"JARVIS: {outgoing.text}")
                        model_turn = getattr(content, "model_turn", None)
                        for part in getattr(model_turn, "parts", None) or []:
                            inline = getattr(part, "inline_data", None)
                            if inline and inline.data:
                                # Bloklayan yazma işlemini ayrı iş parçacığında yap; mikrofon akışı durmasın.
                                await asyncio.to_thread(speaker.write, inline.data)

            sender = asyncio.create_task(send_microphone())
            receiver = asyncio.create_task(receive_responses())
            while not self._stop_event.is_set() and not receiver.done() and not sender.done():
                await asyncio.sleep(0.1)
            for task in (sender, receiver):
                task.cancel()
            results = await asyncio.gather(sender, receiver, return_exceptions=True)
            for result in results:
                if isinstance(result, Exception) and not isinstance(result, asyncio.CancelledError):
                    raise result


class _SessionStarted(Exception):
    def __init__(self, original: Exception):
        super().__init__(str(original))
        self.original = original
