package com.rdald.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rdald.app.databinding.ItemSavedTextBinding

/**
 * List adapter with long-press multi-selection (no checkboxes).
 * Tap opens an item; long-press toggles selection and enters selection mode; tap while in
 * selection mode toggles too. Selected rows are highlighted.
 */
class SavedTextAdapter : ListAdapter<SavedText, SavedTextAdapter.ViewHolder>(DiffCallback()) {

    companion object {
        private const val COLOR_SELECTED = 0xFFBBDEFB.toInt() // light blue highlight
        private const val COLOR_NORMAL = Color.WHITE
    }

    private var onItemClickListener: ((SavedText) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    private val selectedIds = LinkedHashSet<String>()
    var isSelectionMode: Boolean = false
        private set

    fun setOnItemClickListener(listener: (SavedText) -> Unit) {
        onItemClickListener = listener
    }

    val selectedCount: Int get() = selectedIds.size

    fun selectedItems(): List<SavedText> = currentList.filter { selectedIds.contains(it.id) }

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSavedTextBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SavedText) {
            binding.tvTitle.text = item.title
            binding.tvPreview.text = item.content.take(100).replace("\n", " ") + "..."
            binding.tvDate.text = android.text.format.DateUtils.formatDateTime(
                itemView.context, item.createdAt,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME
            )
            val selected = selectedIds.contains(item.id)
            binding.root.setCardBackgroundColor(if (selected) COLOR_SELECTED else COLOR_NORMAL)
            binding.root.setOnClickListener {
                if (isSelectionMode) toggleSelection(item.id) else onItemClickListener?.invoke(item)
            }
            binding.root.setOnLongClickListener {
                toggleSelection(item.id)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SavedText>() {
        override fun areItemsTheSame(oldItem: SavedText, newItem: SavedText): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SavedText, newItem: SavedText): Boolean {
            return oldItem == newItem
        }
    }
}
