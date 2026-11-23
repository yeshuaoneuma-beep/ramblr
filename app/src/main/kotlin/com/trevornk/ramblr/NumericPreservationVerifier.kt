package com.trevornk.ramblr

/** Result of exact, ordered semantic numeric preservation after local-model cleanup. */
sealed class NumericPreservation {
    object Valid : NumericPreservation()
    data class Rejected(val detail: String) : NumericPreservation()
}

/**
 * Verifies that local cleanup returned exactly the normalized numeric sequence it received (#155).
 * Formatting is not identity: a ten-digit phone value may gain parentheses, spaces, or hyphens,
 * and a number may come back spelled as a word when it reads as the same value (#325).
 * Values may not change, disappear, duplicate, or reorder. Diagnostics contain counts only, never
 * transcript or model output.
 */
object NumericPreservationVerifier {
    fun verify(
        normalization: SpokenNumberNormalization,
        modelOutput: String,
    ): NumericPreservation {
        val actual = NumericValueExtractor.extract(modelOutput)
        if (actual == normalization.semanticValues) return NumericPreservation.Valid
        // The model may hand a small number back as a word ("3 times" -> "three times", "1st" ->
        // "first"): same value, different spelling. Read the output through the same normalizer
        // the input went through and require the identical ordered values. A changed, dropped,
        // duplicated or reordered number still fails, because it changes the normalized values too.
        val normalizedOutput = SpokenNumberNormalizer.normalize(modelOutput)
        if (normalizedOutput.semanticValues == normalization.semanticValues) {
            return NumericPreservation.Valid
        }
        if (matchesWithSpokenEquivalences(normalization.text, normalizedOutput.text)) {
            return NumericPreservation.Valid
        }
        return NumericPreservation.Rejected(
            "numeric sequence diverged (expected ${normalization.semanticValues.size} value(s), " +
                "found ${actual.size})",
        )
    }

    private sealed class NumericSlot {
        data class Required(val value: String) : NumericSlot()
        data class Optional(val values: List<String>) : NumericSlot()
    }

    private sealed class TextItem {
        data class Word(val lower: String, val raw: String, val end: Int, val nextGapHasBreak: Boolean) : TextItem()
        data class Num(val values: List<String>, val end: Int, val nextGapHasBreak: Boolean) : TextItem()
    }

    private fun matchesWithSpokenEquivalences(normalizedInput: String, normalizedOutput: String): Boolean {
        val a = extractSlots(normalizedInput)
        val b = extractSlots(normalizedOutput)
        val m = a.size
        val n = b.size
        val memo = Array(m + 1) { ByteArray(n + 1) } // 0=unvisited, 1=false, 2=true

        fun solve(i: Int, j: Int): Boolean {
            if (i == m && j == n) return true
            val cached = memo[i][j].toInt()
            if (cached != 0) return cached == 2
            var ok = false
            if (i < m && a[i] is NumericSlot.Optional && solve(i + 1, j)) {
                ok = true
            }
            if (!ok && j < n && b[j] is NumericSlot.Optional && solve(i, j + 1)) {
                ok = true
            }
            if (!ok && i < m && j < n) {
                val sa = a[i]
                val sb = b[j]
                if (sa is NumericSlot.Required && sb is NumericSlot.Required && sa.value == sb.value) {
                    ok = solve(i + 1, j + 1)
                } else if (sa is NumericSlot.Optional && sb is NumericSlot.Optional && sa.values == sb.values) {
                    ok = solve(i + 1, j + 1)
                }
            }
            if (!ok && i < m && a[i] is NumericSlot.Optional) {
                val vals = (a[i] as NumericSlot.Optional).values
                val k = vals.size
                if (j + k <= n && (0 until k).all { idx -> (b[j + idx] as? NumericSlot.Required)?.value == vals[idx] }) {
                    ok = solve(i + 1, j + k)
                }
            }
            if (!ok && j < n && b[j] is NumericSlot.Optional) {
                val vals = (b[j] as NumericSlot.Optional).values
                val k = vals.size
                if (i + k <= m && (0 until k).all { idx -> (a[i + idx] as? NumericSlot.Required)?.value == vals[idx] }) {
                    ok = solve(i + k, j + 1)
                }
            }
            memo[i][j] = if (ok) 2 else 1
            return ok
        }

        return solve(0, 0)
    }

