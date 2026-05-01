package com.echoai.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.echoai.R
import com.echoai.databinding.ItemPinnedAlertBinding
import com.echoai.domain.PinnedAlert
import com.echoai.domain.Urgency
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
        val u = alert.urgency
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density

        b.labelText.text = alert.label
        b.urgencyStrip.setBackgroundColor(u.color)

        // Banner background: tinted fill + 1dp border in urgency.color@22
        val bannerBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(u.tintBackground)
            setStroke((1 * density).toInt(), withAlpha(u.color, 0x22))
        }
        b.root.background = bannerBg

        // Icon circle: tinted background, urgency text-color icon
        val iconBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(u.color, 0x22))
        }
        b.iconCircle.background = iconBg
        b.iconImage.setImageResource(
            if (u == Urgency.CRITICAL) R.drawable.ic_warning else R.drawable.ic_info
        )
        b.iconImage.imageTintList = ColorStateList.valueOf(u.textColor)

        // Urgency badge: tinted bg + darker text + 1dp border in urgency.color@55
        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 5f * density
            setColor(u.tintBackground)
            setStroke((1 * density).toInt(), withAlpha(u.color, 0x55))
        }
        b.urgencyBadge.background = badgeBg
        b.urgencyBadge.text = u.name
        b.urgencyBadge.setTextColor(u.textColor)

        b.subtitleTimeText.text = "${if (alert.isPrioritized) "Pinned" else "Detected"} · " +
            timeFmt.format(Date(alert.detectedAtMs))

        b.dismissButton.setOnClickListener { onDismiss(alert.label) }
        b.root.setOnClickListener { onTap(alert.label) }
    }

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        (rgb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PinnedAlert>() {
            override fun areItemsTheSame(old: PinnedAlert, new: PinnedAlert) =
                old.label == new.label

            override fun areContentsTheSame(old: PinnedAlert, new: PinnedAlert) =
                old == new
        }
    }
}
