package com.echoai.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.echoai.databinding.ItemPinnedAlertBinding
import com.echoai.domain.PinnedAlert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PinnedAlertAdapter(
    private val onDismiss: (label: String) -> Unit,
    private val onTap: (label: String) -> Unit = {},
) : ListAdapter<PinnedAlert, PinnedAlertAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    inner class VH(val binding: ItemPinnedAlertBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPinnedAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val alert = getItem(position)
        val b = holder.binding
        b.labelText.text = alert.label
        b.urgencyBadge.text = alert.urgency.name
        b.urgencyBadge.backgroundTintList = ColorStateList.valueOf(alert.urgency.color)
        b.urgencyStrip.setBackgroundColor(alert.urgency.color)
        b.priorityIcon.visibility = if (alert.isPrioritized) View.VISIBLE else View.GONE
        b.timeText.text = timeFmt.format(Date(alert.detectedAtMs))
        b.dismissButton.setOnClickListener { onDismiss(alert.label) }
        holder.binding.root.setOnClickListener { onTap(alert.label) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PinnedAlert>() {
            override fun areItemsTheSame(old: PinnedAlert, new: PinnedAlert) =
                old.label == new.label

            override fun areContentsTheSame(old: PinnedAlert, new: PinnedAlert) =
                old == new
        }
    }
}
