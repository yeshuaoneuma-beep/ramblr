package com.trevornk.ramblr

/**
 * Deterministic pre-cleanup and raw-delivery resolver for explicit spoken emoji commands
 * (e.g. `"three broken heart emojis"` -> `"💔💔💔"`, `"one laughing tears emoji"` -> `"😂"`).
 *
 * Only triggers when the phrase explicitly ends with `"emoji"` or `"emojis"` (case-insensitive)
 * so ordinary prose like `"three hearts beat"` is never corrupted. Pure Kotlin, zero Android
 * dependency.
 */
object SpokenEmojiResolver {

    private val COUNT_WORDS: Map<String, Int> = mapOf(
        "a" to 1,
        "an" to 1,
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
    )

    private val EMOJI_MAP: Map<String, String> = linkedMapOf(
        "broken heart" to "\uD83D\uDC94",
        "broken hearts" to "\uD83D\uDC94",
        "red heart" to "\u2764\uFE0F",
        "red hearts" to "\u2764\uFE0F",
        "heart eyes" to "\uD83D\uDE0D",
        "heart" to "\u2764\uFE0F",
        "hearts" to "\u2764\uFE0F",
        "laughing tears" to "\uD83D\uDE02",
        "tears of joy" to "\uD83D\uDE02",
        "crying laughing" to "\uD83D\uDE02",
        "laughing crying" to "\uD83D\uDE02",
        "laughing" to "\uD83D\uDE02",
        "rolling laughing" to "\uD83E\uDD23",
        "rofl" to "\uD83E\uDD23",
        "fire" to "\uD83D\uDD25",
        "fires" to "\uD83D\uDD25",
        "thumbs up" to "\uD83D\uDC4D",
        "thumbs down" to "\uD83D\uDC4E",
        "clapping" to "\uD83D\uDC4F",
        "clap" to "\uD83D\uDC4F",
        "claps" to "\uD83D\uDC4F",
        "folded hands" to "\uD83D\uDE4F",
        "praying" to "\uD83D\uDE4F",
        "hundred" to "\uD83D\uDCAF",
        "hundreds" to "\uD83D\uDCAF",
        "100" to "\uD83D\uDCAF",
        "sweat smile" to "\uD83D\uDE05",
        "smiling" to "\uD83D\uDE0A",
        "smile" to "\uD83D\uDE0A",
        "smiles" to "\uD83D\uDE0A",
        "winking" to "\uD83D\uDE09",
        "wink" to "\uD83D\uDE09",
        "winks" to "\uD83D\uDE09",
        "crying" to "\uD83D\uDE22",
        "sad" to "\uD83D\uDE22",
        "angry" to "\uD83D\uDE21",
        "eyes" to "\uD83D\uDC40",
        "check mark" to "\u2705",
        "check marks" to "\u2705",
        "checkmark" to "\u2705",
        "checkmarks" to "\u2705",
        "sparkles" to "\u2728",
        "sparkle" to "\u2728",
        "party" to "\uD83C\uDF89",
        "celebration" to "\uD83C\uDF89",
        "muscle" to "\uD83D\uDCAA",
        "muscles" to "\uD83D\uDCAA",
        "flex" to "\uD83D\uDCAA",
        "rocket" to "\uD83D\uDE80",
        "rockets" to "\uD83D\uDE80",
        "star" to "\u2B50",
        "stars" to "\u2B50",
        "skull" to "\uD83D\uDC80",
        "skulls" to "\uD83D\uDC80",
        "thinking" to "\uD83E\uDD14",
        "ok hand" to "\uD83D\uDC4C",
        "okay" to "\uD83D\uDC4C",
        "waving" to "\uD83D\uDC4B",
        "wave" to "\uD83D\uDC4B",
        "waves" to "\uD83D\uDC4B",
        "rose" to "\uD83C\uDF39",
        "roses" to "\uD83C\uDF39",
        "kiss" to "\uD83D\uDE18",
        "kisses" to "\uD83D\uDE18",
        "sunglasses" to "\uD83D\uDE0E",
        "cool" to "\uD83D\uDE0E",
    )

    private val NAME_ALTERNATION: String = EMOJI_MAP.keys
        .sortedByDescending { it.length }
        .joinToString("|") { key ->
            key.split(' ').joinToString("[\\s-]+") { Regex.escape(it) }
        }

    private val COUNT_ALTERNATION: String =
        (COUNT_WORDS.keys.sortedByDescending { it.length } + "\\d+").joinToString("|")

    private val COMMAND_REGEX = Regex(
        "\\b(?:($COUNT_ALTERNATION)\\s+)?($NAME_ALTERNATION)\\s+emojis?\\b",
        RegexOption.IGNORE_CASE,
    )

    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val MULTI_HYPHEN_OR_SPACE = Regex("[\\s-]+")

    fun resolve(text: String): String {
        if (text.isBlank()) return text
        var replaced = false
        val rawResolved = COMMAND_REGEX.replace(text) { match ->
            val rawCount = match.groupValues[1].trim().lowercase()
            val rawName = MULTI_HYPHEN_OR_SPACE.replace(match.groupValues[2].trim().lowercase(), " ")
            val emoji = EMOJI_MAP[rawName] ?: return@replace match.value
            val count = if (rawCount.isEmpty()) {
                1
            } else {
                (COUNT_WORDS[rawCount] ?: rawCount.toIntOrNull() ?: 1).coerceIn(1, 10)
            }
            replaced = true
            emoji.repeat(count)
        }
        if (!replaced) return text

        val normalized = MULTI_SPACE.replace(rawResolved, " ").trim()
        if (normalized.isEmpty()) return text

        // ASR commonly appends a trailing period to standalone commands ("One laughing tears emoji.");
        // strip it only when the entire resolved output is emojis plus that trailing period.
        val withoutTrailingDot = if (normalized.endsWith('.') && !normalized.endsWith("..")) {
            val candidate = normalized.dropLast(1).trimEnd()
            if (candidate.isNotEmpty() && isOnlyEmojiAndWhitespace(candidate)) candidate else normalized
        } else {
            normalized
        }
        return withoutTrailingDot.ifBlank { text }
    }

    private fun isOnlyEmojiAndWhitespace(text: String): Boolean {
        var hasEmoji = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) -> Unit
                isEmojiCodePoint(cp) -> hasEmoji = true
                else -> return false
            }
        }
        return hasEmoji
    }

    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2B50..0x2B55 ||
            cp == 0xFE0F ||
            cp == 0x200D ||
            cp == 0x20E3
}
