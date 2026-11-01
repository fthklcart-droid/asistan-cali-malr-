"""FK JARVIS becerileri: sohbet ajanının çağırabildiği yerel araçlar.

Yetkiyi kod verir: yalnızca burada tanımlı adlar çalışır. Dış dünyaya etki eden işlemler
(uygulama açma, WhatsApp) doğrudan çalışmaz; Onaylar listesine düşer.
"""
from __future__ import annotations

import json
from typing import Any, Callable

import requests

from ..config import base_url
from . import dosyalar, gorsel, hafiza, konum, planlayici, temel


def _t(name: str, description: str, props: dict, required: list[str]) -> dict:
    return {"name": name, "description": description, "eager_input_streaming": True,
            "input_schema": {"type": "object", "properties": props, "required": required, "additionalProperties": False}}


S = {"type": "string"}
I = {"type": "integer"}

TOOLS: list[dict] = [
    _t("simdi", "Bilgisayarın saatinden şu anki tarih, gün ve saati döndürür.", {}, []),
    _t("hesapla", "Matematik ifadesini güvenle hesaplar: + - * / // % ** ^, parantez, %18 gibi yüzdeler, sqrt, round. "
       "Türkçe ondalık virgül kabul edilir.", {"ifade": S}, ["ifade"]),
    _t("hava_durumu", "Bir yer için anlık hava durumu ve 1-7 günlük tahmin (Open-Meteo).",
       {"yer": S, "gun": {"type": "integer", "minimum": 1, "maximum": 7}}, ["yer"]),
    _t("konum_coz", "Adres veya yer adını enlem/boylama çevirir.", {"yer": S}, ["yer"]),
    _t("harita_baglantisi", "Google Haritalar bağlantısı üretir; başlangıç verilirse yol tarifi bağlantısı.",
       {"hedef": S, "baslangic": S}, ["hedef"]),
    _t("mesafe_sure", "İki yer arası karayolu mesafesi ve araçla tahmini süre (OSRM) + kuş uçuşu mesafe.",
       {"baslangic": S, "hedef": S}, ["baslangic", "hedef"]),
    _t("yakin_yer_ara", "Bir konumun yakınındaki yerleri OpenStreetMap'te bulur. Tür: " + ", ".join(sorted(konum.YER_TURLERI)),
       {"tur": S, "yer": S, "yaricap_m": I}, ["tur", "yer"]),
    _t("hafiza_kaydet", "Kullanıcı hakkında kalıcı olarak hatırlanacak bir bilgiyi kaydeder. "
       "Kategori: profil, tercih, kisi, proje, not.", {"bilgi": S, "kategori": {"type": "string", "enum": list(hafiza.KATEGORILER)}},
       ["bilgi"]),
    _t("hafiza_ara", "Kalıcı hafızada arar; en ilgili kayıtları döndürür.", {"sorgu": S}, ["sorgu"]),
    _t("hafiza_sil", "Hafızadaki bir kaydı numarasıyla siler.", {"id": I}, ["id"]),
    _t("gorev_ekle", "Yapılacaklar listesine görev ekler. Tarih isteğe bağlı (YYYY-AA-GG).", {"metin": S, "tarih": S}, ["metin"]),
    _t("gorev_listele", "Yapılacaklar listesini döndürür (varsayılan: bitmemişler).", {"hepsi": {"type": "boolean"}}, []),
    _t("gorev_durum", "Görev durumunu değiştirir: bekliyor, devam, duraklatildi, bitti.",
       {"no": I, "durum": {"type": "string", "enum": sorted(planlayici.DURUMLAR)}}, ["no", "durum"]),
    _t("gorev_sil", "Görevi listeden siler.", {"no": I}, ["no"]),
    _t("hatirlatma_kur", "Zamanı gelince ekranda bildirim gösterecek hatırlatma kurar. Zaman: '2026-11-02 09:30', "
       "'18:45' (bugün/yarın) veya '+20dk', '+2saat', '+1gün'.", {"metin": S, "zaman": S}, ["metin", "zaman"]),
    _t("hatirlatma_listele", "Bekleyen hatırlatmaları listeler.", {}, []),
    _t("hatirlatma_sil", "Hatırlatmayı numarasıyla siler.", {"id": I}, ["id"]),
    _t("gunun_ozeti", "Bugünün açık görevleri ve hatırlatmaları.", {}, []),
    _t("dosya_listele", "Kullanıcı klasöründeki bir klasörün içeriğini listeler (ad, boyut, tarih). "
       "Örn. '~/Desktop', '~/Documents', '~/Downloads'.", {"klasor": S, "desen": S}, ["klasor"]),
    _t("dosya_ara", "Klasör ve alt klasörlerde ada göre dosya arar.", {"klasor": S, "desen": S}, ["klasor", "desen"]),
    _t("dosya_bilgi", "Dosya/klasör var mı, boyutu ve son değişim tarihi.", {"yol": S}, ["yol"]),
    _t("dosya_oku", "Metin dosyasının içeriğini okur (parça parça; uzunsa devami_icin_baslangic döner). "
       "Gizli/şifre dosyaları okunamaz.", {"yol": S, "baslangic": I}, ["yol"]),
    _t("not_yaz", "data/notlar altına Markdown not yazar veya mevcut nota ekler.",
       {"baslik": S, "icerik": S, "ekle": {"type": "boolean"}}, ["baslik", "icerik"]),
    _t("notlari_listele", "Kayıtlı notların başlıkları.", {}, []),
    _t("not_oku", "Kayıtlı bir notu okur.", {"baslik": S}, ["baslik"]),
    _t("gorsel_incele", "Bilgisayardaki bir görseli inceler ve açıklar; soru verilirse ona odaklanır.",
       {"yol": S, "soru": S}, ["yol"]),
    _t("fatura_oku", "Fatura/fiş fotoğrafını okuyup kalemlere ayırır ve Excel'de açılabilen CSV olarak kaydeder.",
       {"yol": S}, ["yol"]),
    _t("uygulama_ac_iste", "Bir uygulamayı açmak için Onaylar listesine istek ekler (kullanıcı onaylayınca açılır). "
       "Uygulama: photoshop, premiere, lightroom, capcut, browser.",
       {"uygulama": {"type": "string", "enum": ["photoshop", "premiere", "lightroom", "capcut", "browser"]}}, ["uygulama"]),
    _t("whatsapp_taslak", "WhatsApp mesajını gönderim onayı için Onaylar listesine ekler; kullanıcı onaylamadan gönderilmez.",
       {"alici": S, "mesaj": S}, ["alici", "mesaj"]),
    _t("bilgisayar_gorevi", "Ekranda fare/klavye ile yapılması gereken bir görevi 'Bilgisayarda yaptır' alanına aktarır; "
       "kullanıcı başlatınca çalışır.", {"gorev": S}, ["gorev"]),
]

