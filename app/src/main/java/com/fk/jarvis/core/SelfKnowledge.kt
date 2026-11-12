package com.fk.jarvis.core

/**
 * Çalışma anında öz-bilgi: asistanın o an gerçekten yapabildikleri ve yapamadıkları, yazılı bir listeden değil
 * telefonun canlı durumundan (izinler, seçili sağlayıcı, erişilebilirlik) üretilir. Böylece asistan sahip olmadığı
 * bir yeteneği iddia etmez; eksik olan şeyin nasıl açılacağını söyler.
 */
object SelfKnowledge {
    data class State(
        val assistantName: String,
        val providerName: String?,          // null: yapay zekâ sağlayıcısı ayarlı değil
        val research: Boolean,
        val vision: Boolean,
        val liveReady: Boolean,
        val mic: Boolean,
        val notifications: Boolean,
        val calendarRead: Boolean,
        val calendarWrite: Boolean,
        val screenControlBuild: Boolean,
        val screenControlOn: Boolean,
        val ttsReady: Boolean,
        val memoryCount: Int,
        val openTasks: Int,
        val routines: Int,
    )

    fun can(s: State): List<String> = buildList {
        add("uygulama açmak, YouTube ve internette aramak, alarm ve zamanlayıcı kurmak, ses seviyesini ayarlamak")
        add("görev, not, hatırlatıcı ve rutinlerini tutmak" + if (s.openTasks + s.routines > 0) " (şu an ${s.openTasks} açık görev, ${s.routines} rutin var)" else "")
        add("söylediklerini kalıcı hafızada saklamak" + if (s.memoryCount > 0) " (şu an ${s.memoryCount} bilgi hatırlıyorum)" else "")
        if (s.calendarRead) add("takvimini okumak" + if (s.calendarWrite) " ve onayınla etkinlik eklemek" else "")
        if (s.providerName != null) {
            add("${s.providerName} ile sohbet etmek, metin taslağı hazırlamak ve çok adımlı ajan görevleri yürütmek")
            if (s.research) add("internette güncel araştırma yapmak ve haberleri özetlemek")
            if (s.vision) add("kamerayla çektiğin fotoğrafa bakıp yorumlamak")
        }
        if (s.liveReady) add("gerçek zamanlı sesli sohbet (canlı mod)")
        if (s.screenControlOn) add("ekranda geri gitmek, kaydırmak, bir öğeye dokunmak ve onayınla yazı yazmak")
    }

    fun cannot(s: State): List<String> = buildList {
        if (!s.mic) add("mikrofon izni olmadığı için seni duyamıyorum; yazarak komut verebilirsin")
        if (!s.ttsReady) add("Türkçe seslendirme hazır değil; yanıtlarım yalnızca ekranda görünür")
        if (s.providerName == null) add("yapay zekâ sağlayıcısı ayarlı değil; sohbet, araştırma ve ajan için MENÜ'den SUNUCU bölmesine anahtar girmelisin")
        else {
            if (!s.research) add("seçili sağlayıcı internet araştırmasını desteklemiyor")
            if (!s.vision) add("seçili sağlayıcıyla görüntü analizi yapamıyorum")
        }
        if (!s.calendarRead) add("takvim izni verilmediği için ajandanı okuyamıyorum")
        if (!s.notifications) add("bildirim izni olmadığı için hatırlatıcı bildirimi gösteremiyorum")
        if (!s.screenControlBuild) add("bu basit sürümde ekran kontrolü yok")
        else if (!s.screenControlOn) add("erişilebilirlik hizmeti kapalı olduğu için başka uygulamaların ekranına dokunamıyorum")
        add("başka uygulamaları tamamen kapatamam, senin yerine mesaj gönderemem ya da ödeme yapamam; bunları hazırlarım, son adımı sen atarsın")
        add("ekranı ya da kamerayı sürekli izlemem; yalnızca sen istediğinde tek bir kare görürüm")
    }

    /** Sesli okunacak kısa özet. */
    fun describe(s: State): String =
        "Ben ${s.assistantName}. Şu an yapabildiklerim: " + can(s).joinToString("; ") + ". " +
            "Sınırlarım: " + cannot(s).joinToString("; ") + "."
}
