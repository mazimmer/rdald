package com.rdald.app

import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rdald.app.databinding.ActivityVoicesBinding
import com.rdald.app.databinding.ItemVoiceBinding
import com.rdald.app.databinding.ItemVoiceHeaderBinding
import java.util.Locale

/**
 * Unified voice picker across ALL installed TTS engines. Voices are grouped by language ->
 * country/variant -> voice (folded by default) with tri-state checkboxes. Selection is stored as
 * a set of *deselected* keys "enginePkg|voiceName"; missing engines' keys are pruned on open, so
 * new engines' voices default to selected.
 */
class VoicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoicesBinding
    private val adapter = VoiceAdapter()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val ttsByEngine = HashMap<String, TextToSpeech>()
    private val engineLabels = HashMap<String, String>()
    private val engineVoices = HashMap<String, List<EngVoice>>()
    private var pendingEngines = 0
    private var allVoices: List<EngVoice> = emptyList()

    private val expandedLangs = HashSet<String>()
    private val expandedSubs = HashSet<String>()

    private data class EngVoice(val enginePkg: String, val engineLabel: String, val voice: Voice) {
        val key: String get() = "$enginePkg|${voice.name}"
    }

    private class Row(
        val type: Int,
        val text: String = "",
        val detail: String = "",
        val key: String = "",
        val engVoice: EngVoice? = null,
        var state: Int = STATE_UNCHECKED,
        var expanded: Boolean = false
    )

    companion object {
        private const val TYPE_MASTER = 0
        private const val TYPE_LANG = 1
        private const val TYPE_SUB = 2
        private const val TYPE_VOICE = 3
        private const val STATE_UNCHECKED = 0
        private const val STATE_CHECKED = 1
        private const val STATE_INDETERMINATE = 2
        private const val SAMPLE_ID = "voice_sample"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoicesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        loadEngines()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.voices_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_all -> {
                prefs.edit().putStringSet("deselected_voices", emptySet()).apply()
                rebuildRows()
                return true
            }
            R.id.action_select_none -> {
                prefs.edit().putStringSet("deselected_voices", allVoices.map { it.key }.toSet()).apply()
                rebuildRows()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadEngines() {
        val installed = TtsEngines.installed(this)
        if (installed.isEmpty()) {
            binding.progress.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(R.string.no_tts_engines)
            return
        }
        val installedPkgs = installed.map { it.packageName }.toSet()
        pruneDeselected(installedPkgs)
        pendingEngines = installed.size
        for (ei in installed) {
            val pkg = ei.packageName
            engineLabels[pkg] = ei.label
            val inst = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
                runOnUiThread { onEngineReady(pkg, status) }
            }, pkg)
            ttsByEngine[pkg] = inst
        }
    }

    private fun onEngineReady(pkg: String, status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val vs = try {
                ttsByEngine[pkg]?.voices
            } catch (_: Exception) {
                null
            }
            engineVoices[pkg] = vs?.map { EngVoice(pkg, engineLabels[pkg] ?: pkg, it) } ?: emptyList()
        }
        pendingEngines -= 1
        if (pendingEngines <= 0) finishLoading()
    }

    private fun finishLoading() {
        binding.progress.visibility = View.GONE
        allVoices = engineVoices.values.flatten()
        if (allVoices.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(R.string.no_voices_reported)
            return
        }
        binding.tvEmpty.visibility = View.GONE
        val n = engineVoices.keys.size
        supportActionBar?.subtitle = if (n == 1) {
            getString(R.string.engine_voices, n, allVoices.size)
        } else {
            getString(R.string.engines_voices, n, allVoices.size)
        }
        rebuildRows()
    }

    /** Remove selection keys that belong to engines no longer installed. */
    private fun pruneDeselected(installedPkgs: Set<String>) {
        val ds = HashSet(deselectedSet())
        val changed = ds.removeAll { key ->
            val pkg = key.substringBefore("|")
            // Old-format keys (plain voice names) or missing engines are dropped.
            "|" !in key || pkg !in installedPkgs
        }
        if (changed) prefs.edit().putStringSet("deselected_voices", ds).apply()
    }

    private fun deselectedSet(): Set<String> =
        prefs.getStringSet("deselected_voices", emptySet()) ?: emptySet()

    private fun stateOf(names: Collection<String>, des: Set<String>): Int {
        if (names.isEmpty()) return STATE_UNCHECKED
        val selected = names.count { it !in des }
        return when {
            selected == 0 -> STATE_UNCHECKED
            selected == names.size -> STATE_CHECKED
            else -> STATE_INDETERMINATE
        }
    }

    private fun langCode(locale: Locale) = locale.language.ifBlank { "und" }
    private fun countryCode(locale: Locale) = locale.country.ifBlank { "" }

    private fun langOf(lang: String) = allVoices.filter { langCode(it.voice.locale) == lang }
    private fun subOf(lang: String, country: String) =
        langOf(lang).filter { countryCode(it.voice.locale) == country }

    private fun setGroup(keys: Collection<String>, selected: Boolean) {
        val ds = HashSet(deselectedSet())
        if (selected) keys.forEach { ds.remove(it) } else keys.forEach { ds.add(it) }
        prefs.edit().putStringSet("deselected_voices", ds).apply()
    }

    private fun rebuildRows() {
        val des = deselectedSet()
        val rows = ArrayList<Row>()
        rows.add(Row(TYPE_MASTER, text = getString(R.string.all_languages), state = stateOf(allVoices.map { it.key }, des)))

        val byLang = allVoices.groupBy { langCode(it.voice.locale) }
        for (lang in byLang.keys.sortedBy { langLabel(it) }) {
            val langVoices = byLang.getValue(lang)
            val langExpanded = lang in expandedLangs
            rows.add(Row(TYPE_LANG, text = langLabel(lang), key = lang,
                state = stateOf(langVoices.map { it.key }, des), expanded = langExpanded))
            if (!langExpanded) continue
            val byCountry = langVoices.groupBy { countryCode(it.voice.locale) }
            for (country in byCountry.keys.sortedBy { countryLabel(lang, it) }) {
                val subKey = "$lang|$country"
                val subVoices = byCountry.getValue(country)
                val subExpanded = subKey in expandedSubs
                rows.add(Row(TYPE_SUB, text = countryLabel(lang, country), key = subKey,
                    state = stateOf(subVoices.map { it.key }, des), expanded = subExpanded))
                if (!subExpanded) continue
                val sorted = subVoices.sortedWith(
                    compareBy<EngVoice>({ it.engineLabel.lowercase() }, { it.voice.name.lowercase() })
                )
                for (ev in sorted) {
                    rows.add(Row(TYPE_VOICE, text = ev.voice.name, detail = voiceDetail(ev),
                        key = ev.key, engVoice = ev,
                        state = if (ev.key in des) STATE_UNCHECKED else STATE_CHECKED))
                }
            }
        }
        adapter.submit(rows)
    }

    private fun headerKeys(row: Row): List<String> = when (row.type) {
        TYPE_MASTER -> allVoices.map { it.key }
        TYPE_LANG -> langOf(row.key).map { it.key }
        else -> {
            val parts = row.key.split("|", limit = 2)
            subOf(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }).map { it.key }
        }
    }

    private fun onHeaderCheck(row: Row) {
        val keys = headerKeys(row)
        if (keys.isEmpty()) return
        val allSelected = stateOf(keys, deselectedSet()) == STATE_CHECKED
        setGroup(keys, !allSelected)
        rebuildRows()
    }

    private fun onHeaderRow(row: Row) {
        when (row.type) {
            TYPE_MASTER -> onHeaderCheck(row)
            TYPE_LANG -> {
                if (!expandedLangs.remove(row.key)) expandedLangs.add(row.key)
                rebuildRows()
            }
            TYPE_SUB -> {
                if (!expandedSubs.remove(row.key)) expandedSubs.add(row.key)
                rebuildRows()
            }
        }
    }

    private fun onVoiceRow(row: Row) {
        val ev = row.engVoice ?: return
        val selectNow = row.state != STATE_CHECKED
        setGroup(listOf(ev.key), selectNow)
        playSample(ev)
        rebuildRows()
    }

    private fun playSample(ev: EngVoice) {
        val t = ttsByEngine[ev.enginePkg] ?: ttsByEngine.values.firstOrNull() ?: return
        try {
            t.stop()
            t.voice = ev.voice
            t.speak(sampleFor(ev.voice.locale), TextToSpeech.QUEUE_FLUSH, null, SAMPLE_ID)
        } catch (_: Exception) {
        }
    }

    private fun sampleFor(locale: Locale): String = when (locale.language.lowercase()) {
        "de" -> "Dies ist eine Hörprobe dieser Stimme."
        "fr" -> "Ceci est un exemple de cette voix."
        "es" -> "Esta es una muestra de esta voz."
        "it" -> "Questo è un esempio di questa voce."
        "pt" -> "Esta é uma amostra desta voz."
        "nl" -> "Dit is een voorbeeld van deze stem."
        "sv" -> "Detta är ett prov på den här rösten."
        "da" -> "Dette er en prøve på denne stemme."
        "no", "nb", "nn" -> "Dette er en prøve på denne stemmen."
        "ru" -> "Это пример этого голоса."
        "zh" -> "这是此语音的示例。"
        "ja" -> "これはこの声のサンプルです。"
        else -> "This is a sample of this voice."
    }

    private fun langLabel(lang: String): String {
        val name = Locale(lang).displayLanguage.ifBlank { lang }
        return name.replaceFirstChar { it.uppercase() }
    }

    private fun countryLabel(lang: String, country: String): String {
        if (country.isBlank()) return getString(R.string.general)
        val name = Locale(lang, country).displayCountry.ifBlank { country }
        return name.replaceFirstChar { it.uppercase() }
    }

    private fun voiceDetail(ev: EngVoice): String {
        val v = ev.voice
        val parts = ArrayList<String>()
        parts.add(ev.engineLabel)
        parts.add(v.locale.toString())
        val n = v.name.lowercase()
        if (n.contains("#male")) parts.add("male")
        else if (n.contains("#female")) parts.add("female")
        parts.add(if (v.isNetworkConnectionRequired) "online" else "offline")
        parts.add("q${v.quality}")
        return parts.joinToString(" · ")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        for (t in ttsByEngine.values) {
            try { t.shutdown() } catch (_: Exception) {}
        }
        ttsByEngine.clear()
        super.onDestroy()
    }

    private inner class VoiceAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        fun submit(newRows: List<Row>) { rows = newRows; notifyDataSetChanged() }
        override fun getItemViewType(position: Int): Int = rows[position].type
        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_VOICE) {
                VoiceVH(ItemVoiceBinding.inflate(inflater, parent, false))
            } else {
                HeaderVH(ItemVoiceHeaderBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            when (holder) {
                is VoiceVH -> holder.bind(row)
                is HeaderVH -> holder.bind(row)
            }
        }

        inner class VoiceVH(private val b: ItemVoiceBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: Row) {
                b.tvName.text = row.text
                b.tvDetail.text = row.detail
                b.ivCheck.setImageResource(indicator(row.state))
                b.root.setOnClickListener { onVoiceRow(row) }
            }
        }

        inner class HeaderVH(private val b: ItemVoiceHeaderBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: Row) {
                b.tvHeader.text = row.text
                b.ivCheck.setImageResource(indicator(row.state))
                when (row.type) {
                    TYPE_MASTER -> {
                        b.ivChevron.visibility = View.INVISIBLE
                        b.tvHeader.setTypeface(null, Typeface.BOLD)
                        b.tvHeader.textSize = 16f
                        b.tvHeader.setTextColor(0xFF000000.toInt())
                        b.root.setPadding(dp(8), b.root.paddingTop, dp(16), b.root.paddingBottom)
                    }
                    TYPE_LANG -> {
                        b.ivChevron.visibility = View.VISIBLE
                        b.ivChevron.rotation = if (row.expanded) 90f else 0f
                        b.tvHeader.setTypeface(null, Typeface.BOLD)
                        b.tvHeader.textSize = 16f
                        b.tvHeader.setTextColor(0xFF000000.toInt())
                        b.root.setPadding(dp(8), b.root.paddingTop, dp(16), b.root.paddingBottom)
                    }
                    else -> {
                        b.ivChevron.visibility = View.VISIBLE
                        b.ivChevron.rotation = if (row.expanded) 90f else 0f
                        b.tvHeader.setTypeface(null, Typeface.NORMAL)
                        b.tvHeader.textSize = 14f
                        b.tvHeader.setTextColor(0xFF555555.toInt())
                        b.root.setPadding(dp(28), b.root.paddingTop, dp(16), b.root.paddingBottom)
                    }
                }
                b.ivCheck.setOnClickListener { onHeaderCheck(row) }
                b.root.setOnClickListener { onHeaderRow(row) }
            }
        }

        private fun indicator(state: Int): Int = when (state) {
            STATE_CHECKED -> R.drawable.ic_check_on
            STATE_INDETERMINATE -> R.drawable.ic_check_multi
            else -> R.drawable.ic_check_off
        }
    }
}
