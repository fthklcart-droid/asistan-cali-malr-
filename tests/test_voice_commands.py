import pytest

pytest.importorskip("PySide6.QtCore")

from app.windows_voice_commands import match_app, turkish_lower  # noqa: E402


def test_turkish_dotted_capital_i():
    assert turkish_lower("İnternet") == "internet"
    assert turkish_lower("IŞIK") == "ışık"


@pytest.mark.parametrize("text,expected", [
    ("Photoshop'u aç", "photoshop"),
    ("Fotoşop'u açar mısın", "photoshop"),
    ("Premiere Pro'yu aç", "premiere"),
    ("Lightroom'u başlat", "lightroom"),
    ("CapCut'ı aç", "capcut"),
    ("İnterneti aç", "browser"),
    ("Chrome'u aç", "browser"),
    ("Bugün hava nasıl", None),
])
def test_match_app(text, expected):
    assert match_app(text) == expected
