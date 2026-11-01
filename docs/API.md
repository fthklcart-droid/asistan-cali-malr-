# FK JARVIS yerel API (v1)

Temel adres: `http://127.0.0.1:8765` (port `.env` › `JARVIS_PORT`). Yalnızca bu bilgisayardan erişilir.

## Kimlik doğrulama

`/health` dışındaki her istek şu başlığı taşımalıdır:

```
X-Jarvis-Token: <data/device.json içindeki "token">
```

Eksik/yanlış jeton → `401`. CORS kapalıdır; tarayıcıdaki web sayfaları köprüyü çağıramaz.
Hata yanıtları `{"detail": "<Türkçe açıklama>"}` biçimindedir.

PowerShell örneği:

```powershell
$t = (Get-Content data\device.json | ConvertFrom-Json).token
$h = @{ "X-Jarvis-Token" = $t }
Invoke-RestMethod http://127.0.0.1:8765/api/v1/dashboard -Headers $h
Invoke-RestMethod http://127.0.0.1:8765/api/v1/tasks -Method Post -Headers $h `
  -ContentType "application/json; charset=utf-8" -Body '{"instruction":"Chrome''u aç","plan":true}'
```

## Uç noktalar

| Yöntem | Yol | Açıklama | Başarılı | Hatalar |
|---|---|---|---|---|
| GET | `/health` | Canlılık (jetonsuz) | 200 | — |
| GET | `/api/v1/system` | Sürüm, şema sürümü, cihaz kimliği, çalışma süresi, acil durdurma | 200 | |
| GET | `/api/v1/dashboard` | Panel özeti: görev sayıları, son görevler, onaylar, bugünkü kullanım, denetim, acil durdurma | 200 | |
| GET | `/api/v1/tasks?status=&active=&limit=` | Görev listesi (`updated_at` azalan) | 200 | |
| POST | `/api/v1/tasks` | `{instruction, title?, source?, plan?}` görev oluştur; `plan:true` ise Claude ile planla | 201 | 403 (politika), 502 (Claude) |
| GET | `/api/v1/tasks/{id}` | Görev + `events` (zaman çizelgesi) + `approvals` | 200 | 404 |
| POST | `/api/v1/tasks/{id}/plan` | Claude ile (yeniden) planla | 200 | 403, 404, 409, 502 |
| POST | `/api/v1/tasks/{id}/cancel` | İptal et; bağlı bekleyen onaylar geçersiz olur | 200 | 404, 409 |
| POST | `/api/v1/tasks/{id}/status` | `{status: running/done/failed/cancelled/awaiting_approval, message?, result?, error?}` | 200 | 404, 409 (geçersiz geçiş) |
| POST | `/api/v1/tasks/{id}/events` | `{type, message}` zaman çizelgesine olay ekle | 201 | 404 |
| POST | `/api/v1/tasks/{id}/actions` | Görevle ilişkili eylem için onay iste (gövde: eylem, aşağıda) | 201 | 403, 404, 422 |
| GET | `/api/v1/approvals?status=pending\|all` | Onaylar | 200 | |
| POST | `/api/v1/approvals` | Görevsiz eylem için onay iste | 200 | 403, 422 |
| POST | `/api/v1/approvals/{id}` | `{approved: bool}` karar ver; onaylanırsa politika yeniden sorulur ve eylem çalışır | 200 | 400 (eylem hatası), 403, 404 |
| GET | `/api/v1/audit?limit=` | Denetim kaydı (yeniden eskiye) | 200 | |
| GET | `/api/v1/audit/verify` | `{ok, entries, broken_at}` hash zinciri doğrulaması | 200 | |
| GET | `/api/v1/usage?days=7` | Günlük kullanım/maliyet özeti | 200 | |
| GET | `/api/v1/usage/today` | Bugünkü çağrı, token, web araması, maliyet, bütçe | 200 | |
| PUT | `/api/v1/usage/budget` | `{daily_budget_usd}` (0 = sınırsız) | 200 | 422 |
| GET | `/api/v1/control` | Acil durdurma durumu | 200 | |
| POST | `/api/v1/control` | `{engaged: true, reason?}` durdur / `{engaged: false}` kaldır | 200 | |
| GET | `/api/v1/workspaces` | Çalışma klasörleri | 200 | |
| POST | `/api/v1/workspaces` | `{path, label?}` ekle | 201 | 400 |
| DELETE | `/api/v1/workspaces/{id}` | Kaldır | 200 | 404 |
| GET | `/api/v1/history?limit=` | Etkinlik akışı | 200 | |

Masaüstü arayüzünün eski uç noktaları (`/status`, `/task`, `/actions/request`, `/approvals`, `/approvals/{id}`,
`/history`) aynı servisleri kullanır ve aynı jetonu ister.

## Eylem gövdeleri (`action` alanına göre)

```json
{"action": "launch_app", "app_id": "photoshop|premiere|lightroom|capcut|browser"}
{"action": "whatsapp_send", "to": "+90 5xx xxx xx xx", "body": "metin"}
{"action": "youtube_upload", "file_path": "C:/…/video.mp4", "title": "…", "video_description": "", "privacy": "private|unlisted|public"}
{"action": "photoshop", "command": "info|open|auto_tone|resize_long_edge|export_jpeg|export_png", "path": "", "value": 1080}
```

Onay isteği yanıtı `policy` alanını içerir: `{"effect": "approve", "risk": "local|external", "reason": "…"}`.

## Görev durumları

`queued`, `planning`, `planned`, `awaiting_approval`, `running`, `done`, `failed`, `cancelled`.
İzinli geçişler: `app/tasks.py › TRANSITIONS`.
