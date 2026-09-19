package com.jarvis.agent.android

import com.jarvis.agent.core.ConfirmationGate
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One place where "are you sure?" lives.
 *
 * The agent thread blocks here; the voice recogniser, the on-screen dialog or the
 * notification unblocks it. Every question carries a token, so an answer that arrives after
 * its question timed out is discarded instead of approving the *next* one. Timing out means
 * no — the agent never sends anything on silence.
 */
object ConfirmationBus : ConfirmationGate {

    fun interface Listener {
        fun onPendingChanged(question: String?, token: Long)
    }

    private val lock = ReentrantLock()
    private val answered = lock.newCondition()
    private val listeners = mutableListOf<Listener>()

    private var nextToken = 0L
    private var pendingToken = 0L
    private var answer: Boolean? = null

    @Volatile
    var pendingQuestion: String? = null
        private set

    @Volatile
    var currentToken: Long = 0L
        private set

    var timeoutSeconds: Long = 25
        set(value) {
            field = value.coerceIn(5, 120)
        }

    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
        l.onPendingChanged(pendingQuestion, currentToken)
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    /** Called from the agent thread. Blocks until answered or timed out. */
    override fun confirm(question: String): Boolean {
        var token = 0L
        lock.withLock {
            token = ++nextToken
            pendingToken = token
            answer = null
        }
        pendingQuestion = question
        currentToken = token
        notifyListeners(question, token)

        var approved = false
        lock.withLock {
            var remaining = TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (answer == null && remaining > 0L) {
                remaining = try {
                    answered.awaitNanos(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    0L
                }
            }
            approved = (if (pendingToken == token) answer else null) ?: false
            pendingToken = 0L
            answer = null
        }

        pendingQuestion = null
        currentToken = 0L
        notifyListeners(null, token)
        return approved
    }

    /** Answers the question identified by [token]. A stale token is ignored. */
    fun answer(approved: Boolean, token: Long = currentToken): Boolean = lock.withLock {
        if (pendingToken == 0L || token != pendingToken) return@withLock false
        answer = approved
        answered.signalAll()
        true
    }

    private fun notifyListeners(question: String?, token: Long) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (l in snapshot) l.onPendingChanged(question, token)
    }
}
