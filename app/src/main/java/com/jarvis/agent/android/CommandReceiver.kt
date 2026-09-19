package com.jarvis.agent.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets you drive the agent from a terminal, which is how the manual test scenarios in the
 * README are run:
 *
 *   adb shell am broadcast -a com.jarvis.agent.COMMAND --es text "открой Telegram"
 *   adb shell am broadcast -a com.jarvis.agent.CONFIRM --ez approve true
 */
class CommandReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMMAND = "com.jarvis.agent.COMMAND"
        const val ACTION_CONFIRM = "com.jarvis.agent.CONFIRM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        JarvisRuntime.init(context.applicationContext)
        when (intent.action) {
            ACTION_COMMAND -> {
                val text = intent.getStringExtra("text").orEmpty()
                if (text.isNotBlank()) JarvisRuntime.submit(text)
            }
            ACTION_CONFIRM -> {
                ConfirmationBus.answer(intent.getBooleanExtra("approve", false))
            }
        }
    }
}
