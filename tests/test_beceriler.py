import json
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from app import beceriler
from app.beceriler import dosyalar, hafiza, planlayici, temel


def test_hesapla():
    assert temel.hesapla("1.234,5 + 1")["sonuc"] == 1235.5
    assert temel.hesapla("3.141*2")["sonuc"] == pytest.approx(6.282)
    assert temel.hesapla("1000 * %18")["sonuc"] == 180
    assert temel.hesapla("10 % 3")["sonuc"] == 1
    with pytest.raises(ValueError):
        temel.hesapla("__import__('os').system('dir')")


def test_simdi():
    assert str(datetime.now().year) in temel.simdi()["okunur"]


def test_zaman_coz():
    now = datetime(2026, 11, 1, 20, 0)
    assert planlayici.zaman_coz("+20dk", now) == now + timedelta(minutes=20)
    assert planlayici.zaman_coz("+1gün", now) == now + timedelta(days=1)
    assert planlayici.zaman_coz("21:30", now) == datetime(2026, 11, 1, 21, 30)
    assert planlayici.zaman_coz("08:00", now) == datetime(2026, 11, 2, 8, 0)
    with pytest.raises(ValueError):
        planlayici.zaman_coz("yarın sabah", now)


def test_tasks_and_reminders():
    t = planlayici.gorev_ekle("Faturayı öde", "2026-11-05")
    assert planlayici.gorev_listele()["gorevler"][0]["metin"] == "Faturayı öde"
    planlayici.gorev_durum(t["no"], "bitti")
    assert planlayici.gorev_listele()["gorevler"] == []
    planlayici.hatirlatma_kur("Toplantı", "+1dk")
    assert planlayici.zamani_gelenler() == []
    due = planlayici.zamani_gelenler(datetime.now() + timedelta(minutes=2))
    assert [d["metin"] for d in due] == ["Toplantı"]
    assert planlayici.zamani_gelenler(datetime.now() + timedelta(minutes=5)) == []  # bir kez gösterilir


def test_memory_search_turkish_suffixes():
    hafiza.kaydet("Kızımın adı Elif, 7 yaşında", "kisi")
    hafiza.kaydet("Kahveyi şekersiz içerim", "tercih")
    assert hafiza.kaydet("Kahveyi şekersiz içerim", "tercih")["durum"] == "zaten kayıtlı"
    top = hafiza.ara("kızım kaç yaşında")["sonuclar"][0]
    assert "Elif" in top["bilgi"]
    assert "şekersiz" in hafiza.baglam_ozeti()


def test_file_safety(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    (tmp_path / "Documents").mkdir()  # varsayılan çalışma klasörü olarak otomatik eklenir
    (tmp_path / "Documents" / "rapor.txt").write_text("satış raporu", encoding="utf-8")
    (tmp_path / ".env").write_text("SECRET=1")
    (tmp_path / "sifreler.txt").write_text("x")
    assert dosyalar.oku("~/Documents/rapor.txt")["icerik"] == "satış raporu"
    assert dosyalar.listele("~/Documents")["adet"] == 1
    assert dosyalar.ara("~/Documents", "rapor")["sonuclar"]
    (tmp_path / "Documents" / "sifreler.txt").write_text("x")
    # ev klasörünün kökü çalışma klasörü değil; .env, şifre dosyaları ve sistem yolları reddedilir
    for bad in ("~/.env", "~/sifreler.txt", "~/Documents/sifreler.txt", "/etc/passwd", "~/../../etc/passwd"):
        with pytest.raises(dosyalar.DosyaHatasi):
            dosyalar.oku(bad)


def test_notes_roundtrip():
    dosyalar.not_yaz("Alışveriş", "süt")
    dosyalar.not_yaz("Alışveriş", "ekmek", ekle=True)
    assert "süt" in dosyalar.not_oku("Alışveriş")["icerik"] and "ekmek" in dosyalar.not_oku("Alışveriş")["icerik"]


def test_registry_consistency():
    names = [t["name"] for t in beceriler.TOOLS]
    assert len(names) == len(set(names))
    for n in names:
        assert n in beceriler.DURUM_METNI, n
    assert json.loads(beceriler.run_tool("hesapla", {"ifade": "2+2"}))["sonuc"] == 4
    with pytest.raises(ValueError):
        beceriler.run_tool("rm_rf", {})
    with pytest.raises(ValueError):
        beceriler.run_tool("hesapla", {})
    got = []
    beceriler.run_tool("bilgisayar_gorevi", {"gorev": "Not Defteri'ni aç"}, got.append)
    assert got == ["Not Defteri'ni aç"]
