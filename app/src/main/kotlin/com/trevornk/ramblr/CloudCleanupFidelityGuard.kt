package com.trevornk.ramblr

/** Verdict of [CloudCleanupFidelityGuard.check]. */
sealed class FidelityVerdict {
    object Ok : FidelityVerdict()
    /** [reason] is a fixed enum, never transcript or model text, so it is safe to log and show. */
    data class Rejected(val reason: FidelityViolation) : FidelityVerdict()
}

/**
 * Why a cloud cleanup result was rejected. The names double as the tokens
 * [CleanupFailureNotice.summarize] matches on, so the three that already existed for the local
 * validator ([LENGTH_COLLAPSE], [LENGTH_EXPANSION], [NUMERIC_DIVERGENCE]) keep their wording.
 */
enum class FidelityViolation {
    META_REPLY,
    LENGTH_COLLAPSE,
    LENGTH_EXPANSION,
    NUMERIC_DIVERGENCE,
    QUESTION_ANSWERED,
    ENTITY_LOST,
    WORDS_INSERTED,
}

/**
 * Deterministic fidelity check for cloud cleanup output.
 *
 * Until now only the LOCAL_LLM step was validated ([LocalCleanupOutputValidator]); whatever a cloud
 * model returned went straight into the user's text field. The measured failure modes of a
 * wording-preserving cleanup prompt on a cloud model were a renamed title or product, a dropped word
 * of a multi-word name, an invented phrase, a translated message, a changed number, and a chatty
 * reply asking for the transcript instead of cleaning it.
 *
 * The checks are deliberately cheap and conservative: each one has to be unambiguous for the
 * wording-preserving prompts ([PostProcessor.preservesWording]) and must let legitimate cleanup
 * through -- filler removal, rejoined fragments, spelled-out numbers rendered as digits,
 * capitalization changes, and a self-correction resolving to its final version. The cost of a false
 * positive is that the raw transcript is delivered (with the usual cleanup-failed notice), which is
 * exactly what the user gets with cleanup off; the cost of a false negative is today's behavior.
 *
 * A single swapped word ("cat" -> "hat") or one inserted word is NOT caught: telling that apart from
 * a legitimate homophone fix needs semantics, not rules.
 *
 * Pure Kotlin, no Android dependency. Never logs or returns transcript text.
 */
object CloudCleanupFidelityGuard {

    // Length ratio is only meaningful once there is enough text for fillers/rejoins not to
    // dominate it.
    private const val MIN_WORDS_FOR_LENGTH_CHECK = 6
    private const val MIN_LENGTH_RATIO = 0.5
    private const val MAX_LENGTH_RATIO = 1.6

    private const val MIN_INSERTED_WORDS = 2
    private const val MAX_INSERTION_RATE = 0.25

