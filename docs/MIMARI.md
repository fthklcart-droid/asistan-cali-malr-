# FK JARVIS OS — Mimari (v0.3)

Bu belge kodun **şu anki** halini anlatır. Hedef mimari ve gerekçeler: [ARCHITECTURE_TR.md](ARCHITECTURE_TR.md);
geliştirme talimatı: [CLAUDE_CODE_BRIEF_TR.md](CLAUDE_CODE_BRIEF_TR.md); kalan işler: [YOL_HARITASI.md](YOL_HARITASI.md).

## 1. Süreç modeli

```text
run_windows.bat / masaüstü kısayolu
        │
        ▼
python -m app.main ─────────────────────────── tek Windows süreci
   ├─ iş parçacığı: Uvicorn  → app/server.py   (yerel köprü, yalnız 127.0.0.1:8765)
   └─ ana iş parçacığı: Qt   → app/ui.py       (masaüstü kontrol merkezi)
          ├─ QThread: ChatWorker        → app/sohbet.py         (Claude sohbet + beceriler)
          ├─ QThread: AgentWorker       → app/computer_agent.py (Claude Computer Use döngüsü)
          ├─ QThread: GeminiVoiceWorker → app/gemini_voice.py   (Gemini Live ses)
          └─ QThread: CallWorker        → köprüye HTTP istekleri (arayüz donmaz)
```

- Köprü ayrıca tek başına da çalışabilir: `python -m app.server` (ör. ileride Android eşleştirmesi için).
- Arayüz, köprüye **her zaman HTTP** ile (`app/api_client.py`, `X-Jarvis-Token` başlığı) konuşur. Böylece
  aynı API başka istemcilere (mobil, betik) de açıktır ve tüm iş kuralları tek yerde (köprü servisleri) kalır.
  İstisna: sohbet becerileri (hafıza, planlayıcı) aynı süreçte doğrudan veritabanını kullanır.

## 2. Katmanlar ve modüller

| Katman | Modül | Görev |
|---|---|---|
| **Sunum** | `ui.py`, `agent_overlay.py` | Kontrol merkezi (10 sayfa), bilgisayar kontrolü onay/DURDUR paneli |
| **API** | `server.py` | FastAPI; `/api/v1/*` + masaüstünün eski uç noktaları; jeton doğrulama ara katmanı |
| **Orkestrasyon** | `orchestrator.py` | Eylem yolu: politika → onay → politika (yeniden) → yürütme → kayıtlar; Claude planlama |
| **Alan servisleri** | `tasks.py` | Görev durum makinesi, sınıflandırma, zaman çizelgesi |
| | `approvals.py` | Kalıcı onay kuyruğu (atomik karar) |
| | `policy.py` | Politika motoru: allow / approve / deny |
| | `control.py` | Global acil durdurma |
| | `usage.py` | Token kullanımı, maliyet, günlük bütçe |
| | `audit.py` | Hash zincirli denetim kaydı |
| | `workspaces.py` | Dosya erişimine açık çalışma klasörleri |
| | `device.py` | Cihaz kimliği + yerel API anahtarı |
| **Ajanlar** | `claude_client.py` | Görev planı (tek çağrı) |
| | `sohbet.py` + `beceriler/` | Akan sohbet, web araması, 30 yerel beceri |
| | `computer_agent.py` + `executor.py` | Ekran görüntüsü → Claude → (onay) → fare/klavye döngüsü |
| | `gemini_voice.py`, `windows_voice_commands.py`, `konusma.py` | Ses girişi/çıkışı |
| **Bağlayıcılar** | `actions.py` + `connectors/` | Uygulama başlatma, WhatsApp, YouTube, Photoshop |
| **Altyapı** | `db.py` | SQLite, şema göçleri, yedekleme, bakım komutları |
| | `config.py`, `settings_store.py`, `doctor.py`, `main.py` | Yapılandırma, ayar ekranı, kurulum kontrolü, başlatıcı |

Bağımlılık yönü yukarıdan aşağıdır: sunum → API → orkestrasyon → alan servisleri → altyapı.
Alan servisleri arayüzü veya FastAPI'yi içe aktarmaz.

## 3. Veritabanı (`data/jarvis.db`)

SQLite, WAL günlüğü, `foreign_keys=ON`. Her bağlantı kısa ömürlüdür (`db.session()`), yazma işlemleri
`BEGIN IMMEDIATE` ile tek işlem olarak commit edilir. Şema sürümleri `schema_version` tablosunda tutulur.

