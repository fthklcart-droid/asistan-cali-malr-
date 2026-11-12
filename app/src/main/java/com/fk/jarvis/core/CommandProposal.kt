package com.fk.jarvis.core

import org.json.JSONObject

/**
 * İzin listesindeki eylemler. LLM yalnızca bu adlardan birini önerebilir;
 * listede olmayan her şey [UNKNOWN] olur ve çalıştırılmaz.
 */
enum class CommandIntent(val wire: String) {
    CHAT("chat"),
    CLARIFY("clarify"),
    OPEN_APP("open_app"),
    GO_HOME("go_home"),
    WEB_SEARCH("web_search"),
    OPEN_URL("open_url"),
    NAVIGATE("navigate"),
    DIAL("dial"),
    SHARE_TEXT("share_text"),
    COMPOSE_MESSAGE("compose_message"),
    SET_ALARM("set_alarm"),
    SET_TIMER("set_timer"),
    VOLUME_UP("volume_up"),
    VOLUME_DOWN("volume_down"),
    TELL_TIME("tell_time"),
    TELL_DATE("tell_date"),
    REPEAT_LAST("repeat_last"),
    OPEN_SETTINGS("open_settings"),
    OPEN_CAMERA("open_camera"),
    SCREEN_ACTION("screen_action"),
    YOUTUBE_SEARCH("youtube_search"),
    OPEN_GALLERY("open_gallery"),
    CLOSE_APP("close_app"),
    SCREENSHOT("screenshot"),
    COPY_TEXT("copy_text"),
    DRAFT_TEXT("draft_text"),
    TODAY_AGENDA("today_agenda"),
    RESEARCH("research"),
    CALENDAR_ADD("calendar_add"),
    AGENT_TASK("agent_task"),
    REMEMBER("remember"),
    RECALL("recall"),
    FORGET("forget"),
    UNDO("undo"),
    DEVICE_STATUS("device_status"),
    MONITOR("monitor"),
    CAMERA_LOOK("camera_look"),
    TASK_ADD("task_add"),
    TASK_LIST("task_list"),
    TASK_DONE("task_done"),
    NOTE_ADD("note_add"),
    NOTE_SEARCH("note_search"),
    ROUTINE_ADD("routine_add"),
    ROUTINE_LIST("routine_list"),
    ROUTINE_REMOVE("routine_remove"),
    CAPABILITIES("capabilities"),
    END_SESSION("end_session"),
    STOP("stop"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): CommandIntent =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: UNKNOWN

        /** LLM'e gösterilen izinli eylem adları (UNKNOWN hariç). */
        val allowedWireNames: List<String> = entries.filter { it != UNKNOWN }.map { it.wire }
    }
}

/**
 * Yapılandırılmış komut önerisi. Hem yerel sınıflandırıcı hem de LLM bu tipi üretir.
 * Öneri asla doğrudan çalıştırılmaz: önce [SafetyPolicy] ile doğrulanır.
 */
data class CommandProposal(
    val intent: CommandIntent,
    val target: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false,
    val spokenResponse: String = "",
    val source: Source = Source.LOCAL,
) {
    enum class Source { LOCAL, LLM }

    fun arg(name: String): String? = arguments[name]?.takeIf { it.isNotBlank() }

    companion object {
        /**
         * LLM'in döndürdüğü JSON metnini ayrıştırır. Bozuk JSON'da istisna fırlatır;
         * çağıran taraf bunu anlaşılır bir hataya dönüştürür.
         */
        fun fromJson(text: String): CommandProposal {
            // Bazı modeller JSON'u açıklama ya da kod bloğu içine koyar: ilk '{' ile son '}' arası alınır.
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            require(start >= 0 && end > start) { "no json object" }
            val o = JSONObject(text.substring(start, end + 1))
            val args = mutableMapOf<String, String>()
            o.optJSONObject("arguments")?.let { a ->
                val keys = a.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = a.opt(k)
                    if (v != null && v != JSONObject.NULL) args[k] = v.toString()
                }
            }
            return CommandProposal(
                intent = CommandIntent.fromWire(o.optString("intent", "")),
                target = o.optString("target", "").ifBlank { null },
                arguments = args,
                requiresConfirmation = o.optBoolean("requires_confirmation", false),
                spokenResponse = o.optString("spoken_response", ""),
                source = Source.LLM,
            )
        }
    }
}
