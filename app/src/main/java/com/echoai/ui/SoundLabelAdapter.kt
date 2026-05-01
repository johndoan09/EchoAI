package com.echoai.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.echoai.R
import com.echoai.databinding.ItemSoundLabelBinding
import com.echoai.domain.Urgency
import com.echoai.domain.UrgencyClassifier

class SoundLabelAdapter(
    private val urgencyClassifier: UrgencyClassifier,
    private val onToggle: (label: String, checked: Boolean) -> Unit,
    private val onUrgencyChange: (label: String, urgency: Urgency?) -> Unit,
) : RecyclerView.Adapter<SoundLabelAdapter.VH>() {

    private var allLabels: List<String> = emptyList()
    private var filtered: List<String> = emptyList()
    private val checkedLabels = mutableSetOf<String>()
    private var sortLabels: Set<String> = emptySet()
    private var urgencyOverrides: Map<String, Urgency> = emptyMap()
    private var query = ""

    fun currentLabels(): List<String> = allLabels

    fun setData(labels: List<String>, checked: Set<String>, overrides: Map<String, Urgency>) {
        allLabels = labels.sortedWith(compareBy { it.lowercase() })
        checkedLabels.clear()
        checkedLabels.addAll(checked)
        sortLabels = checked.toSet()
        urgencyOverrides = overrides
        applyFilter()
    }

    fun commitSort() {
        sortLabels = checkedLabels.toSet()
        applyFilter()
    }

    fun resetTo(labels: Set<String>) {
        checkedLabels.clear()
        checkedLabels.addAll(labels)
        notifyDataSetChanged()
    }

    fun filter(q: String) {
        query = q
        applyFilter()
    }

    private fun applyFilter() {
        val base = if (query.isBlank()) allLabels
        else allLabels.filter { it.contains(query, ignoreCase = true) }
        filtered = base.sortedWith(
            compareByDescending<String> { it in sortLabels }.thenBy { it.lowercase() }
        )
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemSoundLabelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSoundLabelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val label = filtered[position]
        val b = holder.binding
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val isChecked = label in checkedLabels

        b.checkBox.setOnCheckedChangeListener(null)
        b.labelName.text = label
        b.checkBox.isChecked = isChecked

        b.root.isActivated = isChecked
        b.labelName.setTextColor(
            ContextCompat.getColor(ctx, if (isChecked) R.color.text else R.color.muted)
        )
        b.labelName.typeface = if (isChecked)
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        else
            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)

        val isOverridden = label in urgencyOverrides
        val effectiveUrgency = urgencyOverrides[label] ?: urgencyClassifier.classify(label)

        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 5f * density
            setColor(effectiveUrgency.tintBackground)
            setStroke((1 * density).toInt(), withAlpha(effectiveUrgency.color, 0x55))
        }
        b.urgencyBadge.background = badgeBg
        b.urgencyBadge.text = effectiveUrgency.name
        b.urgencyBadge.setTextColor(effectiveUrgency.textColor)
        b.urgencyBadge.paintFlags = if (isOverridden)
            b.urgencyBadge.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        else
            b.urgencyBadge.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()

        b.checkBox.setOnCheckedChangeListener { _, isChk ->
            if (isChk) checkedLabels.add(label) else checkedLabels.remove(label)
            onToggle(label, isChk)
            notifyDataSetChanged()
        }

        b.urgencyBadge.setOnClickListener {
            UrgencyPickerSheet.show(ctx, label, effectiveUrgency, isOverridden) { chosen ->
                if (chosen == null) {
                    urgencyOverrides = urgencyOverrides - label
                    onUrgencyChange(label, null)
                } else {
                    urgencyOverrides = urgencyOverrides + (label to chosen)
                    onUrgencyChange(label, chosen)
                }
                notifyDataSetChanged()
            }
        }
    }

    override fun getItemCount() = filtered.size

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        (rgb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}
