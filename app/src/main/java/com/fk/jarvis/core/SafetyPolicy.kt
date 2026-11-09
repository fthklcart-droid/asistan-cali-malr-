package com.fk.jarvis.core

import java.net.URI

/** Bir komut önerisi için güvenlik kararı. */
sealed class SafetyDecision {
    /** Doğrulandı, onaysız çalıştırılabilir. [proposal] temizlenmiş/normalize edilmiş hâlidir. */
    data class Allow(val proposal: CommandProposal) : SafetyDecision()

    /** Çalıştırmadan önce kullanıcıdan açık onay gerekir. */
    data class Confirm(val proposal: CommandProposal, val question: String) : SafetyDecision()

    /** Çalıştırılmaz; [reason] kullanıcıya söylenir. */
    data class Reject(val reason: String) : SafetyDecision()
}

/**
 * İzin listesi + argüman doğrulaması + onay kuralları.
 * Model çıktısındaki talimatlar bu kuralları gevşetemez: model yalnızca onayı
 * zorunlu hâle getirebilir (requires_confirmation=true), kaldıramaz.
 */
object SafetyPolicy {

    val settingsTargets = setOf("wifi", "bluetooth", "display", "sound", "battery", "location", "accessibility", "general", "app")
    val screenActions = setOf("back", "home", "recents", "notifications", "scroll_down", "scroll_up", "click", "type")

    /** Bu sözcükleri içeren ekran etkileşimleri her zaman onay ister. */
    val sensitiveWords = listOf(
        "gönder", "öde", "ödeme", "satın", "sipariş", "onayla", "sil", "kaldır", "paylaş", "yayınla",
        "transfer", "havale", "eft", "fast", "şifre", "parola", "hesab", "abonelik", "kart", "send", "pay",
        "buy", "delete", "remove", "share", "post", "publish", "confirm", "password", "checkout",
    )

    fun isSensitiveText(text: String): Boolean {
        val t = text.lowercase(TextNormalizer.TR)
        return sensitiveWords.any { t.contains(it) }
    }

