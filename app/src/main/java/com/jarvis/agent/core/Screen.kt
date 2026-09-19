package com.jarvis.agent.core

data class Bounds(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** A flattened, immutable view of one on-screen element. */
data class ScreenNode(
    val id: Int,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val bounds: Bounds = Bounds()
) {
    /** Everything a matcher may look at, joined for cheap substring checks. */
    val searchable: String
        get() = listOfNotNull(text, contentDescription, viewId?.substringAfterLast('/'))
            .joinToString(" ")

    fun label(): String = text ?: contentDescription ?: viewId?.substringAfterLast('/') ?: (className ?: "view")

    /**
     * Stable-enough identity across two snapshots of the same screen. Used to remember
     * "this is the field I just typed into" so the next tap does not land back on it.
     */
    val key: String
        get() = viewId ?: "${className ?: "view"}@${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
}

data class ScreenSnapshot(
    val packageName: String? = null,
    val nodes: List<ScreenNode> = emptyList()
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    /** Compact text the LLM can read without blowing up the prompt. */
    fun summarize(limit: Int = 40): String {
        if (nodes.isEmpty()) return "screen: (empty), app: ${packageName ?: "unknown"}"
        val sb = StringBuilder()
        sb.append("app: ").append(packageName ?: "unknown").append('\n')
        var shown = 0
        for (n in nodes) {
            if (shown >= limit) break
            val label = listOfNotNull(
                n.text?.takeIf { it.isNotBlank() }?.let { "text=\"${it.take(60)}\"" },
                n.contentDescription?.takeIf { it.isNotBlank() }?.let { "desc=\"${it.take(60)}\"" },
                n.viewId?.takeIf { it.isNotBlank() }?.let { "id=${it.substringAfterLast('/')}" }
            )
            if (label.isEmpty()) continue
            val flags = buildList {
                if (n.clickable) add("clickable")
                if (n.editable) add("editable")
                if (n.scrollable) add("scrollable")
                if (n.focused) add("focused")
            }
            sb.append("- #").append(n.id).append(' ').append(label.joinToString(" "))
            if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(",")).append(']')
            sb.append('\n')
            shown++
        }
        return sb.toString().trimEnd()
    }
}

/** Picks the on-screen element that best matches a natural-language target. */
object ScreenMatcher {

    /** Words for a field you type into, in both languages. */
    private val EDIT_HINTS = setOf(
        "поле", "ввод", "сообщение", "текст", "поиск", "search", "message", "input",
        "field", "write", "напиши", "введи", "edit"
    )

    fun find(
        snapshot: ScreenSnapshot,
        target: String,
        requireClickable: Boolean = false,
        excludeKeys: Set<String> = emptySet(),
        penalizeEditable: Boolean = false
    ): ScreenNode? = rank(snapshot, target, requireClickable, excludeKeys, penalizeEditable)
        .firstOrNull()?.first

    /**
     * Ranks nodes against [target]. The target may list alternatives separated by `|`
     * ("отправить|send") — the best-scoring alternative wins, which is how one plan works
     * on both a Russian and an English UI.
     */
    fun rank(
        snapshot: ScreenSnapshot,
        target: String,
        requireClickable: Boolean = false,
        excludeKeys: Set<String> = emptySet(),
        penalizeEditable: Boolean = false
    ): List<Pair<ScreenNode, Int>> {
        val alternatives = target.split('|')
            .map { Text.normalize(it) }
            .filter { it.isNotEmpty() }
        if (alternatives.isEmpty()) return emptyList()

        val out = ArrayList<Pair<ScreenNode, Int>>()
        for (node in snapshot.nodes) {
            if (!node.enabled) continue
            if (requireClickable && !node.clickable) continue
            if (node.key in excludeKeys) continue

            var score = 0
            val candidates = listOfNotNull(
                node.text,
                node.contentDescription,
                node.viewId?.substringAfterLast('/')?.replace('_', ' ')
            )
            for (q in alternatives) {
                val qTranslit = Text.translit(q)
                for (c in candidates) {
                    val n = Text.normalize(c)
                    if (n.isEmpty()) continue
                    val t = Text.translit(n)
                    when {
                        n == q || t == qTranslit -> score = maxOf(score, 100)
                        n.startsWith(q) || t.startsWith(qTranslit) -> score = maxOf(score, 85)
                        q.length >= 3 && (n.contains(q) || t.contains(qTranslit)) -> score = maxOf(score, 75)
                        q.length >= 3 && n.length >= 3 && (q.contains(n) || qTranslit.contains(t)) ->
                            score = maxOf(score, 65)
                    }
                    val overlap = Text.tokens(n).toSet().intersect(Text.tokens(q).toSet()).size
                    if (overlap > 0) score = maxOf(score, 45 + overlap * 8)
                    val sim = Text.similarity(qTranslit, t)
                    if (sim >= 0.8) score = maxOf(score, (sim * 72).toInt())
                }
            }

            if (score == 0) continue
            if (node.clickable) score += 8
            if (!node.text.isNullOrBlank()) score += 3
            // A text field that merely contains what we typed is not a tap target.
            if (penalizeEditable && node.editable) score -= 40
            if (score <= 0) continue
            out.add(node to score)
        }

        return out.sortedWith(
            compareByDescending<Pair<ScreenNode, Int>> { it.second }
                .thenBy { it.first.label().length }
                .thenBy { it.first.id }
        )
    }

    /**
     * Finds the field to type into. Prefers the focused editable, then an editable matching
     * [hint], then any editable at all.
     */
    fun findEditable(
        snapshot: ScreenSnapshot,
        hint: String? = null,
        excludeKeys: Set<String> = emptySet()
    ): ScreenNode? {
        val editables = snapshot.nodes.filter { it.editable && it.enabled && it.key !in excludeKeys }
        if (editables.isEmpty()) return null
        editables.firstOrNull { it.focused }?.let { return it }
        if (!hint.isNullOrBlank()) {
            val byHint = rank(ScreenSnapshot(snapshot.packageName, editables), hint).firstOrNull()
            if (byHint != null && byHint.second >= 45) return byHint.first
        }
        val hintWords = editables.firstOrNull { node ->
            Text.tokens(node.searchable).any { it in EDIT_HINTS }
        }
        return hintWords ?: editables.first()
    }

    fun findScrollable(snapshot: ScreenSnapshot): ScreenNode? =
        snapshot.nodes.firstOrNull { it.scrollable && it.enabled }
}
