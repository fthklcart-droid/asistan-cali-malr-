package com.fk.jarvis.core

import com.fk.jarvis.apps.AppInfo

/** Avatar ve arayüzün izlediği asistan durumları (şartname §9). */
enum class AssistantState { IDLE, LISTENING, PROCESSING, SPEAKING, EXECUTING, CONFIRMING, ERROR }

data class AssistantUi(
    val state: AssistantState = AssistantState.IDLE,
    val partialTranscript: String = "",
    val transcript: String = "",
    val response: String = "",
    val error: String? = null,
    val confirmQuestion: String? = null,
    val appChoices: List<AppInfo> = emptyList(),
    val micDenied: Boolean = false,
    /** Son yanıt bulutta (yapay zekâ sağlayıcısı) mı üretildi, cihazda mı? */
    val lastAnswerFromCloud: Boolean = false,
    /** Hazırlanan metin taslağı (ör. Instagram paylaşım metni); ekranda gösterilir, panoya kopyalanır. */
    val draft: String? = null,
    /** Arayüzün kullanıcıdan istemesi gereken Android izni (ör. takvim). */
    val permissionRequest: String? = null,
)
