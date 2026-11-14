package com.readaloud.app.v2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.readaloud.app.v2.databinding.ItemSavedTextBinding

class SavedTextAdapter : ListAdapter<SavedText, SavedTextAdapter.ViewHolder>(DiffCallback()) {

    private var onItemClickListener: ((SavedText) -> Unit)? = null

    fun setOnItemClickListener(listener: (SavedText) -> Unit) {
        onItemClickListener = listener
    }

    fun addItem(item: SavedText) {
        val currentList = currentList.toMutableList()
        currentList.add(0, item)
        submitList(currentList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(private val binding: ItemSavedTextBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SavedText) {
            binding.tvTitle.text = item.title
            binding.tvPreview.text = item.content.take(100).replace("\n", " ") + "..."
            binding.tvDate.text = android.text.format.DateUtils.formatDateTime(
                itemView.context, item.createdAt,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME
            )
            binding.root.setOnClickListener { onItemClickListener?.invoke(item) }
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