    // A transcript that asks for a shell command legitimately gets a command back (Formal persona),
    // so wording-based checks do not apply to it.
    private val COMMAND_REQUEST = Regex(
        "\\b(command mode|shell command|terminal command|command line|bash command)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Cues that the speaker corrected themselves. Dropping the superseded name or words is then the
    // point of cleanup, so neither the lost-name nor the collapse check applies. Deliberately
    // phrases, not single words: "actually" or "I mean" alone are everyday filler.
    internal val SELF_CORRECTION_CUE = Regex(
        "\\b(?:no,?\\s+sorry|no,?\\s+wait|no,?\\s+actually|actually,?\\s+no|wait,?\\s+no|wait,?\\s+actually|wait,?\\s+make|" +
            "nah\\s+(?:make|actually|let[\u2019']?s|change)|forget\\s+that|change\\s+that\\s+to|" +
            "sorry,?\\s+i\\s+(?:meant|mean)|i\\s+meant|scratch\\s+that|never\\s?mind|" +
            "actually,?\\s+(?:make|let[\u2019']?s\\s+make|change)|make\\s+that|or\\s+rather|correction)\\b|\\b(?:nah|wait),",
        RegexOption.IGNORE_CASE,
    )

    // Explicit spoken emoji command spans (e.g. "three laughing emojis", "fire emoji", "thumbs up emoji").
    internal val SPOKEN_EMOJI_COMMAND = Regex(
        "\\b(?:(?:a|an|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+)?(?!(?:am|pm|a\\.m\\.|p\\.m\\.)\\b)(?:[\\p{L}]+\\s+){1,2}emojis?\\b",
        RegexOption.IGNORE_CASE,
    )

    private val INTERROGATIVE_START = Regex(
        "^(?:how|what|why|where|when|who|is|are|do|does|did|can|could|would|will|should)" +
            "(?:['](?:s|re|ve|ll|d|t|m)|n[']t)?\\b",
        RegexOption.IGNORE_CASE,
    )

    private val CONVERSATIONAL_REPLY_OPENERS = listOf(
        "yes", "no", "yeah", "well", "honestly", "things are", "it's going", "work is", "work's",
        "doing great", "pretty good", "the capital", "i'm doing", "yo", "hey", "man", "bro",
    )

    // Replies a model gives instead of cleaning up. Only a rejection when the transcript does not
    // contain the phrase itself ("Here is the list of options" can be dictated content).
    private val META_PREFIXES = listOf(
        "i'm sorry", "i am sorry", "i apologize", "i can't", "i cannot", "i'm unable", "i am unable",
        "as an ai", "here is", "here's", "sure", "certainly", "of course", "please provide",
        "please paste", "could you provide", "could you please provide",
    )

    // Words that carry no content for the insertion check: grammatical glue, pronouns, fillers, and
    // contraction fragments. Mirrors the list the research harness tuned this rule against.
    private val FILLERS = setOf(
        "um", "uh", "er", "ah", "hmm", "like", "so", "okay", "ok", "well", "yeah", "basically",
        "actually", "literally", "just", "kind", "sort", "of", "you", "know", "i", "mean", "right",
        "anyway", "anyways",
    )
    private val STOP: Set<String> = FILLERS + setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "that", "this", "these", "those", "to",
        "in", "on", "at", "for", "with", "from", "by", "as", "is", "are", "was", "were", "be", "been",
        "being", "it", "its", "do", "does", "did", "have", "has", "had", "not", "no", "yes", "he",
        "she", "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "our", "their",
        "what", "which", "who", "whom", "there", "here", "than", "too", "very", "can", "could",
        "will", "would", "should", "shall", "may", "might", "up", "out", "about", "into", "over",
        "after", "before", "also", "because", "cuz", "cause", "gonna", "wanna", "gotta", "im", "ive",
        "dont", "didnt", "doesnt", "isnt", "arent", "wasnt", "thats", "theres", "s", "t", "re", "ll",
        "ve", "d", "m", "am", "any", "some", "all", "one", "really", "still", "now", "get", "got",
    )

