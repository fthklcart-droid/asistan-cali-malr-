"""Hava durumu ve harita becerileri (anahtar gerektirmeyen açık servisler).

Open-Meteo (hava + yer adı), OpenStreetMap Nominatim (adres), OSRM (yol mesafesi), Overpass (yakın yerler).
"""
from __future__ import annotations

import math
import urllib.parse

import requests

UA = {"User-Agent": "FK-JARVIS/0.3 (kisisel masaustu asistani)"}

HAVA_KODLARI = {
    0: "açık", 1: "çoğunlukla açık", 2: "parçalı bulutlu", 3: "kapalı", 45: "sisli", 48: "kırağılı sis",
    51: "hafif çisenti", 53: "çisenti", 55: "yoğun çisenti", 61: "hafif yağmur", 63: "yağmur", 65: "kuvvetli yağmur",
    66: "dondurucu yağmur", 67: "kuvvetli dondurucu yağmur", 71: "hafif kar", 73: "kar", 75: "yoğun kar",
    77: "kar taneleri", 80: "hafif sağanak", 81: "sağanak", 82: "şiddetli sağanak", 85: "kar sağanağı",
    86: "yoğun kar sağanağı", 95: "gök gürültülü fırtına", 96: "dolu ile fırtına", 99: "şiddetli dolu ile fırtına",
}

YER_TURLERI = {
    "eczane": ("amenity", "pharmacy"), "hastane": ("amenity", "hospital"), "atm": ("amenity", "atm"),
    "banka": ("amenity", "bank"), "benzinlik": ("amenity", "fuel"), "akaryakıt": ("amenity", "fuel"),
    "restoran": ("amenity", "restaurant"), "kafe": ("amenity", "cafe"), "otopark": ("amenity", "parking"),
    "market": ("shop", "supermarket"), "süpermarket": ("shop", "supermarket"), "fırın": ("shop", "bakery"),
    "otel": ("tourism", "hotel"), "cami": ("amenity", "place_of_worship"), "okul": ("amenity", "school"),
    "polis": ("amenity", "police"), "postane": ("amenity", "post_office"), "kuaför": ("shop", "hairdresser"),
}


def _get(url: str, params: dict, timeout: int = 15) -> dict | list:
    r = requests.get(url, params=params, headers=UA, timeout=timeout)
    r.raise_for_status()
    return r.json()


def konum_coz(yer: str) -> dict:
    """Adres/yer adı -> koordinat."""
    data = _get("https://nominatim.openstreetmap.org/search",
                {"q": yer, "format": "jsonv2", "limit": 1, "accept-language": "tr"})
    if not data:
        geo = _get("https://geocoding-api.open-meteo.com/v1/search", {"name": yer, "count": 1, "language": "tr"})
        res = (geo.get("results") or []) if isinstance(geo, dict) else []
        if not res:
            raise ValueError(f"Konum bulunamadı: {yer}")
        r = res[0]
        return {"ad": ", ".join(x for x in (r.get("name"), r.get("admin1"), r.get("country")) if x),
                "enlem": r["latitude"], "boylam": r["longitude"]}
    r = data[0]
    return {"ad": r.get("display_name", yer), "enlem": float(r["lat"]), "boylam": float(r["lon"])}


def hava_durumu(yer: str, gun: int = 1) -> dict:
    gun = max(1, min(7, int(gun or 1)))
    k = konum_coz(yer)
    data = _get("https://api.open-meteo.com/v1/forecast", {
        "latitude": k["enlem"], "longitude": k["boylam"], "timezone": "auto", "forecast_days": gun,
        "current": "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation",
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
    })
    cur = data.get("current", {})
    daily = data.get("daily", {})
    tahmin = []
    for i, tarih in enumerate(daily.get("time", [])):
        tahmin.append({"tarih": tarih, "durum": HAVA_KODLARI.get(daily["weather_code"][i], "?"),
                       "en_yuksek": daily["temperature_2m_max"][i], "en_dusuk": daily["temperature_2m_min"][i],
                       "yagis_olasiligi_yuzde": daily["precipitation_probability_max"][i]})
    return {"konum": k["ad"], "simdi": {
        "sicaklik_c": cur.get("temperature_2m"), "hissedilen_c": cur.get("apparent_temperature"),
        "nem_yuzde": cur.get("relative_humidity_2m"), "ruzgar_kmh": cur.get("wind_speed_10m"),
        "yagis_mm": cur.get("precipitation"), "durum": HAVA_KODLARI.get(cur.get("weather_code"), "?")},
        "tahmin": tahmin, "kaynak": "Open-Meteo"}


