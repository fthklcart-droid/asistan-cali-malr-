package com.fk.jarvis.apps

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings

/** Bir Android eyleminin gerçek sonucu. [success] yalnızca sistem eylemi kabul ettiyse true'dur. */
data class ActionResult(val success: Boolean, val message: String)

/**
 * Seviye A — güvenli temel kontrol: yalnızca belgelenmiş Android intent'leri kullanır.
 * Uygulamalar paket adı tahmin edilerek değil, cihazdaki başlatıcı listesinden bulunur.
 */
class AndroidAppController(private val context: Context) {

    private var cache: List<AppInfo> = emptyList()
    private var cacheTime = 0L

    /** Başlatıcıda görünen, kurulu uygulamalar (AndroidManifest'teki <queries> sayesinde görünür). */
    fun installedApps(): List<AppInfo> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cacheTime < 30_000) return cache
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        cache = pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        cacheTime = now
        return cache
    }

    fun findApp(spokenName: String): AppMatch = AppMatcher.match(spokenName, installedApps())

    /** Paket adı cihazda gerçekten kurulu ve başlatılabilir mi? (LLM'den gelen hedefleri doğrulamak için) */
    fun verifiedApp(packageName: String?): AppInfo? =
        packageName?.let { pkg -> installedApps().firstOrNull { it.packageName == pkg } }

    fun launch(app: AppInfo): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ActionResult(false, "${app.label} uygulamasını başlatamadım.")
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "${app.label} açıldı.", "${app.label} açılamadı.")
    }

    fun goHome(): ActionResult =
        start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "Ana ekrana dönüldü.", "Ana ekrana dönülemedi.")

    fun openCamera(): ActionResult {
        val r = start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Kamera açıldı.", "")
        return if (r.success) r else start(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Kamera açıldı.", "Bu cihazda kamera uygulaması bulunamadı.")
    }

    fun webSearch(query: String): ActionResult {
        val r = start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query), "\"$query\" için arama açıldı.", "")
        if (r.success) return r
        return openUrl("https://www.google.com/search?q=" + Uri.encode(query))
    }

    /** YouTube uygulamasında arama; uygulama yoksa tarayıcıda YouTube arama sonuçları. */
    fun youtubeSearch(query: String): ActionResult {
        val r = start(
            Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE_PACKAGE).putExtra(SearchManager.QUERY, query).putExtra("query", query),
            "YouTube'da \"$query\" aranıyor.", "",
        )
        if (r.success) return r
        return openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(query))
    }

    fun openGallery(): ActionResult {
        val r = start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY), "Galeri açıldı.", "")
        if (r.success) return r
        val r2 = start(Intent(Intent.ACTION_VIEW).setType("image/*"), "Galeri açıldı.", "")
        if (r2.success) return r2
        return when (val m = findApp("galeri")) {
            is AppMatch.Found -> launch(m.app)
            else -> ActionResult(false, "Bu cihazda bir galeri uygulaması bulamadım.")
        }
    }

    /** Metni panoya kopyalar. Android 13+ sistem, kopyalandığını ayrıca gösterir. */
    fun copyToClipboard(text: String, label: String = "JARVIS"): ActionResult = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        ActionResult(true, "Metni panoya kopyaladım; istediğin yere yapıştırabilirsin.")
    } catch (e: Exception) {
        ActionResult(false, "Metni panoya kopyalayamadım.")
    }

    fun openUrl(url: String): ActionResult =
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Bağlantı açıldı.", "Bu bağlantıyı açabilecek bir uygulama bulunamadı.")

    fun navigate(query: String): ActionResult {
        val r = start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))), "\"$query\" için harita açıldı.", "")
        if (r.success) return r
        return openUrl("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query))
    }

    /** Yalnızca arama ekranını numarayla açar; aramayı kullanıcı başlatır (CALL_PHONE izni kullanılmaz). */
    fun dial(number: String): ActionResult =
        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), "Arama ekranı $number numarasıyla açıldı. Aramak için yeşil düğmeye bas.", "Arama ekranı açılamadı.")

    /** Metni hazırlar ve paylaşım ekranını açar. Gönderme işlemini kullanıcı yapar. */
    fun shareText(text: String, targetApp: AppInfo?): ActionResult {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (targetApp != null) {
            val direct = Intent(send).setPackage(targetApp.packageName)
            val r = start(direct, "Metin ${targetApp.label} içinde hazırlandı. Göndermeyi sen onayla.", "")
            if (r.success) return r
        }
        return start(Intent.createChooser(send, "Paylaş"), "Paylaşım ekranı açıldı. Hedefi ve göndermeyi sen seç.", "Paylaşım ekranı açılamadı.")
    }

    fun setAlarm(hour: Int, minute: Int): ActionResult = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "SRİUS JARVIS")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
        "Saat uygulamasında %02d:%02d alarmı hazırlandı.".format(hour, minute),
        "Bu cihazda alarm kurabilen bir saat uygulaması bulunamadı.",
    )

    fun setTimer(seconds: Int): ActionResult = start(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "SRİUS JARVIS")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
        "${formatDuration(seconds)} zamanlayıcı saat uygulamasına gönderildi.",
        "Bu cihazda zamanlayıcı kurabilen bir saat uygulaması bulunamadı.",
    )

    fun openSettings(target: String): ActionResult {
        val action = when (target) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            "app" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action)
        if (target == "app") intent.data = Uri.fromParts("package", context.packageName, null)
        if (target == "notifications") intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val r = start(intent, "Ayar ekranı açıldı.", "")
        return if (r.success) r else start(Intent(Settings.ACTION_SETTINGS), "Ayarlar açıldı.", "Ayarlar açılamadı.")
    }

    fun adjustVolume(up: Boolean): ActionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
        val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return when {
            after != before -> ActionResult(true, if (up) "Ses yükseltildi." else "Ses kısıldı.")
            up && after >= max -> ActionResult(true, "Ses zaten en yüksek seviyede.")
            !up && after <= 0 -> ActionResult(true, "Ses zaten kapalı.")
            else -> ActionResult(false, "Ses seviyesini değiştiremedim.")
        }
    }

    private fun start(intent: Intent, ok: String, fail: String): ActionResult = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ActionResult(true, ok)
    } catch (e: ActivityNotFoundException) {
        ActionResult(false, fail)
    } catch (e: SecurityException) {
        ActionResult(false, fail.ifBlank { "Android bu işleme izin vermedi." })
    }

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        fun formatDuration(seconds: Int): String {
            val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { "$it saat" },
                m.takeIf { it > 0 }?.let { "$it dakika" },
                s.takeIf { it > 0 }?.let { "$it saniye" },
            ).joinToString(" ").ifBlank { "0 saniye" }
        }
    }
}
