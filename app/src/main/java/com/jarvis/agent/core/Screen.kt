package com.jarvis.agent.core

data class Bounds(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()
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
    val password: Boolean = false,
    val bounds: Bounds = Bounds(),
    /** Distinguishes sibling rows that share one viewId (OTP cells, list items). */
    val occurrence: Int = 0
) {
    val shortViewId: String? get() = viewId?.substringAfterLast('/')

    /** Everything a matcher may look at, joined for cheap substring checks. */
    val searchable: String
        get() = listOfNotNull(text, contentDescription, shortViewId).joinToString(" ")

    fun label(): String = text ?: contentDescription ?: shortViewId ?: (className ?: "view")

    /**
     * Identity across two snapshots of the same screen.
     *
     * Deliberately not bounds-based: the keyboard opening moves every field by a few pixels,
     * and a key that changes silently turns off the "don't type here twice" guard.
     */
    val key: String
        get() = (viewId ?: className ?: "view") + "#" + occurrence
}

data class ScreenSnapshot(
    val packageName: String? = null,
    val nodes: List<ScreenNode> = emptyList(),
    /** True when the tree was bigger than the traversal cap, so "absent" may mean "unseen". */
    val truncated: Boolean = false
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    /**
     * Compact text the model can read.
     *
     * Selection is by importance, not by traversal order: a chat has dozens of message
     * bubbles and exactly one composer, and taking the first N in tree order reliably cut
     * the composer and the send button out of the prompt.
     */
    fun summarize(limit: Int = 40): String {
        if (nodes.isEmpty()) return "app: ${packageName ?: "unknown"}\n(экран пуст)"
        val sb = StringBuilder()
        sb.append("app: ").append(packageName ?: "unknown").append('\n')

        val labelled = nodes.filter { describeNode(it) != null }
        val interactive = labelled.filter { it.editable || it.focused || it.scrollable }
        val clickable = labelled.filter { it.clickable && it !in interactive }
        val rest = labelled.filter { it !in interactive && it !in clickable }

        val chosen = LinkedHashSet<ScreenNode>()
        chosen.addAll(interactive.take(limit))
        for (n in clickable) {
            if (chosen.size >= limit) break
            chosen.add(n)
        }
        // Plain text last, and skip a child whose text the parent already reported.
        val seen = chosen.mapNotNull { it.text?.let { t -> Text.normalize(t) } }.toMutableSet()
        for (n in rest) {
            if (chosen.size >= limit) break
            val norm = Text.normalize(n.text ?: n.contentDescription)
            if (norm.isNotEmpty() && !seen.add(norm)) continue
            chosen.add(n)
        }

        val ordered = chosen.sortedBy { it.bounds.top }
        for (n in ordered) {
            val described = describeNode(n) ?: continue
            sb.append("- #").append(n.id).append(' ').append(described)
            val flags = buildList {
                if (n.clickable) add("clickable")
                if (n.editable) add("editable")
                if (n.scrollable) add("scrollable")
                if (n.focused) add("focused")
            }
            if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(",")).append(']')
            sb.append('\n')
        }
        if (labelled.size > chosen.size || truncated) {
            sb.append("(на экране есть и другие элементы — при необходимости прокрутите)\n")
        }
        return sb.toString().trimEnd()
    }

    private fun describeNode(n: ScreenNode): String? {
        val parts = listOfNotNull(
            n.text?.takeIf { it.isNotBlank() }?.let { "text=\"${it.take(60)}\"" },
            n.contentDescription?.takeIf { it.isNotBlank() }?.let { "desc=\"${it.take(60)}\"" },
            n.shortViewId?.takeIf { it.isNotBlank() }?.let { "id=$it" }
        )
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}

/** Picks the on-screen element that best matches a natural-language target. */
object ScreenMatcher {

    /** The score a caller must beat to act on a match. */
    const val MIN_SCORE = 60

    /** Words for a field you type into, in both languages. */
    private val EDIT_HINTS = setOf(
        "сообщение", "текст", "поиск", "search", "message", "write", "compose", "напиши"
    )

    /** A "clear"/"close" control is almost never what a positive target means. */
    private val DISMISS_WORDS = setOf(
        "close", "clear", "cancel", "dismiss", "закрыть", "убрать", "очистить", "отмена", "стереть"
    )

    fun find(
        snapshot: ScreenSnapshot,
        target: String,
        requireClickable: Boolean = false,
        excludeKeys: Set<String> = emptySet(),
        avoidTextEquals: String? = null
    ): ScreenNode? = rank(snapshot, target, requireClickable, excludeKeys, avoidTextEquals)
        .firstOrNull()
        ?.takeIf { it.second >= MIN_SCORE }
        ?.first

