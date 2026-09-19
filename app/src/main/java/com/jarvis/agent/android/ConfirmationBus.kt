package com.jarvis.agent.android

import com.jarvis.agent.core.ConfirmationGate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One place where "are you sure?" lives.
 *
 * The agent thread blocks here; the voice recogniser ("да"/"нет") or the on-screen dialog
 * unblocks it. Timing out means "no" — the agent never sends anything on silence.
 */
object ConfirmationBus : ConfirmationGate {

    fun interface Listener {
        fun onPendingChanged(question: String?)
    }

    private val answers = ArrayBlockingQueue<Boolean>(1)
    private val listeners = mutableListOf<Listener>()

    @Volatile
    var pendingQuestion: String? = null
        private set

    var timeoutSeconds: Long = 25

    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
        l.onPendingChanged(pendingQuestion)
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    /** Called from the agent thread. Blocks until answered or timed out. */
    override fun confirm(question: String): Boolean {
        answers.clear()
        pendingQuestion = question
        notifyListeners()
        return try {
            answers.poll(timeoutSeconds, TimeUnit.SECONDS) ?: false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            pendingQuestion = null
            notifyListeners()
        }
    }

    /** Called from the voice thread or the UI. No-op when nothing is pending. */
    fun answer(approved: Boolean): Boolean {
        if (pendingQuestion == null) return false
        answers.offer(approved)
        return true
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (l in snapshot) l.onPendingChanged(pendingQuestion)
    }
}
