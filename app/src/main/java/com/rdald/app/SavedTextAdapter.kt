package com.rdald.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.rdald.app.databinding.ItemSavedTextBinding
import java.util.concurrent.Executors

/**
 * Small async favicon loader with an in-memory cache. Favicons are tiny files stored on
 * disk by the download service; decoding happens off the main thread and is guarded
 * against RecyclerView view recycling.
 */
private object FaviconLoader {
    private val cache = LruCache<String, Bitmap>(64)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun load(path: String?, iv: ImageView) {
        if (path.isNullOrBlank()) {
            iv.tag = null
            iv.visibility = View.INVISIBLE
            iv.setImageDrawable(null)
            return
        }
        iv.visibility = View.VISIBLE
        iv.tag = path
        cache.get(path)?.let {
            iv.setImageBitmap(it)
            return
        }
        iv.setImageDrawable(null)
        executor.execute {
            val bmp = try {
                BitmapFactory.decodeFile(path)
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) cache.put(path, bmp)
            main.post {
                if (iv.tag == path && bmp != null) iv.setImageBitmap(bmp)
            }
        }
    }
}

/**
 * List adapter with long-press multi-selection and drag-to-reorder via a dedicated handle.
 * Tap opens an item; long-press toggles selection; drag the handle to reorder.
 */
class SavedTextAdapter : RecyclerView.Adapter<SavedTextAdapter.ViewHolder>() {

    companion object {
        private const val COLOR_SELECTED = 0xFFBBDEFB.toInt() // light blue highlight
        private const val COLOR_PLAYING = 0xFFDDF1E0.toInt() // soft green for now-playing
        private const val COLOR_NORMAL = Color.WHITE
    }

    /** id of the item currently playing (drives the speaker icon + shaded card). */
    var currentPlayingId: String? = null

    private var items: MutableList<SavedText> = ArrayList()
    private var touchHelper: ItemTouchHelper? = null

    private var onItemClickListener: ((SavedText) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    private val selectedIds = LinkedHashSet<String>()
    var isSelectionMode: Boolean = false
        private set

    fun setOnItemClickListener(listener: (SavedText) -> Unit) {
        onItemClickListener = listener
    }

    /** Replace the list (diffed) — call whenever the DB contents are reloaded. */
    fun submit(newItems: List<SavedText>) {
        val old = items.toList()
        val newList = newItems.toMutableList()
        val diff = DiffUtil.calculateDiff(ItemDiff(old, newList), true)
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    fun currentItems(): List<SavedText> = items

    /** Reorder during drag; returns false if the move is invalid. */
    fun moveItem(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) return false
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
        return true
    }

    fun attachTouchHelper(helper: ItemTouchHelper) {
        touchHelper = helper
    }

    val selectedCount: Int get() = selectedIds.size

    fun selectedItems(): List<SavedText> = items.filter { selectedIds.contains(it.id) }

    fun clearSelection() {
        if (selectedIds.isEmpty() && !isSelectionMode) return
        selectedIds.clear()
        isSelectionMode = false
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    private fun toggleSelection(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        isSelectionMode = selectedIds.isNotEmpty()
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(private val binding: ItemSavedTextBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SavedText) {
            FaviconLoader.load(item.iconPath, binding.ivIcon)
            binding.tvTitle.text = item.title
            val compact = itemView.context
                .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("compact_cards", false)
            binding.tvPreview.visibility = if (compact) View.GONE else View.VISIBLE
            binding.tvDate.visibility = if (compact) View.GONE else View.VISIBLE
            val density = itemView.resources.displayMetrics.density
            fun dpx(v: Int) = (v * density + 0.5f).toInt()
            val pad = dpx(if (compact) 6 else 16)
            binding.content.setPadding(pad, pad, pad, pad)
            (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = dpx(if (compact) 3 else 8)
                binding.root.layoutParams = lp
            }
            binding.tvTitle.textSize = if (compact) 15f else 16f
            binding.tvPreview.text = item.content.take(100).replace("\n", " ") + "..."
            val dateStr = android.text.format.DateUtils.formatDateTime(
                itemView.context, item.createdAt,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME
            )
            val rate = itemView.context
                .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getInt("speech_rate", 100) / 100f
            val words = item.content.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            val totalSec = (words / (180.0 * rate.coerceAtLeast(0.5f)) * 60.0).toInt()
            val totalMin = kotlin.math.ceil(totalSec / 60.0).toInt().coerceAtLeast(1)
            binding.tvDate.text = if (item.progress in 1..99) {
                val remSec = totalSec * (100 - item.progress) / 100
                val remMin = kotlin.math.ceil(remSec / 60.0).toInt().coerceAtLeast(1)
                itemView.context.getString(R.string.meta_progress, dateStr, item.progress, totalMin, remMin)
            } else {
                itemView.context.getString(R.string.meta_simple, dateStr, totalMin)
            }
            binding.ivDrag.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(this)
                false
            }
            val selected = selectedIds.contains(item.id)
            val playing = item.id == currentPlayingId
            binding.ivPlaying.visibility = if (playing) View.VISIBLE else View.GONE
            binding.root.setCardBackgroundColor(
                when {
                    selected -> COLOR_SELECTED
                    playing -> COLOR_PLAYING
                    else -> COLOR_NORMAL
                }
            )
            binding.root.setOnClickListener {
                if (isSelectionMode) toggleSelection(item.id) else onItemClickListener?.invoke(item)
            }
            binding.root.setOnLongClickListener {
                toggleSelection(item.id)
                true
            }
        }
    }

    private class ItemDiff(
        private val oldList: List<SavedText>,
        private val newList: List<SavedText>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            oldList[oldPos].id == newList[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            oldList[oldPos] == newList[newPos]
    }
}
