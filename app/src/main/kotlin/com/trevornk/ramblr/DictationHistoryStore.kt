package com.trevornk.ramblr

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One past dictation: the raw transcript, and the cleaned version if cleanup ran and succeeded.
 * [paidFallbackGroup] is only ever set when cleanup was served by a pay-per-token direct
 * provider (see [CleanupStepGroup.isPaidFallback], issue #33) -- null for OMNIROUTE/LOCAL_LLM
 * and for entries with no cleanup at all, so the common case and existing history
 * entries (predating this field) stay indistinguishable from each other.
 */
data class DictationHistoryEntry(
    val timestamp: Long,
    val rawText: String,
    val cleanedText: String?,
    val paidFallbackGroup: CleanupStepGroup? = null,
    val liveText: String? = null,
    val insertedSource: String? = null,
) {
    /** Text that was actually inserted, respecting [insertedSource] with safe fallback. */
    fun displayedText(): String = when (insertedSource) {
        "cleaned" -> cleanedText ?: rawText
        "live" -> liveText ?: rawText
        "raw" -> rawText
        else -> cleanedText ?: rawText
    }
}

/** One distinct version of a dictation for side-by-side comparison in History. */
data class HistoryVersionOption(
    val sourceKey: String,
    val label: String,
    val text: String,
    val wasInserted: Boolean,
)

/**
 * Collects distinct non-blank versions among Post-processed ([DictationHistoryEntry.cleanedText]),
 * Live ([DictationHistoryEntry.liveText]), and Raw ([DictationHistoryEntry.rawText]), deduplicating
 * identical text while accurately tagging whichever version was inserted.
 */
fun distinctHistoryVersions(entry: DictationHistoryEntry): List<HistoryVersionOption> {
    val insertedKey = entry.insertedSource ?: when {
        !entry.cleanedText.isNullOrBlank() -> "cleaned"
        !entry.liveText.isNullOrBlank() && entry.liveText == entry.rawText -> "live"
        else -> "raw"
    }
    val rawCandidates = buildList {
        entry.cleanedText?.takeIf { it.isNotBlank() }?.let { add(Triple("cleaned", "Post-processed", it)) }
        entry.liveText?.takeIf { it.isNotBlank() }?.let { add(Triple("live", "Live", it)) }
        entry.rawText.takeIf { it.isNotBlank() }?.let { add(Triple("raw", "Raw", it)) }
    }
    val deduped = mutableListOf<HistoryVersionOption>()
    for ((key, label, text) in rawCandidates) {
        val existingIdx = deduped.indexOfFirst { it.text.trim() == text.trim() }
        if (existingIdx >= 0) {
            if (key == insertedKey) {
                deduped[existingIdx] = deduped[existingIdx].copy(wasInserted = true)
            }
        } else {
            deduped += HistoryVersionOption(
                sourceKey = key,
                label = label,
                text = text,
                wasInserted = key == insertedKey,
            )
        }
    }
    return deduped
}

/** Whether one history row's "paid fallback" badge (#33) should render: the debug/visibility
 *  toggle must be on, and the entry must actually have been served by a paid group. */
fun shouldShowPaidFallbackBadge(debugVisibilityEnabled: Boolean, entry: DictationHistoryEntry): Boolean =
    debugVisibilityEnabled && entry.paidFallbackGroup != null

/**
 * Local-only, file-backed log of past dictations (see #25) so a transcript survives even if
 * injection fails and the user misses the clipboard-fallback toast. Stored as JSON-lines in
 * app-private storage; capped at [maxEntries] with the oldest entries evicted on write. Plain
 * [File] I/O, no Android framework dependency, so this is unit-testable against a temp file.
 *
 * Excluded from backup even though the manifest sets `allowBackup="true"`: backup_rules.xml /
 * data_extraction_rules.xml use include-only semantics (only ramblr.xml + overlay_icon.png), so
 * dictation_history.jsonl is never backed up (security audit L-6).
 */
class DictationHistoryStore(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val appContext: Context? = null,
) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
        private const val PREFS_NAME = "ramblr"
        private const val KEY_HISTORY_ENABLED = "dictation_history_enabled"
        const val KEY_UNREAD_COUNT = "history_unread_count"
        private const val FILE_NAME = "dictation_history.jsonl"

        // One store instance per backing file (#71)
        private val instances = mutableMapOf<String, DictationHistoryStore>()

        /** The shared store for [file], creating it on first use. */
        fun forFile(file: File, maxEntries: Int = DEFAULT_MAX_ENTRIES): DictationHistoryStore =
            synchronized(instances) {
                instances.getOrPut(file.absolutePath) { DictationHistoryStore(file, maxEntries) }
            }

        fun forContext(context: Context, maxEntries: Int = DEFAULT_MAX_ENTRIES): DictationHistoryStore {
            val appCtx = context.applicationContext ?: context
            val targetFile = File(appCtx.filesDir, FILE_NAME)
            return synchronized(instances) {
                val existing = instances[targetFile.absolutePath]
                if (existing != null && existing.appContext != null) {
                    existing
                } else {
                    DictationHistoryStore(targetFile, maxEntries, appCtx).also {
                        instances[targetFile.absolutePath] = it
                    }
                }
            }
        }

        fun unreadCount(context: Context): Int {
            val appCtx = context.applicationContext ?: context
            val p = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!p.getBoolean(KEY_HISTORY_ENABLED, true)) return 0
            return p.getInt(KEY_UNREAD_COUNT, 0).coerceAtLeast(0)
        }

        fun incrementUnreadCount(context: Context) {
            val appCtx = context.applicationContext ?: context
            val p = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!p.getBoolean(KEY_HISTORY_ENABLED, true)) return
            p.edit().putInt(KEY_UNREAD_COUNT, p.getInt(KEY_UNREAD_COUNT, 0) + 1).apply()
        }

        fun clearUnreadCount(context: Context) {
            val appCtx = context.applicationContext ?: context
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_UNREAD_COUNT, 0)
                .apply()
        }
    }

    /** Appends [entry], evicting the oldest entries beyond [maxEntries]. */
    @Synchronized
    fun add(entry: DictationHistoryEntry) {
        val entries = (readAll() + entry).takeLast(maxEntries)
        writeAll(entries)
        appContext?.let { runCatching { incrementUnreadCount(it) } }
    }

    /** Inserts [entry], or replaces the existing entry with the same [DictationHistoryEntry.timestamp]
     *  in place if one exists (#73). */
    @Synchronized
    fun upsert(entry: DictationHistoryEntry) {
        val existing = readAll()
        val isNew = existing.none { it.timestamp == entry.timestamp }
        val entries = if (!isNew) {
            existing.map { if (it.timestamp == entry.timestamp) entry else it }
        } else {
            (existing + entry).takeLast(maxEntries)
        }
        writeAll(entries)
        if (isNew) {
            appContext?.let { runCatching { incrementUnreadCount(it) } }
        }
    }

    /** All entries, newest first. */
    @Synchronized
    fun all(): List<DictationHistoryEntry> = readAll().asReversed()

    @Synchronized
    fun clear() {
        if (file.exists()) file.writeText("")
        appContext?.let { runCatching { clearUnreadCount(it) } }
    }

    /** Removes every entry with the given [timestamp], which is unique per dictation and doubles
     *  as the entry's identity since there's no separate id field. */
    @Synchronized
    fun delete(timestamp: Long) {
        writeAll(readAll().filterNot { it.timestamp == timestamp })
    }

    private fun readAll(): List<DictationHistoryEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            // A corrupt/partial line (e.g. from a killed process mid-write) is skipped rather
            // than losing every other entry in the file.
            .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
    }

    private fun writeAll(entries: List<DictationHistoryEntry>) {
        file.parentFile?.mkdirs()
        val body = entries.joinToString("") { serialize(it) + "\n" }
        // Write to a temp sibling then atomically rename over the target (bugs audit L8): a plain
        // truncate-then-write loses the whole history if the process dies mid-write, whereas a
        // rename either fully replaces the file or leaves the previous version intact.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            // renameTo can fail across some filesystems even on the same dir; fall back to a
            // direct write so a rename quirk never silently drops the update.
            file.writeText(body)
            tmp.delete()
        }
    }

    private fun serialize(entry: DictationHistoryEntry): String =
        JSONObject().apply {
            put("timestamp", entry.timestamp)
            put("rawText", entry.rawText)
            put("cleanedText", entry.cleanedText ?: JSONObject.NULL)
            put("paidFallbackGroup", entry.paidFallbackGroup?.name ?: JSONObject.NULL)
            put("liveText", entry.liveText ?: JSONObject.NULL)
            put("insertedSource", entry.insertedSource ?: JSONObject.NULL)
        }.toString()

    private fun parse(line: String): DictationHistoryEntry {
        val json = JSONObject(line)
        return DictationHistoryEntry(
            timestamp = json.getLong("timestamp"),
            rawText = json.getString("rawText"),
            cleanedText = if (json.isNull("cleanedText")) null else json.getString("cleanedText"),
            // json.isNull returns true both for an explicit JSON null and for a missing key
            // (Android's org.json), so a pre-#33 history line with no "paidFallbackGroup" key at
            // all parses to null here rather than throwing.
            paidFallbackGroup = if (json.isNull("paidFallbackGroup")) null
                else runCatching { CleanupStepGroup.valueOf(json.getString("paidFallbackGroup")) }.getOrNull(),
            liveText = if (json.isNull("liveText")) null else json.optString("liveText").takeIf { it.isNotEmpty() },
            insertedSource = if (json.isNull("insertedSource")) null else json.optString("insertedSource").takeIf { it.isNotEmpty() },
        )
    }
}
