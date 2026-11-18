package com.rdald.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.rdald.app.service.PlaybackService

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var prefs: SharedPreferences? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val applyRunnable = Runnable { applySettingsNow() }

        /** Debounced so dragging the slider restarts the sentence only once, after release. */
        private fun scheduleApplySettings() {
            handler.removeCallbacks(applyRunnable)
            handler.postDelayed(applyRunnable, 500)
        }

        private fun applySettingsNow() {
            val ctx = context ?: return
            try {
                ctx.startService(
                    Intent(ctx, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_APPLY_SETTINGS)
                )
            } catch (_: Exception) {
            }
        }

        override fun onDestroy() {
            handler.removeCallbacks(applyRunnable)
            super.onDestroy()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Use the SAME prefs file the PlaybackService reads ("settings").
            // Must be set BEFORE the preferences are inflated.
            preferenceManager.sharedPreferencesName = "settings"
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = preferenceManager.sharedPreferences

            // Update summaries to show current values
            updateSeekBarSummary(findPreference<SeekBarPreference>("speech_rate"), "%", 100) {
                scheduleApplySettings()
            }
            updateSeekBarSummary(findPreference<SeekBarPreference>("speech_pitch"), "%", 100) {
                scheduleApplySettings()
            }
            val seconds = getString(R.string.unit_seconds_suffix)
            updateSeekBarSummary(findPreference<SeekBarPreference>("forward_interval"), seconds, 30)
            updateSeekBarSummary(findPreference<SeekBarPreference>("rewind_interval"), seconds, 10)
            updateSeekBarSummary(
                findPreference<SeekBarPreference>("announce_threshold_min"),
                getString(R.string.unit_min_suffix), 5
            )

            findPreference<Preference>("voices")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), VoicesActivity::class.java))
                true
            }

            findPreference<Preference>("reset_defaults")?.setOnPreferenceClickListener {
                resetDefaults()
                true
            }
        }

        private fun resetDefaults() {
            val seconds = getString(R.string.unit_seconds_suffix)
            val minutes = getString(R.string.unit_min_suffix)
            val items = listOf(
                Triple("speech_rate", 100, "%"),
                Triple("speech_pitch", 100, "%"),
                Triple("announce_threshold_min", 5, minutes),
                Triple("forward_interval", 30, seconds),
                Triple("rewind_interval", 10, seconds)
            )
            for ((key, def, suffix) in items) {
                val pref = findPreference<SeekBarPreference>(key) ?: continue
                pref.value = def
                pref.summary = getString(R.string.pref_value_default, def, suffix, def)
            }
            scheduleApplySettings()
        }

        private fun updateSeekBarSummary(
            pref: SeekBarPreference?,
            suffix: String,
            defaultValue: Int,
            onChanged: (() -> Unit)? = null
        ) {
            val key = pref?.key ?: return
            val currentValue = prefs?.getInt(key, defaultValue) ?: defaultValue
            pref?.let {
                // Set initial summary, showing the default value as a marker.
                it.summary = getString(R.string.pref_value_default, currentValue, suffix, defaultValue)
                // Update live (SeekBarPreferences use updatesContinuously=true).
                it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val progress = (newValue as Int)
                    it.summary = getString(R.string.pref_value_default, progress, suffix, defaultValue)
                    onChanged?.invoke()
                    true
                }
            }
        }
    }
}
