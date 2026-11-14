package com.rdald.app

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference

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

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Use the SAME prefs file the PlaybackService reads ("settings").
            // Must be set BEFORE the preferences are inflated.
            preferenceManager.sharedPreferencesName = "settings"
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = preferenceManager.sharedPreferences

            // Update summaries to show current values
            updateSeekBarSummary(findPreference<SeekBarPreference>("speech_rate"), "%", 100)
            updateSeekBarSummary(findPreference<SeekBarPreference>("speech_pitch"), "%", 100)
            updateSeekBarSummary(findPreference<SeekBarPreference>("forward_interval"), "s", 30)
            updateSeekBarSummary(findPreference<SeekBarPreference>("rewind_interval"), "s", 10)
        }

        private fun updateSeekBarSummary(pref: SeekBarPreference?, suffix: String, defaultValue: Int) {
            val key = pref?.key ?: return
            val currentValue = prefs?.getInt(key, defaultValue) ?: defaultValue
            
            pref?.let {
                // Set initial summary
                it.summary = "${currentValue}$suffix"
                // Update on change
                it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val progress = (newValue as Int)
                    it.summary = "${progress}$suffix"
                    true
                }
            }
        }
    }
}