    private fun extractSlots(text: String): List<NumericSlot> {
        // Walk non-whitespace spans and numeric extractor matches in document order.
        val numMatches = NumericValueExtractor.extractMatches(text)
        val items = mutableListOf<TextItem>()
        var cursor = 0
        var matchIdx = 0
        for (token in TOKEN_REGEX.findAll(text)) {
            while (matchIdx < numMatches.size && numMatches[matchIdx].range.last < token.range.first) {
                matchIdx++
            }
            if (token.range.first < cursor) continue
            val m = numMatches.getOrNull(matchIdx)
            if (m != null && token.range.first <= m.range.last && m.range.first <= token.range.last) {
                // Collect every number match inside this span (e.g. "4:30" -> ["4", "30"]).
                val grouped = mutableListOf<String>()
                var lastEnd = token.range.last + 1
                while (matchIdx < numMatches.size && numMatches[matchIdx].range.first <= token.range.last) {
                    val nm = numMatches[matchIdx]
                    grouped += nm.values
                    lastEnd = maxOf(lastEnd, nm.range.last + 1)
                    matchIdx++
                }
                val trailingPunct = text.substring(token.range.first, lastEnd).any { it in SENTENCE_BREAK_CHARS }
                items += TextItem.Num(grouped, lastEnd, trailingPunct)
                cursor = lastEnd
                continue
            }
            val raw = token.value
            val lead = raw.indexOfFirst { it.isLetterOrDigit() }
            if (lead < 0) continue
            val trail = raw.indexOfLast { it.isLetterOrDigit() }
            val word = raw.substring(lead, trail + 1).lowercase()
            val hasTrailingBreak = raw.substring(trail + 1).any { it in SENTENCE_BREAK_CHARS }
            items += TextItem.Word(word, raw.substring(lead, trail + 1), token.range.last + 1, hasTrailingBreak)
            cursor = token.range.last + 1
        }

        val slots = mutableListOf<NumericSlot>()
        var i = 0
        while (i < items.size) {
            val item = items[i]
            if (item is TextItem.Num) {
                item.values.forEach { slots += NumericSlot.Required(it) }
                i++
                continue
            }
            val word = (item as TextItem.Word).lower
            val hour = HOUR_WORDS[word]
            if (hour != null && !item.nextGapHasBreak && i + 1 < items.size) {
                val next1 = items[i + 1]
                // Bare spoken clock time: "four thirty", "four oh five", "four thirty five".
                if (next1 is TextItem.Word) {
                    val w1 = next1.lower
                    if (w1 in ZERO_WORDS && !next1.nextGapHasBreak && i + 2 < items.size) {
                        val next2 = items[i + 2]
                        val ones = (next2 as? TextItem.Word)?.lower?.let { DIGIT_ONES[it] }
                        if (ones != null) {
                            slots += NumericSlot.Optional(listOf(hour.toString(), ones.toString()))
                            i += 3
                            continue
                        }
                    }
                    val tens = TENS_WORDS[w1]
                    if (tens != null) {
                        val next2 = items.getOrNull(i + 2) as? TextItem.Word
                        val ones = if (!next1.nextGapHasBreak && next2 != null) DIGIT_ONES[next2.lower] else null
                        if (ones != null) {
                            slots += NumericSlot.Optional(listOf(hour.toString(), (tens + ones).toString()))
                            i += 3
                            continue
                        }
                        slots += NumericSlot.Optional(listOf(hour.toString(), tens.toString()))
                        i += 2
                        continue
                    }
                    val teenOrTen = TEN_TO_NINETEEN_WORDS[w1]
                    if (teenOrTen != null) {
                        slots += NumericSlot.Optional(listOf(hour.toString(), teenOrTen.toString()))
                        i += 2
                        continue
                    }
                }
            }
            if (word == "one") {
                val prevWord = (items.getOrNull(i - 1) as? TextItem.Word)?.lower
                if (prevWord !in TENS_WORDS && prevWord != "and" && prevWord != "hundred") {
                    slots += NumericSlot.Optional(listOf("1"))
                }
            }
            i++
        }
        return slots
    }