    /**
     * Ranks nodes against [target], best first.
     *
     * The target may list alternatives separated by `|` ("отправить|send") so one plan works
     * in any UI language. The whole string is tried first, because on-screen text often
     * contains a pipe of its own ("Курсы валют | Банк").
     *
     * [avoidTextEquals] is the text we just typed: a field now holding it is the field we
     * typed into, not the search result we are looking for.
     */
    fun rank(
        snapshot: ScreenSnapshot,
        target: String,
        requireClickable: Boolean = false,
        excludeKeys: Set<String> = emptySet(),
        avoidTextEquals: String? = null
    ): List<Pair<ScreenNode, Int>> {
        val whole = Text.normalize(target)
        val alternatives = (listOf(whole) + target.split('|').map { Text.normalize(it) })
            .filter { it.isNotEmpty() }
            .distinct()
        if (alternatives.isEmpty()) return emptyList()

        val avoid = Text.normalize(avoidTextEquals).takeIf { it.isNotEmpty() }
        val targetIsDismissive = alternatives.any { alt ->
            Text.tokens(alt).any { it in DISMISS_WORDS }
        }

        val out = ArrayList<Pair<ScreenNode, Int>>()
        for (node in snapshot.nodes) {
            if (!node.enabled) continue
            if (requireClickable && !node.clickable) continue
            if (node.key in excludeKeys) continue

            var score = 0
            for (alt in alternatives) {
                score = maxOf(score, scoreNode(node, alt))
            }
            if (score == 0) continue

            if (node.clickable) score += 4
            if (!node.text.isNullOrBlank()) score += 1

            // A text field that now contains exactly what we just typed is not the answer.
            if (avoid != null && node.editable && Text.normalize(node.text) == avoid) score -= 45
            // Don't let "поиск" land on the X that clears the search.
            if (!targetIsDismissive && Text.tokens(node.searchable).any { it in DISMISS_WORDS }) {
                score -= 25
            }
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
     * Score of one node against one already-normalised alternative.
     *
     * The rungs are spaced wider than the bonuses added by the caller, so a better kind of
     * match always beats a worse one — otherwise a clickable message bubble containing the
     * word "отправить" outranks the actual send button.
     */
    private fun scoreNode(node: ScreenNode, q: String): Int {
        val qT = Text.translit(q)
        var best = 0
        for (candidate in candidates(node)) {
            val n = candidate.second
            if (n.isEmpty()) continue
            val fromViewId = candidate.first
            val t = Text.translit(n)

            var s = when {
                n == q || t == qT -> 100
                Text.stem(n) == Text.stem(q) && q.length >= 3 -> 90
                n.startsWith(q) || t.startsWith(qT) -> 80
                q.length >= 3 && (n.contains(q) || t.contains(qT)) -> 62
                q.length >= 3 && n.length >= 3 && (q.contains(n) || qT.contains(t)) -> 50
                else -> 0
            }

            if (s == 0) {
                // Shared whole words, but only words long enough to mean something.
                val shared = Text.tokens(n).filter { it.length >= 3 }
                    .intersect(Text.tokens(q).filter { it.length >= 3 }.toSet())
                if (shared.isNotEmpty()) s = 35 + shared.size * 6
            }

            if (s == 0 && qT.length >= 6 && t.length >= 6) {
                // Fuzzy, but only for words long enough that one typo is not another name.
                val sim = Text.similarity(qT, t)
                if (sim >= 0.85) s = (sim * 70).toInt()
            }

            if (s == 0) continue

            if (fromViewId) {
                // A resource id is weaker evidence than what the user can actually read.
                s -= 5
            } else if (n.length > 12 && n.length > q.length * 3) {
                // A long free-form label that merely mentions the word is not a button:
                // a message bubble saying "надо отправить до 6" is not the send button.
                s = minOf(s, 50)
            }

            best = maxOf(best, s)
        }
        return best
    }

    /** Pairs of (came-from-viewId, normalised label) worth matching against. */
    private fun candidates(node: ScreenNode): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>(5)
        node.text?.let { out.add(false to Text.normalize(it)) }
        node.contentDescription?.let { out.add(false to Text.normalize(it)) }
        node.shortViewId?.let { out.add(true to Text.normalize(it.replace('_', ' '))) }
        // Icon-only buttons: give the glyph its words, one per candidate so "send" can match
        // exactly instead of being buried in a phrase.
        val glyphs = (Text.glyphWords(node.text) + " " + Text.glyphWords(node.contentDescription))
            .split(' ')
            .filter { it.isNotBlank() }
        for (word in glyphs) out.add(false to Text.normalize(word))
        return out
    }

    /**
     * Finds the field to type into.
     *
     * An explicit hint wins over focus: when a plan says "type into the message box", a
     * still-focused search bar must not steal it.
     */
    fun findEditable(
        snapshot: ScreenSnapshot,
        hint: String? = null,
        excludeKeys: Set<String> = emptySet()
    ): ScreenNode? {
        val editables = snapshot.nodes.filter { it.editable && it.enabled && it.key !in excludeKeys }
        if (editables.isEmpty()) return null

        if (!hint.isNullOrBlank()) {
            val byHint = rank(ScreenSnapshot(snapshot.packageName, editables), hint).firstOrNull()
            if (byHint != null && byHint.second >= 65) return byHint.first
        }
        editables.firstOrNull { it.focused }?.let { return it }

        // Words a human would use for an input, looked for only in what a human can read.
        editables.firstOrNull { node ->
            val visible = listOfNotNull(node.text, node.contentDescription).joinToString(" ")
            Text.tokens(visible).any { it in EDIT_HINTS }
        }?.let { return it }

        // Otherwise the lowest field on screen: composers sit at the bottom, and the last
        // field of a form is the one still to be filled.
        return editables.maxByOrNull { it.bounds.bottom }
    }

    fun findScrollable(snapshot: ScreenSnapshot): ScreenNode? =
        snapshot.nodes.filter { it.scrollable && it.enabled }.maxByOrNull { it.bounds.area }
}