def harita_baglantisi(hedef: str, baslangic: str = "") -> dict:
    if baslangic:
        url = "https://www.google.com/maps/dir/?api=1&" + urllib.parse.urlencode(
            {"origin": baslangic, "destination": hedef})
    else:
        url = "https://www.google.com/maps/search/?api=1&" + urllib.parse.urlencode({"query": hedef})
    return {"baglanti": url}


def _haversine(a: dict, b: dict) -> float:
    r = 6371.0
    p1, p2 = math.radians(a["enlem"]), math.radians(b["enlem"])
    dp, dl = p2 - p1, math.radians(b["boylam"] - a["boylam"])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def mesafe_sure(baslangic: str, hedef: str) -> dict:
    a, b = konum_coz(baslangic), konum_coz(hedef)
    sonuc = {"baslangic": a["ad"], "hedef": b["ad"], "kus_ucusu_km": round(_haversine(a, b), 1)}
    try:
        data = _get(f"https://router.project-osrm.org/route/v1/driving/{a['boylam']},{a['enlem']};{b['boylam']},{b['enlem']}",
                    {"overview": "false"})
        route = (data.get("routes") or [None])[0]
        if route:
            sonuc["karayolu_km"] = round(route["distance"] / 1000, 1)
            dk = round(route["duration"] / 60)
            sonuc["tahmini_sure"] = f"{dk // 60} sa {dk % 60} dk" if dk >= 60 else f"{dk} dk"
    except Exception as exc:
        sonuc["not"] = f"Yol mesafesi alınamadı ({exc}); yalnız kuş uçuşu verildi."
    sonuc.update(harita_baglantisi(hedef, baslangic))
    return sonuc


def yakin_yer_ara(tur: str, yer: str, yaricap_m: int = 1500) -> dict:
    key = tur.strip().lower()
    if key not in YER_TURLERI:
        raise ValueError("Desteklenen türler: " + ", ".join(sorted(YER_TURLERI)))
    tag, value = YER_TURLERI[key]
    k = konum_coz(yer)
    radius = max(100, min(10000, int(yaricap_m or 1500)))
    query = (f'[out:json][timeout:20];(node["{tag}"="{value}"](around:{radius},{k["enlem"]},{k["boylam"]});'
             f'way["{tag}"="{value}"](around:{radius},{k["enlem"]},{k["boylam"]}););out center 15;')
    r = requests.post("https://overpass-api.de/api/interpreter", data={"data": query}, headers=UA, timeout=30)
    r.raise_for_status()
    sonuc = []
    for el in r.json().get("elements", []):
        lat = el.get("lat") or el.get("center", {}).get("lat")
        lon = el.get("lon") or el.get("center", {}).get("lon")
        if lat is None:
            continue
        tags = el.get("tags", {})
        nokta = {"enlem": lat, "boylam": lon}
        sonuc.append({"ad": tags.get("name", "(adsız)"), "adres": " ".join(
            x for x in (tags.get("addr:street"), tags.get("addr:housenumber"), tags.get("addr:district")) if x),
            "mesafe_m": round(_haversine(k, nokta) * 1000), "telefon": tags.get("phone", ""),
            "harita": f"https://www.google.com/maps/search/?api=1&query={lat},{lon}"})
    sonuc.sort(key=lambda x: x["mesafe_m"])
    return {"merkez": k["ad"], "tur": key, "sonuclar": sonuc[:10], "kaynak": "OpenStreetMap"}