| Göç | İçerik |
|---|---|
| 1 | `events` (etkinlik akışı), `memory` (hafıza), `todos` (yapılacaklar), `reminders` (hatırlatmalar) |
| 2 | Eski `hafiza.db` / `planlayici.db` dosyalarını içe aktarır, `*.db.imported` olarak yeniden adlandırır |
| 3 | `tasks`, `task_events`, `approvals`, `audit_log`, `usage`, `settings`, `workspaces` |

Kurallar: yayımlanmış bir göç **değiştirilmez**; değişiklik yeni bir göçtür. Göçler atomiktir (yarıda kalan
göç geri alınır — testle doğrulanır). `executescript()` kullanılmaz (açık işlemi sessizce commit eder).

```text
tasks 1 ──< task_events          (görev zaman çizelgesi)
tasks 1 ──< approvals            (görevle ilişkili eylem onayları; görev silinirse task_id NULL olur)
audit_log                        (bağımsız, eklemeli, hash zinciri)
usage                            (her model çağrısı bir satır)
settings                         (anahtar/değer: bütçe, sınırlar, acil durdurma durumu)
workspaces                       (izinli klasörler)
```

## 4. Görev yaşam döngüsü

```text
queued ─► planning ─► planned ─► running ─► done
   │                     │  └──► awaiting_approval ─► running / done / failed / cancelled
   └─────────────────────┴─────► cancelled                failed ─► queued (yeniden dene)
```

1. Görev oluşur (arayüz, hızlı komut, sesli komut, API) ve türü sınıflandırılır: tasarım, video, iletişim, web,
   dosya, genel.
2. **Planla** → `orchestrator.plan` → Claude planı görev kaydına yazılır.
3. **Bilgisayarda yaptır** → `AgentWorker` görevi `running` yapar, her eylemi ve Claude notunu zaman
   çizelgesine yazar, sonunda `done` / `failed` / `cancelled`.
4. **Eylem** (uygulama aç, WhatsApp, YouTube, Photoshop) → onay kuyruğu → görev `awaiting_approval` →
   onay/ret/hata sonucuna göre görev `done` / `cancelled` / `failed`.
5. Geçersiz geçişler (ör. `done → running`) reddedilir (HTTP 409).

## 5. Güvenlik modeli (uygulanan)

| İlke (ARCHITECTURE_TR.md) | Uygulama |
|---|---|
| Yerel API yalnız loopback | `config.host()` 127.0.0.1/localhost/::1 dışını kabul etmez |
| Yerel API kimliği | `X-Jarvis-Token` (data/device.json, 256 bit rastgele); `/health` hariç her uç nokta; CORS kapalı |
| Uygulama izin listesi, keyfi shell yok | `desktop.ALLOWED_APPS`, `models.ActionRequest` (pydantic ayırt edici birleşim) |
| Dosya alanları kullanıcı seçimiyle sınırlı | `workspaces` + `dosyalar.guvenli_yol` (gizli dosya/klasör kara listesi ayrıca uygulanır) |
| Dış etkili işlemler onaylı | `policy.evaluate_action` hiçbir eylemi onaysız `allow` etmez; günlük sınırlar |
| Ekran/sayfa içeriği güvenilmez | Sohbet ve bilgisayar ajanı sistem istemlerinde; gönderme/ödeme/silme öncesi `ask_user` |
| Acil durdurma | `control.engage`: onaylar geçersiz, görevler iptal, ajan ve sohbet döngüleri bir sonraki adımda durur |
| Denetim kaydı | `audit_log` hash zinciri; `GET /api/v1/audit/verify`, `python -m app.db verify` |
| Anahtarlar loglanmaz | `.env` yalnız yerelde; denetimde yalnız değişen anahtarın **adı** |
| Maliyet sınırı | `usage.check_budget` her model çağrısından önce |

## 6. Veri konumları

| Yol | İçerik | Git'e girer mi |
|---|---|---|
| `.env` | API anahtarları ve seçenekler | Hayır |
| `config/apps.json` | Uygulama yolları | Hayır |
| `data/jarvis.db` (+ `-wal`, `-shm`) | Tüm kalıcı veri | Hayır |
| `data/device.json` | Cihaz kimliği + yerel API anahtarı | Hayır |
| `data/notlar/`, `data/faturalar/` | Notlar, fatura CSV'leri | Hayır |
| `data/youtube_token.json` | YouTube OAuth jetonu | Hayır |
| `data/yedekler/` | `python -m app.db backup` çıktıları | Hayır |
| `jarvis.log` | Uygulama günlüğü | Hayır |
