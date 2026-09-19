package com.jarvis.agent.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets you drive the agent from a terminal, which is how the manual test scenarios in the
 * README are run:
 *
 *   adb shell am broadcast -a com.jarvis.agent.COMMAND --es text "открой Telegram"
 *
 * The receiver is declared with `android:permission="android.permission.DUMP"`, which the
 * adb shell holds and an installed app cannot obtain. Without that, any app on the phone
 * could make Jarvis tap anything.
 *
 * There is deliberately no broadcast that answers a confirmation: a remotely settable "yes"
 * to a safety prompt has no legitimate use.
 */
class CommandReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMMAND = "com.jarvis.agent.COMMAND"
    }

    override fun onReceive(context: Context, intent: Intent) {
        JarvisRuntime.init(context.applicationContext)
        if (intent.action == ACTION_COMMAND) {
            val text = intent.getStringExtra("text").orEmpty()
            if (text.isNotBlank()) JarvisRuntime.submit(text)
        }
    }
}
