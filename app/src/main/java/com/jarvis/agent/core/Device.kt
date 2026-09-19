package com.jarvis.agent.core

/**
 * Everything the agent can do to the phone. The Accessibility service implements it for
 * real; the tests implement it with a fake screen, which is why the whole agent logic is
 * verifiable without a device.
 */
interface DeviceController {
    fun installedApps(): List<AppEntry>
    fun launchPackage(packageName: String): Boolean
    fun openSystemSettings(): Boolean
    fun screen(): ScreenSnapshot
    fun tap(node: ScreenNode): Boolean
    fun setText(node: ScreenNode, text: String): Boolean
    fun scroll(direction: ScrollDirection): Boolean
    fun back(): Boolean
    fun home(): Boolean
    fun recents(): Boolean
    fun pressEnter(): Boolean
    fun sleep(millis: Long)
}

fun interface VoiceOutput {
    fun say(text: String)
}

/** Asks the user before anything irreversible. Returns true when approved. */
fun interface ConfirmationGate {
    fun confirm(question: String): Boolean
}

/** Approves everything — only for tests and for the "no confirmations" setting. */
object AlwaysApprove : ConfirmationGate {
    override fun confirm(question: String): Boolean = true
}
