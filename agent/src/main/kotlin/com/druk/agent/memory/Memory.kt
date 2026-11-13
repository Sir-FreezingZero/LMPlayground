package com.druk.agent.memory

import com.druk.agent.model.MemoryEntry
import com.druk.agent.model.MemoryKind
import com.druk.agent.model.Project
import java.security.MessageDigest
import kotlin.math.ln

object Text {
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "be", "with",
        "it", "this", "that", "as", "at", "by", "from", "we", "i", "you", "not", "but", "if", "then", "so",
    )

    fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length > 1 && it !in STOP }

    /** Word 3-shingles; robust near-duplicate detection for code and prose. */
    fun shingles(s: String, k: Int = 3): Set<String> {
        val t = s.lowercase().split(Regex("\\s+|(?=[^a-z0-9_])|(?<=[^a-z0-9_])")).filter { it.isNotBlank() }
        if (t.size < k) return setOf(t.joinToString(" "))
        return (0..t.size - k).mapTo(HashSet()) { t.subList(it, it + k).joinToString(" ") }
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    fun similarity(a: String, b: String): Double = jaccard(shingles(a), shingles(b))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(s: String) = sha256(s.toByteArray())

    /** Whitespace-insensitive hash, so reformatting does not count as new content. */
    fun normalizedHash(s: String) = sha256(s.replace(Regex("\\s+"), " ").trim().lowercase()).take(16)

    fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 15) + "…[+" + (s.length - max + 15) + " chars]"

    fun oneLine(s: String, max: Int) = clip(s.replace(Regex("\\s+"), " ").trim(), max)
}

/**
 * Project-scoped long-term memory retrieval.
 *
 * BM25 over the entry text, boosted by importance and kind, plus a small
 * recency term. Brute force is fine: a project holds hundreds to low
 * thousands of entries and this runs once per step. Memory only ever comes
 * from the Project passed in, so there is no path for another project's
 * entries to leak into a prompt.
 */
object MemoryRetriever {

    fun search(p: Project, query: String, limit: Int, includeArchived: Boolean = false): List<MemoryEntry> {
        val pool = p.memory.filter { includeArchived || !it.archived }
        if (pool.isEmpty()) return emptyList()
        val q = Text.tokens(query).toSet()
        val docs = pool.map { Text.tokens(it.text) }
        val avgLen = docs.sumOf { it.size }.toDouble() / docs.size
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size
        val maxStep = pool.maxOf { it.step }.coerceAtLeast(1)
        return pool.indices.map { i ->
            val d = docs[i]
            val tf = d.groupingBy { it }.eachCount()
            var bm = 0.0
            for (term in q) {
                val f = tf[term] ?: continue
                val idf = ln(1.0 + (n - (df[term] ?: 0) + 0.5) / ((df[term] ?: 0) + 0.5))
                bm += idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * d.size / avgLen.coerceAtLeast(1.0)))
            }
            val e = pool[i]
            val kindBoost = when (e.kind) {
                MemoryKind.DISCOVERY -> 1.5; MemoryKind.INSTRUCTION -> 1.5; MemoryKind.FAILURE -> 1.0
                MemoryKind.SUMMARY -> 0.8; MemoryKind.FINDING -> 0.8; MemoryKind.NOTE -> 0.2
            }
            val score = bm + kindBoost + 0.3 * e.importance + 0.5 * e.step.toDouble() / maxStep
            e to score
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}

/**
 * Keeps the active memory bounded. When too many un-archived low-kind
 * entries accumulate, the oldest batch is folded into one SUMMARY entry
 * (written by the model when available, extractively otherwise) and the
 * originals are archived — still on disk and reachable via `recall`, just
 * no longer competing for prompt space.
 */
object MemoryConsolidator {
    const val ACTIVE_LIMIT = 60
    const val BATCH = 20

    fun needsConsolidation(p: Project): Boolean = candidates(p).size > ACTIVE_LIMIT

    fun candidates(p: Project): List<MemoryEntry> = p.memory.filter {
        !it.archived && (it.kind == MemoryKind.NOTE || it.kind == MemoryKind.FINDING || it.kind == MemoryKind.FAILURE || it.kind == MemoryKind.SUMMARY) && it.importance <= 3
    }.sortedBy { it.step }

    fun batch(p: Project): List<MemoryEntry> = candidates(p).take(BATCH)

    fun extractiveSummary(entries: List<MemoryEntry>): String {
        val failures = entries.filter { it.kind == MemoryKind.FAILURE }
        val rest = entries.filter { it.kind != MemoryKind.FAILURE }
        return buildString {
            append("Summary of ${entries.size} older entries (steps ${entries.minOf { it.step }}–${entries.maxOf { it.step }}): ")
            append(rest.sortedByDescending { it.importance }.take(6).joinToString("; ") { Text.oneLine(it.text, 90) })
            if (failures.isNotEmpty()) append(". Failed: " + failures.take(4).joinToString("; ") { Text.oneLine(it.text, 80) })
        }.take(900)
    }

    fun apply(p: Project, batch: List<MemoryEntry>, summaryText: String): Project {
        if (batch.isEmpty()) return p
        val ids = batch.map { it.id }.toSet()
        val (np, id) = p.nextId("m")
        val summary = MemoryEntry(id, MemoryKind.SUMMARY, summaryText.take(1200), importance = 3,
            evidence = batch.flatMap { it.evidence }.distinct().take(20), step = p.stepCount)
        return np.copy(memory = np.memory.map { if (it.id in ids) it.copy(archived = true) else it } + summary)
    }
}