    private val TOKEN_REGEX = Regex("\\S+")
    private val SENTENCE_BREAK_CHARS = setOf(',', '.', ';', ':', '!', '?')
    private val ZERO_WORDS = setOf("oh", "zero")
    private val DIGIT_ONES = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val HOUR_WORDS = DIGIT_ONES + mapOf("ten" to 10, "eleven" to 11, "twelve" to 12)
    private val TEN_TO_NINETEEN_WORDS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS_WORDS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    )
}

/** Shared numeric semantics for normalized input and post-model output. */
internal object NumericValueExtractor {
    internal data class ExtractedMatch(val range: IntRange, val values: List<String>)

    fun extract(text: String): List<String> = extractMatches(text).flatMap { it.values }

    internal fun extractMatches(text: String): List<ExtractedMatch> =
        VALUE.findAll(text).map { match ->
            val raw = match.value
            val values = when {
                // A phone-shaped match is digits only: its separators are formatting, and `.` must
                // not survive the way a decimal point does or `212.555.3476` would never equal
                // `2125553476`.
                isPhone(raw) -> listOf(raw.filter { it.isDigit() })
                ON_THE_HOUR_TIME.matches(raw) -> {
                    val hour = raw.substringBefore(':')
                    listOf(canonicalize(hour))
                }
                else -> listOf(canonicalize(raw.filter { it.isDigit() || it == '.' }))
            }
            ExtractedMatch(match.range, values)
        }.toList()

    /**
     * Canonicalizes non-phone numeric literals so formatting differences like leading zeros
     * (`"08"` -> `"8"`) and trailing `.0+` decimals (`"12.00"` -> `"12"`) compare identically.
     */
    internal fun canonicalize(digits: String): String {
        val trimmed = digits.trimEnd('.')
        val withoutTrailingZeroDecimal = trimmed.replace(TRAILING_ZERO_DECIMAL, "")
        val withoutLeadingZeros = withoutTrailingZeroDecimal.trimStart('0')
        return when {
            withoutLeadingZeros.isEmpty() -> "0"
            withoutLeadingZeros.startsWith(".") -> "0$withoutLeadingZeros"
            else -> withoutLeadingZeros
        }
    }

    /**
     * A single dot is far more likely a decimal point than a phone separator, so `3.1415926`
     * keeps its decimal meaning while `212.555.3476` is treated as formatting.
     */
    private fun isPhone(value: String): Boolean {
        if (!PHONE.matches(value)) return false
        return value.count { it == '.' } != 1
    }

    /**
     * Phone separators models actually emit: space, hyphen, or dot, with optional area parens and
     * an optional country code. Covers the 7-digit local form as well as 10-digit NANP, because a
     * punctuation-only reformat must never read as a value change.
     */
    private const val SEP = "[ .-]?"
    private const val PHONE_BODY =
        "(?:\\+?\\d{1,3}$SEP)?(?:\\(\\d{3}\\)$SEP|\\d{3}$SEP)?\\d{3}$SEP\\d{4}"

    private val PHONE = Regex(PHONE_BODY)
    private val ON_THE_HOUR_TIME = Regex("(?:2[0-3]|1\\d|0?\\d):00")
    private val TRAILING_ZERO_DECIMAL = Regex("\\.0+$")

    // Phone first so `(212) 555-3476` is one value; on-the-hour clock times (`12:00`, `08:00`)
    // next so `12:00` -> `12 PM` keeps a single value `12`; then general numbers/decimals.
    private val VALUE = Regex(
        "(?<![\\d+(])$PHONE_BODY(?!\\d)|" +
            "(?<![\\p{L}\\d:])(?:2[0-3]|1\\d|0?\\d):00(?![\\d:])|" +
            "(?<![\\p{L}\\d])[$€£¥]?\\d+(?:,\\d{3})*(?:\\.\\d+)?(?:st|nd|rd|th)?(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE,
    )
}