    private val ORDINAL = Regex("\\d+(st|nd|rd|th)")
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['.][\\p{L}\\p{N}]+)*")

    // Like WORD, but a hyphen joins ("Wi-Fi"), because a model may rejoin it ("WiFi").
    private val ENTITY_WORD = Regex("[\\p{L}\\p{N}]+(?:['.\\-][\\p{L}\\p{N}]+)*")

    /**
     * Checks [output] (a cloud cleanup result) against the [raw] transcript it was produced from.
     * Rules run cheapest-first and the first violation wins.
     */
    fun check(raw: String, output: String, spokenEmojiCommands: Boolean = false): FidelityVerdict {
        val rawText = raw.trim()
        val outText = output.trim()
        if (rawText.isEmpty() || outText.isEmpty()) return FidelityVerdict.Ok
        if (COMMAND_REQUEST.containsMatchIn(rawText)) return FidelityVerdict.Ok

        if (isMetaReply(rawText, outText)) return FidelityVerdict.Rejected(FidelityViolation.META_REPLY)

        val emojiExempt = (spokenEmojiCommands || hasUnicodeEmoji(outText)) &&
            SPOKEN_EMOJI_COMMAND.containsMatchIn(rawText) &&
            hasUnicodeEmoji(outText)

        val rawWords = rawText.split(Regex("\\s+")).size
        val outWords = outText.split(Regex("\\s+")).size
        val effectiveRawWordsForCollapse = if (emojiExempt) {
            val savedWords = SPOKEN_EMOJI_COMMAND.findAll(rawText).sumOf { m ->
                (m.value.trim().split(Regex("\\s+")).size - 1).coerceAtLeast(0)
            }
            (rawWords - savedWords).coerceAtLeast(1)
        } else {
            rawWords
        }
        if (effectiveRawWordsForCollapse >= MIN_WORDS_FOR_LENGTH_CHECK) {
            val ratio = outWords.toDouble() / effectiveRawWordsForCollapse
            if (ratio < MIN_LENGTH_RATIO && !SELF_CORRECTION_CUE.containsMatchIn(rawText)) {
                return FidelityVerdict.Rejected(FidelityViolation.LENGTH_COLLAPSE)
            }
        }
        if (rawWords >= MIN_WORDS_FOR_LENGTH_CHECK) {
            val ratio = outWords.toDouble() / rawWords
            if (ratio > MAX_LENGTH_RATIO) return FidelityVerdict.Rejected(FidelityViolation.LENGTH_EXPANSION)
        }

        val rawForNumbers = if (emojiExempt) SPOKEN_EMOJI_COMMAND.replace(rawText, " ") else rawText
        if (numbersChanged(rawForNumbers, outText)) return FidelityVerdict.Rejected(FidelityViolation.NUMERIC_DIVERGENCE)
        if (entityLost(rawText, outText)) return FidelityVerdict.Rejected(FidelityViolation.ENTITY_LOST)
        if (tooManyInsertedWords(rawText, outText)) return FidelityVerdict.Rejected(FidelityViolation.WORDS_INSERTED)
        return FidelityVerdict.Ok
    }

    /**
     * Always-on anti-hallucination and question-answering safety check that runs on EVERY cloud
     * cleanup output, including rewriting/custom/per-app styles where [PostProcessor.preservesWording]
     * is false.
     */
    fun checkSafety(
        raw: String,
        output: String,
        vocabularyTerms: List<String> = emptyList(),
    ): FidelityVerdict {
        val rawText = raw.trim()
        val outText = output.trim()
        if (rawText.isEmpty() || outText.isEmpty()) return FidelityVerdict.Ok
        if (COMMAND_REQUEST.containsMatchIn(rawText)) return FidelityVerdict.Ok

        if (isMetaReply(rawText, outText)) return FidelityVerdict.Rejected(FidelityViolation.META_REPLY)

        val rawWords = rawText.split(Regex("\\s+")).size
        val outWords = outText.split(Regex("\\s+")).size

        if (isQuestionAnswered(rawText, outText, rawWords, outWords)) {
            return FidelityVerdict.Rejected(FidelityViolation.QUESTION_ANSWERED)
        }

        if (rawWords <= 8 && outWords > 18 && outWords.toDouble() / rawWords > 3.5) {
            return FidelityVerdict.Rejected(FidelityViolation.LENGTH_EXPANSION)
        }
        if (rawWords > 8 && outWords.toDouble() / rawWords > 3.0 && outWords - rawWords >= 20) {
            return FidelityVerdict.Rejected(FidelityViolation.LENGTH_EXPANSION)
        }

        if (hasInjectedVocabularyTerm(rawText, outText, rawWords, outWords, vocabularyTerms)) {
            return FidelityVerdict.Rejected(FidelityViolation.WORDS_INSERTED)
        }

        return FidelityVerdict.Ok
    }

    private fun isQuestionAnswered(rawText: String, outText: String, rawWords: Int, outWords: Int): Boolean {
        if (rawWords > 12) return false
        val isQuestion = rawText.endsWith("?") || INTERROGATIVE_START.containsMatchIn(leadingText(rawText))
        if (!isQuestion) return false
        if (outText.contains('?')) return false
        if (outWords >= maxOf(8, rawWords * 2)) return true
        val rawLower = normalizeApostrophes(rawText).lowercase()
        val outHead = leadingText(outText)
        return CONVERSATIONAL_REPLY_OPENERS.any { opener ->
            outHead.startsWith(opener) &&
                !outHead.getOrElse(opener.length) { ' ' }.isLetterOrDigit() &&
                !rawLower.contains(opener)
        }
    }

    private fun hasInjectedVocabularyTerm(
        rawText: String,
        outText: String,
        rawWords: Int,
        outWords: Int,
        vocabularyTerms: List<String>,
    ): Boolean {
        if (outWords < rawWords + 4) return false
        val combinedTerms = (vocabularyTerms + VocabularyTerms.DEFAULTS)
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinctBy { it.lowercase() }
        if (combinedTerms.isEmpty()) return false
        val rawLower = normalizeApostrophes(rawText).lowercase()
        val outLower = normalizeApostrophes(outText).lowercase()
        val rawTokens = tokens(rawText)
        for (term in combinedTerms) {
            val termLower = normalizeApostrophes(term).lowercase()
            if (rawLower.contains(termLower)) continue
            val wholeWord = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(termLower)}(?![\\p{L}\\p{N}])")
            if (!wholeWord.containsMatchIn(outLower)) continue
            val compactPrefix = termLower.filter { it.isLetterOrDigit() }.take(3)
            val subPrefixes = termLower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.map { it.take(3) }
            val prefixesToCheck = (listOf(compactPrefix) + subPrefixes).filter { it.length >= 3 }.distinct()
            val sharesPrefix = rawTokens.any { tok ->
                tok.length >= 3 && prefixesToCheck.any { prefix -> tok.startsWith(prefix) }
            }
            if (!sharesPrefix) return true
        }
        return false
    }

