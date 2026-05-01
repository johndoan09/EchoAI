package com.echoai.ui

import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.echoai.R
import com.echoai.databinding.ItemHistoryBinding
import com.echoai.domain.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val highlightLabel: String? = null,
    private val onDismiss: (HistoryEntry) -> Unit = {},
) : ListAdapter<HistoryEntry, HistoryAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    inner class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val b = holder.binding
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val u = entry.urgency

        b.labelText.text = entry.label
        b.urgencyStrip.setBackgroundColor(u.color)

        val rowBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            if (entry.label == highlightLabel) {
                setColor(ContextCompat.getColor(ctx, R.color.accentBg))
                setStroke((1 * density).toInt(), withAlpha(ContextCompat.getColor(ctx, R.color.accent), 0x66))
            } else {
                setColor(ContextCompat.getColor(ctx, R.color.surface))
            }
        }
        b.root.background = rowBg

        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 5f * density
            setColor(u.tintBackground)
            setStroke((1 * density).toInt(), withAlpha(u.color, 0x55))
        }
        b.urgencyBadge.background = badgeBg
        b.urgencyBadge.text = u.name
        b.urgencyBadge.setTextColor(u.textColor)

        b.profileText.text = entry.profileName
        b.timeText.text = formatTime(entry.timestampMs)

        b.dismissButton.setOnClickListener { onDismiss(entry) }
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

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        (rgb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HistoryEntry>() {
            override fun areItemsTheSame(old: HistoryEntry, new: HistoryEntry) =
                old.timestampMs == new.timestampMs && old.label == new.label

            override fun areContentsTheSame(old: HistoryEntry, new: HistoryEntry) =
                old == new
        }
    }
}
