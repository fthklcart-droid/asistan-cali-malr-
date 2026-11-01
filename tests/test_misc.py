import os

from app import executor, settings_store
from app.konusma import temizle


def test_scale_and_keys():
    assert executor.scale_factor(1366, 768) == 1.0
    assert executor.scale_factor(2560, 1440) < 0.6
    assert executor.normalize_key("ctrl+Return") == ["ctrl", "enter"]
    assert executor.normalize_key("super") == ["win"]


def test_write_env_keeps_comments(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text("# yorum\nANTHROPIC_API_KEY=\nJARVIS_PORT=8765\n", encoding="utf-8")
    settings_store.write_env({"ANTHROPIC_API_KEY": "sk-ant-x", "GEMINI_VOICE_NAME": "Kore Ses"}, path=env)
    text = env.read_text(encoding="utf-8")
    assert "# yorum" in text and "ANTHROPIC_API_KEY=sk-ant-x" in text and 'GEMINI_VOICE_NAME="Kore Ses"' in text
    assert os.environ["ANTHROPIC_API_KEY"] == "sk-ant-x"
    monkeypatch.delenv("ANTHROPIC_API_KEY")
    monkeypatch.delenv("GEMINI_VOICE_NAME")


def test_tts_cleanup():
    assert temizle("**Merhaba** [site](https://x.com) 😀") == "Merhaba site"
