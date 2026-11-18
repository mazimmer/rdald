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
                // Set initial summary
                it.summary = "${currentValue}$suffix"
                // Update on change
                it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val progress = (newValue as Int)
                    it.summary = "${progress}$suffix"
                    onChanged?.invoke()
                    true
                }
            }
        }
    }
}
