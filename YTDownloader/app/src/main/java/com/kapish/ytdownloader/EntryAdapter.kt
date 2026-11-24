package com.kapish.ytdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kapish.ytdownloader.databinding.ItemEntryBinding

class EntryAdapter(private val onChanged: () -> Unit) : RecyclerView.Adapter<EntryAdapter.VH>() {
    var items: List<PlaylistEntry> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(val b: ItemEntryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        with(holder.b) {
            index.text = "${position + 1}"
            title.text = e.title
            duration.text = formatDuration(e.durationSec)
            check.setOnCheckedChangeListener(null)
            check.isChecked = e.selected
            check.setOnCheckedChangeListener { _, c -> e.selected = c; onChanged() }
            root.setOnClickListener { check.toggle() }
        }
    }

    fun setAll(selected: Boolean) {
        items.forEach { it.selected = selected }
        notifyDataSetChanged()
        onChanged()
    }
}
