package com.echoai.ui

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.echoai.databinding.ItemHistoryBinding
import com.echoai.domain.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : ListAdapter<HistoryEntry, HistoryAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    inner class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val b = holder.binding
        b.labelText.text = entry.label
        b.urgencyBadge.text = entry.urgency.name
        b.urgencyBadge.backgroundTintList = ColorStateList.valueOf(entry.urgency.color)
        b.urgencyStrip.setBackgroundColor(entry.urgency.color)
        b.profileText.text = entry.profileName
        b.timeText.text = formatTime(entry.timestampMs)
    }

    private fun formatTime(timestampMs: Long): CharSequence {
        val elapsed = System.currentTimeMillis() - timestampMs
        return if (elapsed < DateUtils.HOUR_IN_MILLIS) {
            DateUtils.getRelativeTimeSpanString(
                timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
        } else {
            timeFmt.format(Date(timestampMs))
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HistoryEntry>() {
            override fun areItemsTheSame(old: HistoryEntry, new: HistoryEntry) =
                old.timestampMs == new.timestampMs && old.label == new.label

            override fun areContentsTheSame(old: HistoryEntry, new: HistoryEntry) =
                old == new
        }
    }
}
