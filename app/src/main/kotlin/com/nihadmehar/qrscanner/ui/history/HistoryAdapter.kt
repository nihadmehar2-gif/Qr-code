package com.nihadmehar.qrscanner.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nihadmehar.qrscanner.data.ScanHistoryEntity
import com.nihadmehar.qrscanner.databinding.ItemHistoryBinding
import java.text.DateFormat
import java.util.Date

class HistoryAdapter(
    private val onClick: (ScanHistoryEntity) -> Unit,
    private val onFavorite: (ScanHistoryEntity) -> Unit,
    private val onDelete: (ScanHistoryEntity) -> Unit
) : ListAdapter<ScanHistoryEntity, HistoryAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScanHistoryEntity) {
            binding.tvTitle.text = item.title.ifBlank { item.rawContent }
            binding.tvSubtitle.text = "${formatDate(item.scannedAt)} · ${item.typeLabel}"
            binding.tvRaw.text = item.rawContent
            binding.btnFavorite.setIconResource(
                if (item.isFavorite) com.nihadmehar.qrscanner.R.drawable.ic_star_filled
                else com.nihadmehar.qrscanner.R.drawable.ic_star_outline
            )
            binding.btnFavorite.setOnClickListener { onFavorite(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemHistoryBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ScanHistoryEntity>() {
            override fun areItemsTheSame(oldItem: ScanHistoryEntity, newItem: ScanHistoryEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ScanHistoryEntity, newItem: ScanHistoryEntity) = oldItem == newItem
        }
    }

    private fun formatDate(millis: Long): String {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        return fmt.format(Date(millis))
    }
}