    private fun hasUnicodeEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F) return true
        }
        return false
    }

    private fun normalizeApostrophes(s: String) = s.replace('\u2019', '\'').replace('\u2018', '\'')

    private fun leadingText(s: String): String =
        normalizeApostrophes(s).trimStart { !it.isLetterOrDigit() }.lowercase()

    private fun isMetaReply(raw: String, out: String): Boolean {
        val rawLower = normalizeApostrophes(raw).lowercase()
        val outHead = leadingText(out)
        return META_PREFIXES.any { phrase ->
            outHead.startsWith(phrase) &&
                !outHead.getOrElse(phrase.length) { ' ' }.isLetterOrDigit() &&
                !rawLower.contains(phrase)
        }
    }

    // --- numbers ---------------------------------------------------------------------------------

    /**
     * A number is *changed* when a numeric value the speaker said is missing from the output while
     * the output carries a value they did not say (5 -> 6), or when every number vanished. Values
     * only in the output are fine on their own (spelled-out numbers rendered as digits), and values
     * only in the transcript are fine as long as something numeric remains (a self-correction
     * "5 pm, no, 6 pm" resolves to 6). Spelled-out numbers in the output are normalized first, so
     * "5" -> "five" is formatting, not a change.
     */
    private fun numbersChanged(raw: String, out: String): Boolean {
        val rawValues = NumericValueExtractor.extract(raw)
        if (rawValues.isEmpty()) return false
        val outValues = NumericValueExtractor.extract(out) + SpokenNumberNormalizer.normalize(out).semanticValues
        val outCounts = outValues.groupingBy { it }.eachCount().toMutableMap()
        val missing = mutableListOf<String>()
        for (v in rawValues) {
            val c = outCounts[v] ?: 0
            if (c > 0) outCounts[v] = c - 1 else missing += v
        }
        if (missing.isEmpty()) return false
        if (outValues.isEmpty()) return true
        // Anything the output has that the transcript did not say (counting the transcript's own
        // spelled-out numbers as said).
        val said = (rawValues + SpokenNumberNormalizer.normalize(raw).semanticValues).toSet()
        return outValues.any { it !in said }
    }

    // --- capitalized terms -----------------------------------------------------------------------

    private fun comparable(word: String): String =
        normalizeApostrophes(word).lowercase().replace("'", "").replace("-", "").trimEnd('.')

    /**
     * Capitalized words in the middle of a sentence (names, titles, products) are the terms the
     * speaker most needs preserved. One is *lost* when it appears nowhere in the output
     * (case-insensitively, tolerating compounds such as "Wi-Fi" -> "WiFi"), unless the speaker
     * visibly corrected themselves, in which case the superseded name is meant to go.
     */
    private fun entityLost(raw: String, out: String): Boolean {
        val entities = capitalizedMidSentenceWords(raw)
        if (entities.isEmpty()) return false
        val outWords = ENTITY_WORD.findAll(out).map { comparable(it.value) }.toSet()
        val outCompact = comparable(out).filter { it.isLetterOrDigit() }
        val lost = entities.any { e ->
            e !in outWords && !(e.length >= 3 && outCompact.contains(e.filter { it.isLetterOrDigit() }))
        }
        return lost && !SELF_CORRECTION_CUE.containsMatchIn(raw)
    }

    private fun capitalizedMidSentenceWords(text: String): Set<String> {
        val found = linkedSetOf<String>()
        for (sentence in text.split(Regex("(?<=[.!?])\\s+|\\n+"))) {
            val words = ENTITY_WORD.findAll(sentence).map { it.value }.toList()
            for (w in words.drop(1)) {
                val c = w.first()
                if (!c.isUpperCase()) continue
                val lower = comparable(w)
                if (lower in setOf("i", "im", "ill", "ive", "id") || lower.length < 2) continue
                found += lower
            }
        }
        return found
    }

    // --- inserted words --------------------------------------------------------------------------

    private fun tokens(text: String): List<String> =
        WORD.findAll(normalizeApostrophes(text).lowercase())
            .flatMap { m -> m.value.replace("'", "").split('.').asSequence() }
            .filter { it.isNotEmpty() }
            .toList()

    // Numbers (digits or spelled out, either direction) have their own rule; glue and fillers carry
    // no content.
    private fun isContent(w: String) =
        w.length > 1 && w !in STOP && w !in SpokenNumberNormalizer.NUMBER_OR_ORDINAL_WORDS &&
            !w.all(Char::isDigit) && !ORDINAL.matches(w)

    /**
     * Content words in the output that the speaker never said. Fails when either
     *  - at least [MIN_INSERTED_WORDS] were added AND they are more than [MAX_INSERTION_RATE] of the
     *    transcript's content words (scattered additions), or
     *  - [MIN_INSERTED_WORDS] or more were added back to back ("went down"): a phrase the speaker
     *    never said, however long the message is.
     * A stray homophone fix, "gonna" -> "going to", or one extra word never trips it. Words that
     * merely re-inflect a spoken word ("anyways" -> "anyway") share a four-letter stem with it and
     * are not counted.
     */
    private fun tooManyInsertedWords(raw: String, out: String): Boolean {
        val rawContent = tokens(raw).filter(::isContent)
        val rawCounts = rawContent.groupingBy { it }.eachCount().toMutableMap()
        var inserted = 0
        var run = 0
        var longestRun = 0
        for (w in tokens(out)) {
            if (!isContent(w)) { run = 0; continue }
            val c = rawCounts[w] ?: 0
            if (c > 0) {
                rawCounts[w] = c - 1
                run = 0
                continue
            }
            val sharesStem = w.length >= 4 && rawContent.any { r -> r.length >= 4 && (r.startsWith(w.take(4)) || w.startsWith(r.take(4))) }
            if (sharesStem) { run = 0; continue }
            inserted++
            run++
            if (run > longestRun) longestRun = run
        }
        if (longestRun >= MIN_INSERTED_WORDS) return true
        val total = maxOf(1, rawContent.size)
        return inserted >= MIN_INSERTED_WORDS && inserted.toDouble() / total > MAX_INSERTION_RATE
    }
}
