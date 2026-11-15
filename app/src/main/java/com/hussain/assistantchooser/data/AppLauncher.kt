package com.hussain.assistantchooser.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import com.hussain.assistantchooser.BuildConfig

private const val TAG = "AppLauncher"

private const val CHATGPT_PKG = "com.openai.chatgpt"

/** Intent actions that mean "start listening", in order of preference. */
private val VOICE_ACTIONS = listOf(
    "android.intent.action.VOICE_ASSIST",
    Intent.ACTION_VOICE_COMMAND,
)

/**
 * ChatGPT's voice overlay activity from older app versions. Newer ChatGPT builds renamed,
 * removed or un-exported it, so it is only used when it still exists and is exported.
 */
private val CHATGPT_LEGACY_VOICE_ACTIVITIES = listOf(
    "com.openai.voice.assistant.AssistantActivity",
)

/**
 * Launches the most appropriate activity for a given assistant package.
 * Handles Google, ChatGPT, and generic ACTION_ASSIST / launch-intent fallbacks.
 */
fun launchAssistantForPackage(context: Context, pkg: String) {
    try {
        if (pkg == "com.google.android.googlequicksearchbox") {
            val intents = listOf(
                Intent("android.intent.action.VOICE_ASSIST").setPackage(pkg),
                Intent(Intent.ACTION_VOICE_COMMAND).setPackage(pkg),
                Intent().apply {
                    component = ComponentName(pkg, "com.google.android.voicesearch.VoiceSearchActivity")
                }
            )
            for (i in intents) {
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    return
                } catch (_: ActivityNotFoundException) { }
            }
            Toast.makeText(context, "Google Assistant not available.", Toast.LENGTH_SHORT).show()
            return
        }

        if (pkg == CHATGPT_PKG && launchChatGptVoice(context)) return

        val resolvers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_ASSIST), PackageManager.MATCH_ALL
        )
        val ri = resolvers.firstOrNull { it.activityInfo.packageName == pkg }
        if (ri != null) {
            context.startActivity(Intent(Intent.ACTION_ASSIST).apply {
                component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            if (BuildConfig.DEBUG) {
                Toast.makeText(context, "Opened ${ri.activityInfo.name} via ASSIST", Toast.LENGTH_LONG).show()
            }
            return
        }

        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?.let { context.startActivity(it) }
            ?: Toast.makeText(context, "Cannot launch $pkg", Toast.LENGTH_SHORT).show()

    } catch (e: Exception) {
        Log.e(TAG, "launchAssistantForPackage($pkg)", e)
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?.let { runCatching { context.startActivity(it) } }
    }
}

/**
 * Tries to open ChatGPT straight into voice mode. Returns false when no voice entry point
 * could be started, so the caller falls back to the generic ACTION_ASSIST / launcher path.
 *
 * Instead of trusting one hardcoded class name (which broke when ChatGPT changed it), this
 * asks the package manager what ChatGPT actually exposes on the installed version:
 *  1. an exported activity that handles a voice action (VOICE_ASSIST / VOICE_COMMAND),
 *  2. the legacy voice activity, if it still exists and is exported,
 *  3. any other exported, enabled activity whose class name mentions "voice".
 */
private fun launchChatGptVoice(context: Context): Boolean {
    val pm = context.packageManager

    for (action in VOICE_ACTIONS) {
        val match = pm.queryIntentActivities(Intent(action).setPackage(CHATGPT_PKG), 0)
            .firstOrNull { it.activityInfo.exported }
            ?: continue
        val intent = Intent(action).setClassName(CHATGPT_PKG, match.activityInfo.name)
        if (tryStart(context, intent)) return true
    }

    for (cls in CHATGPT_LEGACY_VOICE_ACTIVITIES) {
        val info = runCatching { pm.getActivityInfo(ComponentName(CHATGPT_PKG, cls), 0) }.getOrNull()
        if (info == null || !info.exported) continue
        val intent = Intent(Intent.ACTION_ASSIST).setClassName(CHATGPT_PKG, cls)
        if (tryStart(context, intent)) return true
    }

    val voiceActivities = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(CHATGPT_PKG, PackageManager.GET_ACTIVITIES).activities
    }.getOrNull().orEmpty()
        .filter { it.exported && it.enabled && it.name.contains("voice", ignoreCase = true) }
    Log.d(TAG, "ChatGPT exported voice-like activities: ${voiceActivities.map { it.name }}")
    for (info in voiceActivities) {
        val intent = Intent(Intent.ACTION_MAIN).setClassName(CHATGPT_PKG, info.name)
        if (tryStart(context, intent)) return true
    }

    Log.w(TAG, "No ChatGPT voice entry point found; falling back to ACTION_ASSIST")
    return false
}

/** Starts [intent] in a new task. Returns false if the target is missing or not allowed. */
private fun tryStart(context: Context, intent: Intent): Boolean = try {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    Log.d(TAG, "Launched ${intent.component?.className} (${intent.action})")
    if (BuildConfig.DEBUG) {
        Toast.makeText(
            context,
            "Opened ${intent.component?.shortClassName} via ${intent.action}",
            Toast.LENGTH_LONG,
        ).show()
    }
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "Not found: ${intent.component}", e); false
} catch (e: SecurityException) {
    Log.w(TAG, "Not exported: ${intent.component}", e); false
}
