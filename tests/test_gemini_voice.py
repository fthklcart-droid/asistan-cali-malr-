"""Gemini Live görüşmesinin ilk yanıttan sonra kapanmadığını doğrular (gerçek ağ/ses kullanılmaz)."""
import asyncio
from types import SimpleNamespace as NS

import pytest

pytest.importorskip("PySide6.QtCore")

from app.gemini_voice import GeminiVoiceWorker  # noqa: E402


class FakeStream:
    def __init__(self, *a, **k):
        self.written = []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def write(self, data):
        self.written.append(data)


class FakeSession:
    """Her receive() çağrısı tek bir model turu döndürür ve biter (gerçek SDK davranışı)."""

    def __init__(self, worker, turns):
        self.worker = worker
        self.turns = turns
        self.calls = 0

    async def send_realtime_input(self, **kwargs):
        pass

    async def receive(self):
        if self.calls >= self.turns:
            self.worker.stop()
            await asyncio.sleep(0.05)
            return
        self.calls += 1
        part = NS(inline_data=NS(data=b"\x00\x00"))
        yield NS(server_content=NS(
            input_transcription=NS(text=f"soru {self.calls}"),
            output_transcription=NS(text=f"cevap {self.calls}"),
            model_turn=NS(parts=[part]),
        ))


def test_conversation_continues_across_turns():
    worker = GeminiVoiceWorker()
    lines = []
    worker.transcript.connect(lines.append)
    speaker = FakeStream()
    sd = NS(RawInputStream=FakeStream, RawOutputStream=lambda *a, **k: speaker)
    types = NS(Blob=lambda **k: k)
    session = FakeSession(worker, turns=3)

    asyncio.run(worker._converse_inner(session, sd, types))

    assert session.calls == 3
    assert "JARVIS: cevap 3" in lines
    assert len(speaker.written) == 3