    fun evaluate(p: CommandProposal): SafetyDecision {
        val modelWantsConfirm = p.requiresConfirmation
        return when (p.intent) {
            CommandIntent.UNKNOWN -> SafetyDecision.Reject("Bu isteği izin verilen bir komuta dönüştüremedim.")

            CommandIntent.CHAT, CommandIntent.CLARIFY, CommandIntent.TELL_TIME, CommandIntent.TELL_DATE,
            CommandIntent.REPEAT_LAST, CommandIntent.STOP, CommandIntent.VOLUME_UP, CommandIntent.VOLUME_DOWN,
            CommandIntent.GO_HOME, CommandIntent.OPEN_CAMERA -> allowOrConfirm(p, modelWantsConfirm)

            CommandIntent.OPEN_APP -> {
                if (p.arg("app_name") == null && p.target.isNullOrBlank()) SafetyDecision.Reject("Hangi uygulamayı açmamı istediğini anlayamadım.")
                else allowOrConfirm(p, modelWantsConfirm)
            }

            CommandIntent.WEB_SEARCH, CommandIntent.NAVIGATE -> {
                val q = p.arg("query") ?: return SafetyDecision.Reject("Ne aramamı istediğini anlayamadım.")
                if (q.length > 300) return SafetyDecision.Reject("Arama metni çok uzun.")
                allowOrConfirm(p, modelWantsConfirm)
            }

            CommandIntent.OPEN_URL -> {
                val url = sanitizeUrl(p.arg("url") ?: p.target)
                    ?: return SafetyDecision.Reject("Bu bağlantıyı güvenli bulmadım; yalnızca http ve https bağlantıları açabilirim.")
                allowOrConfirm(p.copy(arguments = p.arguments + ("url" to url)), modelWantsConfirm)
            }

            CommandIntent.DIAL -> {
                val num = (p.arg("number") ?: p.target)?.replace(Regex("[\\s()-]"), "")
                if (num == null || !Regex("^\\+?\\d{3,15}$").matches(num)) SafetyDecision.Reject("Geçerli bir telefon numarası bulamadım.")
                else allowOrConfirm(p.copy(arguments = p.arguments + ("number" to num)), modelWantsConfirm)
            }

            CommandIntent.SET_TIMER -> {
                val s = p.arg("seconds")?.toDoubleOrNull()?.toInt()
                if (s == null || s !in 1..86_400) SafetyDecision.Reject("Zamanlayıcı süresi 1 saniye ile 24 saat arasında olmalı.")
                else allowOrConfirm(p.copy(arguments = p.arguments + ("seconds" to s.toString())), modelWantsConfirm)
            }

            CommandIntent.SET_ALARM -> {
                val h = p.arg("hour")?.toDoubleOrNull()?.toInt()
                val m = p.arg("minute")?.toDoubleOrNull()?.toInt() ?: 0
                if (h == null || h !in 0..23 || m !in 0..59) SafetyDecision.Reject("Alarm saati geçerli değil.")
                else allowOrConfirm(p.copy(arguments = p.arguments + mapOf("hour" to h.toString(), "minute" to m.toString())), modelWantsConfirm)
            }

            CommandIntent.OPEN_SETTINGS -> {
                val t = (p.target ?: p.arg("setting") ?: "general").lowercase()
                if (t !in settingsTargets) SafetyDecision.Reject("Bu ayar ekranını açma iznim yok.")
                else allowOrConfirm(p.copy(target = t), modelWantsConfirm)
            }

            CommandIntent.SHARE_TEXT, CommandIntent.COMPOSE_MESSAGE -> {
                val text = p.arg("text") ?: return SafetyDecision.Reject("Paylaşılacak metni anlayamadım.")
                if (text.length > 2000) return SafetyDecision.Reject("Metin çok uzun.")
                val where = p.arg("app_name")?.let { " $it uygulamasında" } ?: ""
                SafetyDecision.Confirm(p, "Şu metni$where hazırlayacağım: \"$text\". Göndermeyi sen yapacaksın. Onaylıyor musun?")
            }

            CommandIntent.SCREEN_ACTION -> {
                val action = p.arg("action")?.lowercase()
                if (action == null || action !in screenActions) return SafetyDecision.Reject("Bu ekran işlemine izin verilmiyor.")
                when (action) {
                    "type" -> {
                        val text = p.arg("text") ?: return SafetyDecision.Reject("Yazılacak metni anlayamadım.")
                        SafetyDecision.Confirm(p, "Ekrandaki seçili alana \"$text\" yazacağım. Onaylıyor musun?")
                    }
                    "click" -> {
                        val text = p.arg("text") ?: return SafetyDecision.Reject("Neye dokunacağımı anlayamadım.")
                        if (isSensitiveText(text) || modelWantsConfirm) SafetyDecision.Confirm(p, "\"$text\" öğesine dokunacağım. Bu sonuç doğurabilecek bir işlem. Onaylıyor musun?")
                        else SafetyDecision.Allow(p)
                    }
                    else -> allowOrConfirm(p, modelWantsConfirm)
                }
            }
        }
    }

    private fun allowOrConfirm(p: CommandProposal, confirm: Boolean): SafetyDecision =
        if (confirm) SafetyDecision.Confirm(p, (p.spokenResponse.ifBlank { "Bu işlemi yapacağım." }) + " Onaylıyor musun?")
        else SafetyDecision.Allow(p)

    /** Yalnızca http/https; şema yoksa https eklenir. Diğer şemalar (intent:, file:, javascript:) reddedilir. */
    fun sanitizeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var u = raw.trim()
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) u = "https://$u"
        return try {
            val uri = URI(u)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) u else null
        } catch (e: Exception) {
            null
        }
    }

    /** Onay yanıtını yorumlar: true=evet, false=hayır, null=anlaşılmadı. */
    fun parseYesNo(raw: String): Boolean? {
        val n = TextNormalizer.normalize(raw)
        val yes = listOf("evet", "onaylıyorum", "onayla", "tamam", "olur", "yap", "devam", "kabul", "evet onaylıyorum")
        val no = listOf("hayır", "iptal", "vazgeç", "yapma", "istemiyorum", "dur", "olmaz", "reddet")
        if (no.any { n == it || n.startsWith("$it ") }) return false
        if (yes.any { n == it || n.startsWith("$it ") }) return true
        return null
    }
}