TOOL_NAMES = {t["name"] for t in TOOLS}

DURUM_METNI = {
    "simdi": "Saate bakıyorum", "hesapla": "Hesaplıyorum", "hava_durumu": "Hava durumuna bakıyorum",
    "konum_coz": "Konumu buluyorum", "harita_baglantisi": "Harita bağlantısı hazırlıyorum",
    "mesafe_sure": "Mesafe ve süreyi hesaplıyorum", "yakin_yer_ara": "Yakındaki yerleri arıyorum",
    "hafiza_kaydet": "Hafızaya kaydediyorum", "hafiza_ara": "Hafızada arıyorum", "hafiza_sil": "Hafızadan siliyorum",
    "gorev_ekle": "Görev ekliyorum", "gorev_listele": "Görevlere bakıyorum", "gorev_durum": "Görevi güncelliyorum",
    "gorev_sil": "Görevi siliyorum", "hatirlatma_kur": "Hatırlatma kuruyorum", "hatirlatma_listele": "Hatırlatmalara bakıyorum",
    "hatirlatma_sil": "Hatırlatmayı siliyorum", "gunun_ozeti": "Günün özetini çıkarıyorum",
    "dosya_listele": "Klasöre bakıyorum", "dosya_ara": "Dosya arıyorum", "dosya_bilgi": "Dosyaya bakıyorum",
    "dosya_oku": "Dosyayı okuyorum", "not_yaz": "Not yazıyorum", "notlari_listele": "Notlara bakıyorum",
    "not_oku": "Notu okuyorum", "gorsel_incele": "Görseli inceliyorum", "fatura_oku": "Faturayı okuyorum",
    "uygulama_ac_iste": "Uygulama açma onayı istiyorum", "whatsapp_taslak": "WhatsApp onayı hazırlıyorum",
    "bilgisayar_gorevi": "Görevi aktarıyorum", "web_search": "İnternette arıyorum", "web_fetch": "Sayfayı okuyorum",
}


