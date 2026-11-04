package com.codex.tvphone

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.Locale

/** The phrases Vosk is allowed to hear, and how a heard phrase maps to a command name. */
object CommandParser {
    private val defaults = linkedMapOf(
        "play" to listOf("شغل", "شعل", "كمل", "دير بلاي", "تشغيل"),
        "pause" to listOf("حبس", "وقف", "ستوب", "ايقاف مؤقت"),
        "next" to listOf("الموالي", "اللي بعده", "الجاي", "التالي"),
        "previous" to listOf("اللي قبله", "اللي فات", "الفايت", "السابق"),
        "forward" to listOf("قدم", "تقدم", "زيد دقيقة", "قدم دقيقة", "دقيقة لقدام", "تقديم دقيقة"),
        "back" to listOf("رجع", "تأخر", "دقيقة للور", "رجع دقيقة", "ارجاع دقيقة"),
        "volume_set" to listOf("الصوت", "مستوى الصوت"),
        "volume_up" to listOf("علي الصوت", "كبر الصوت", "زيد الصوت", "ارفع الصوت"),
        "volume_down" to listOf("هبط الصوت", "نقص الصوت", "صغر الصوت", "اخفض الصوت"),
        "mute" to listOf("اسكت", "سكت", "طفي الصوت", "كتم"),
        "unmute" to listOf("رجع الصوت", "رد الصوت", "الغ الكتم"),
        "open_youtube" to listOf("افتح يوتيوب", "حل يوتيوب", "افتح يوتوب", "شغل يوتيوب")
    )

    private val commandNames = defaults.keys
    private val tens = mapOf(20 to "عشرين", 30 to "ثلاثين", 40 to "اربعين", 50 to "خمسين", 60 to "ستين", 70 to "سبعين", 80 to "ثمانين", 90 to "تسعين")
    private val ones = listOf("", "واحد", "اثنين", "ثلاثة", "اربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة")
    private val teens = mapOf(10 to "عشرة", 11 to "حداش", 12 to "اثناش", 13 to "ثلطاش", 14 to "اربعطاش", 15 to "خمستاش", 16 to "ستاش", 17 to "سبعتاش", 18 to "ثمنطاش", 19 to "تسعتاش")
    private fun file(context: Context) = File(context.filesDir, "commands.txt")

    fun defaultText(): String = defaults.entries.joinToString("\n") { (command, list) -> "$command=${list.joinToString(" | ")}" }

    fun readText(context: Context): String {
        val f = file(context)
        if (!f.exists()) f.writeText(defaultText(), Charsets.UTF_8)
        return f.readText(Charsets.UTF_8)
    }

    fun saveText(context: Context, text: String) {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        require(lines.isNotEmpty()) { "أضف أمرًا واحدًا على الأقل" }
        for (line in lines) {
            require(line.contains('=')) { "استخدم الصيغة command=عبارة | عبارة" }
            require(line.substringBefore('=').trim() in commandNames) { "اسم أمر غير معروف: ${line.substringBefore('=')}" }
            require(line.substringAfter('=').split('|').any { it.isNotBlank() }) { "أضف عبارة بعد علامة =" }
        }
        parseEntries(text)
        file(context).writeText(text, Charsets.UTF_8)
    }

    private fun parseEntries(text: String): List<Pair<String, List<String>>> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .mapNotNull { line ->
            val command = line.substringBefore('=').trim()
            if (command !in commandNames) return@mapNotNull null
            val phrases = line.substringAfter('=').split('|').map { normalize(it) }.filter { it.isNotBlank() }
            if (phrases.isEmpty()) null else command to phrases
        }.toList().ifEmpty { defaults.entries.map { it.key to it.value.map(::normalize) } }

    /** Each phrase alone and after the wake word, the wake word alone, and [unk] so noise is not forced into a command. */
    fun grammar(context: Context, wake: String): String {
        val array = JSONArray()
        for ((command, phrases) in parseEntries(readText(context))) {
            for (p in phrases) {
                if (command !in setOf("volume_set", "volume_up", "volume_down")) {
                    array.put(p); array.put("$wake $p")
                } else if (phrases.take(2).contains(p)) {
                    for (n in 0..100) {
                        val word = numberWord(n)
                        val variants = (if (word == n.toString()) listOf(word) else listOf(word, n.toString())) + if (n == 100) listOf("مئة") else emptyList()
                        for (number in variants) { val numeric = "$p $number"; array.put(numeric); array.put("$wake $numeric") }
                    }
                }
            }
        }
        array.put(wake)
        array.put("[unk]")
        return array.toString()
    }

    fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\p{Punct}،؛؟]+"), " ")
        .replace(Regex("[٠-٩]")) { (it.value[0].code - '٠'.code).toString() }
        .replace(Regex("\\s+"), " ").trim()

    private fun numberWord(n: Int): String = when {
        n == 0 -> "صفر"
        n < 10 -> ones[n]
        n < 20 -> teens[n] ?: n.toString()
        n == 100 -> "مية"
        n % 10 == 0 -> tens[n] ?: n.toString()
        else -> "${ones[n % 10]} و${tens[n / 10 * 10]}"
    }

    private fun numberValue(raw: String): Int? {
        val number = normalize(raw)
        number.toIntOrNull()?.let { return it.takeIf { value -> value in 0..100 } }
        return (0..100).firstOrNull { n -> number == numberWord(n) || (n == 100 && number == "مئة") }
    }

    /** [text] must already be normalized and without the wake word. */
    fun parse(context: Context, text: String): String? {
        val normalized = normalize(text)
        val entries = parseEntries(readText(context))
        for (command in listOf("volume_set", "volume_up", "volume_down")) {
            for (prefix in entries.firstOrNull { it.first == command }?.second.orEmpty().sortedByDescending { it.length }) {
                if (normalized.startsWith("$prefix ")) {
                    val value = numberValue(normalized.removePrefix("$prefix ").trim())
                    if (value != null) return "${command}_$value"
                }
            }
        }
        return entries.filter { it.first !in setOf("volume_set", "volume_up", "volume_down") }
            .flatMap { (command, phrases) -> phrases.map { it to command } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (phrase, _) -> normalized == phrase || normalized.contains(" $phrase ") || normalized.startsWith("$phrase ") || normalized.endsWith(" $phrase") }
            ?.second
    }
}
