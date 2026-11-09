package com.fk.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Google Play "açık bilgilendirme ve onay" (prominent disclosure) metinleri. */
object Disclosures {
    const val FIRST_RUN = """SRİUS JARVIS sesli bir asistandır. Kullanmaya başlamadan önce verilerinin nasıl kullanıldığını bilmelisin:

• Mikrofon: yalnızca KONUŞ'a bastığında, asistan sana soru sorduktan sonra ya da sen açarsan "JARVIS" uyandırma sözcüğü için kullanılır. Ses kayıtları saklanmaz. Konuşma, telefonundaki konuşma tanıma hizmeti (çoğunlukla Google) tarafından metne çevrilir.

• Yapay zekâ: telefonun kendi başına anlayamadığı isteklerde, söylediğin cümlenin METNİ, son birkaç konuşma satırı ve saat bilgisi, Ayarlar'da seçtiğin sağlayıcıya (Google Gemini, OpenAI, Anthropic Claude ya da kendi yerel sunucun) gönderilir. "Yalnızca cihaz içi" seçeneğinde hiçbir şey gönderilmez.

• Takvim: yalnızca "bugün ne yapmam gerekiyor" diye sorduğunda, iznin istenerek bugünün etkinlikleri okunur; dışarı gönderilmez.

• Ekran kontrolü (erişilebilirlik): yalnızca sen ayrıca açarsan kullanılır; ayrı bir onay ekranı gösterilir.

• API anahtarların telefonda şifreli saklanır. Konuşma geçmişi (yalnızca metin) cihazda tutulur, Ayarlar'dan silinebilir. Parolalar kaydedilmez.

• Mesaj gönderme, paylaşım, ödeme, silme gibi işlemler senin onayın olmadan yapılmaz."""

    const val ACCESSIBILITY = """SRİUS JARVIS, Android Erişilebilirlik Hizmeti API'sini (AccessibilityService) kullanarak sesli komutlarınla telefonunda işlem yapabilir:

• Geri gitme, ana ekran, son uygulamalar, bildirimleri açma
• Ekranı kaydırma, ekrandaki bir yazıya dokunma
• Seçili metin alanına yazı yazma, seçili metni kopyalama
• Ekran görüntüsü alma

Bunun için hizmet, YALNIZCA sen bir komut verdiğinde, o anda öndeki ekranın içeriğini (yazılar ve düğmeler) okur. Ekran içeriği kaydedilmez, saklanmaz ve hiçbir sunucuya ya da yapay zekâ sağlayıcısına gönderilmez. Arka planda ekran izlenmez.

Gönder/öde/sil gibi sonuç doğuran dokunuşlar ve yazı yazma her zaman senin onayını ister; parola alanlarına yazılmaz. Hizmeti dilediğin an Ayarlar > Erişilebilirlik'ten kapatabilirsin.

"Kabul ediyorum" dersen Android Erişilebilirlik ayarları açılır; hizmeti orada sen etkinleştirirsin."""
}

@Composable
fun DisclosureDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("VAZGEÇ") } },
    )
}

/** İlk açılış gizlilik ve izin bilgilendirmesi; kabul edilmeden mikrofon kullanılmaz. */
@Composable
fun ConsentScreen(onAccept: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xF5000306)).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Gizlilik ve izinler", color = JarvisColors.text, fontSize = 22.sp)
        Text(Disclosures.FIRST_RUN, color = JarvisColors.text, fontSize = 14.sp)
        SmallButton("KABUL EDİYORUM, DEVAM ET", JarvisColors.gold) { onAccept() }
    }
}