def _request_action(payload: dict) -> dict:
    r = requests.post(base_url() + "/actions/request", json=payload, timeout=10)
    if r.status_code >= 400:
        try:
            detail = r.json().get("detail")
        except ValueError:
            detail = r.text
        raise ValueError(f"Onay isteği oluşturulamadı: {detail}")
    item = r.json()
    return {"durum": "Onaylar listesine eklendi; kullanıcı onaylayınca çalışacak.", "ozet": item["action"].get("summary")}


def run_tool(name: str, args: dict[str, Any], on_computer_task: Callable[[str], None] | None = None) -> str:
    """Aracı çalıştırır ve sonucu JSON metin olarak döndürür. Hata olursa ValueError fırlatır."""
    if name not in TOOL_NAMES:
        raise ValueError(f"Bilinmeyen araç: {name}")
    a = args or {}

    def need(key):
        value = a.get(key)
        if value is None or (isinstance(value, str) and not value.strip()):
            raise ValueError(f"'{key}' parametresi gerekli.")
        return value

    match name:
        case "simdi": out = temel.simdi()
        case "hesapla": out = temel.hesapla(need("ifade"))
        case "hava_durumu": out = konum.hava_durumu(need("yer"), a.get("gun", 1))
        case "konum_coz": out = konum.konum_coz(need("yer"))
        case "harita_baglantisi": out = konum.harita_baglantisi(need("hedef"), a.get("baslangic", ""))
        case "mesafe_sure": out = konum.mesafe_sure(need("baslangic"), need("hedef"))
        case "yakin_yer_ara": out = konum.yakin_yer_ara(need("tur"), need("yer"), a.get("yaricap_m", 1500))
        case "hafiza_kaydet": out = hafiza.kaydet(need("bilgi"), a.get("kategori", "not"))
        case "hafiza_ara": out = hafiza.ara(need("sorgu"))
        case "hafiza_sil": out = hafiza.sil(need("id"))
        case "gorev_ekle": out = planlayici.gorev_ekle(need("metin"), a.get("tarih", ""))
        case "gorev_listele": out = planlayici.gorev_listele(bool(a.get("hepsi")))
        case "gorev_durum": out = planlayici.gorev_durum(need("no"), need("durum"))
        case "gorev_sil": out = planlayici.gorev_sil(need("no"))
        case "hatirlatma_kur": out = planlayici.hatirlatma_kur(need("metin"), need("zaman"))
        case "hatirlatma_listele": out = planlayici.hatirlatma_listele()
        case "hatirlatma_sil": out = planlayici.hatirlatma_sil(need("id"))
        case "gunun_ozeti": out = planlayici.gunun_ozeti()
        case "dosya_listele": out = dosyalar.listele(need("klasor"), a.get("desen") or "*")
        case "dosya_ara": out = dosyalar.ara(need("klasor"), need("desen"))
        case "dosya_bilgi": out = dosyalar.bilgi(need("yol"))
        case "dosya_oku": out = dosyalar.oku(need("yol"), a.get("baslangic", 0))
        case "not_yaz": out = dosyalar.not_yaz(need("baslik"), need("icerik"), bool(a.get("ekle")))
        case "notlari_listele": out = dosyalar.notlari_listele()
        case "not_oku": out = dosyalar.not_oku(need("baslik"))
        case "gorsel_incele": out = gorsel.incele(need("yol"), a.get("soru", ""))
        case "fatura_oku": out = gorsel.fatura_oku(need("yol"))
        case "uygulama_ac_iste":
            app_id = need("uygulama")
            out = _request_action({"action": "launch_app", "app_id": app_id, "description": f"{app_id} uygulamasını aç"})
        case "whatsapp_taslak":
            out = _request_action({"action": "whatsapp_send", "to": need("alici"), "body": need("mesaj")})
        case "bilgisayar_gorevi":
            gorev = need("gorev")
            if on_computer_task:
                on_computer_task(gorev)
            out = {"durum": "Görev 'Bilgisayarda yaptır' alanına aktarıldı; kullanıcının başlatması bekleniyor."}
        case _:
            raise ValueError(f"Bilinmeyen araç: {name}")
    return json.dumps(out, ensure_ascii=False)
