package com.rdald.app

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Helpers for working with the multiple TTS *engines* that can be installed. A `TextToSpeech`
 * instance is bound to a single engine, and voices are engine-specific — so to show the voices of
 * the user's preferred engine (not just Google's), the app must bind to that engine explicitly.
 */
object TtsEngines {

    data class EngineInfo(val packageName: String, val label: String)

    fun installed(context: Context): List<EngineInfo> {
        val pm = context.packageManager
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val result = LinkedHashMap<String, EngineInfo>()
        try {
            @Suppress("DEPRECATION")
            val services = pm.queryIntentServices(intent, 0)
            for (si in services) {
                val pkg = si.serviceInfo?.packageName ?: continue
                if (result.containsKey(pkg)) continue
                val label = try {
                    si.serviceInfo.applicationInfo.loadLabel(pm).toString()
                } catch (_: Exception) {
                    pkg
                }
                result[pkg] = EngineInfo(pkg, label)
            }
        } catch (_: Exception) {
        }
        return result.values.toList()
    }

    fun systemDefault(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
    } catch (_: Exception) {
        null
    }

    /** Engine the app should use: saved choice if valid, else the system default, else the first. */
    fun resolve(context: Context): String? {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val chosen = prefs.getString("tts_engine", null)
        val installed = installed(context).map { it.packageName }
        if (!chosen.isNullOrBlank() && (installed.isEmpty() || chosen in installed)) return chosen
        val def = systemDefault(context)
        if (!def.isNullOrBlank() && (installed.isEmpty() || def in installed)) return def
        return installed.firstOrNull() ?: def
    }

    fun labelFor(context: Context, pkg: String?): String {
        if (pkg.isNullOrBlank()) return "System default"
        return installed(context).firstOrNull { it.packageName == pkg }?.label ?: pkg
    }
}
