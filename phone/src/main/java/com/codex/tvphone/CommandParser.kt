package com.codex.tvphone

import org.json.JSONArray
import java.util.Locale

/** The phrases Vosk is allowed to hear, and how a heard phrase maps to a command name. */
object CommandParser {
    private val phrases = listOf(
        "تشغيل", "ابدأ", "ابدأ التشغيل", "إيقاف", "توقف", "أوقف",
        "التالي", "الفيديو التالي", "السابق", "الفيديو السابق",
        "تقديم دقيقة", "قدم دقيقة", "إرجاع دقيقة", "ارجع دقيقة",
        "ارفع الصوت", "علي الصوت", "اخفض الصوت", "خفض الصوت",
        "كتم", "اكتم الصوت", "ألغ الكتم", "إلغاء الكتم",
        "افتح يوتيوب", "افتح يوتوب", "افتح اليوتيوب", "شغل يوتيوب"
    )

    /** Each phrase alone and after the wake word, the wake word alone, and [unk] so noise is not forced into a command. */
    fun grammar(wake: String): String {
        val array = JSONArray()
        for (p in phrases) { array.put(p); array.put("$wake $p") }
        array.put(wake)
        array.put("[unk]")
        return array.toString()
    }

    fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        .replace(Regex("\\s+"), " ").trim()

    private fun isYoutube(t: String) = t.contains("يوتيوب") || t.contains("يوتوب") || t.contains("يوتيب")

    /** [text] must already be normalized and without the wake word. */
    fun parse(text: String): String? = when {
        isYoutube(text) -> "open_youtube"
        text.contains("التالي") -> "next"
        text.contains("السابق") -> "previous"
        text.contains("تقديم دقيقة") || text.contains("قدم دقيقة") -> "forward"
        text.contains("ارجاع دقيقة") || text.contains("ارجع دقيقة") -> "back"
        text.contains("ارفع الصوت") || text.contains("علي الصوت") -> "volume_up"
        text.contains("اخفض الصوت") || text.contains("خفض الصوت") -> "volume_down"
        text.contains("الغ الكتم") || text.contains("الغاء الكتم") -> "unmute"
        text == "كتم" || text.contains("اكتم الصوت") -> "mute"
        text.contains("تشغيل") || text.contains("ابدا") -> "play"
        text.contains("ايقاف") || text.contains("توقف") || text.contains("اوقف") -> "pause"
        else -> null
    }
}
