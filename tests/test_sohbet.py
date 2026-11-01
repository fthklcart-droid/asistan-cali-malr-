from types import SimpleNamespace as NS

import anthropic
import httpx
import pytest

from app.sohbet import Sohbet


class FakeStream:
    def __init__(self, final, texts):
        self.final, self.texts = final, texts

    def __enter__(self): return self
    def __exit__(self, *a): return False

    def __iter__(self):
        for b in self.final.content:
            if b.type in ("tool_use", "server_tool_use"):
                yield NS(type="content_block_start", content_block=b)
        for t in self.texts:
            yield NS(type="text", text=t)

    def get_final_message(self): return self.final


class FakeClient:
    def __init__(self, turns):
        self.turns, self.calls = list(turns), []
        self.messages = NS(stream=self.stream)

    def stream(self, **kw):
        self.calls.append({**kw, "messages": list(kw["messages"])})
        item = self.turns.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


def tu(id_, name, **inp):
    return NS(type="tool_use", id=id_, name=name, input=inp)


def test_tool_roundtrip_and_streaming():
    client = FakeClient([
        FakeStream(NS(content=[tu("a", "hesapla", ifade="12*12")], stop_reason="tool_use"), []),
        FakeStream(NS(content=[NS(type="text", text="Sonuç 144.")], stop_reason="end_turn"), ["Sonuç ", "144."]),
    ])
    chunks, statuses = [], []
    answer = Sohbet(client).send("12 kere 12?", chunks.append, statuses.append)
    assert answer == "Sonuç 144."
    assert "Hesaplıyorum…" in statuses
    result = client.calls[1]["messages"][-1]["content"][0]
    assert result["tool_use_id"] == "a" and '"sonuc": 144' in result["content"]
    tools = client.calls[0]["tools"]
    assert tools[0]["type"] == "web_search_20260209"
    assert client.calls[0]["cache_control"] == {"type": "ephemeral"}


def test_pause_turn_resends_without_new_user_message():
    client = FakeClient([
        FakeStream(NS(content=[NS(type="server_tool_use", name="web_search", id="s")], stop_reason="pause_turn"), []),
        FakeStream(NS(content=[NS(type="text", text="Haberler…")], stop_reason="end_turn"), ["Haberler…"]),
    ])
    Sohbet(client).send("bugünün haberleri", lambda t: None)
    assert client.calls[1]["messages"][-1]["role"] == "assistant"


def test_tool_error_is_returned_to_model():
    client = FakeClient([
        FakeStream(NS(content=[tu("a", "dosya_oku", yol="/etc/shadow")], stop_reason="tool_use"), []),
        FakeStream(NS(content=[NS(type="text", text="Okuyamadım.")], stop_reason="end_turn"), ["Okuyamadım."]),
    ])
    Sohbet(client).send("oku", lambda t: None)
    r = client.calls[1]["messages"][-1]["content"][0]
    assert r["is_error"] is True and "Hata" in r["content"]


def test_api_error_rolls_back_history():
    req = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    err = anthropic.AuthenticationError("bad", response=httpx.Response(401, request=req), body=None)
    s = Sohbet(FakeClient([err]))
    with pytest.raises(RuntimeError, match="geçersiz"):
        s.send("merhaba", lambda t: None)
    assert s.messages == []
