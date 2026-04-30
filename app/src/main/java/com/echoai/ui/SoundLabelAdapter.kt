package com.echoai.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
    private var urgencyOverrides: Map<String, Urgency> = emptyMap()
    private var query = ""

    fun currentLabels(): List<String> = allLabels

    fun setData(labels: List<String>, checked: Set<String>, overrides: Map<String, Urgency>) {
        allLabels = labels.sortedWith(compareBy { it.lowercase() })
        checkedLabels.clear()
        checkedLabels.addAll(checked)
        urgencyOverrides = overrides
        applyFilter()
    }

    fun filter(q: String) {
        query = q
        applyFilter()
    }

    private fun applyFilter() {
        val base = if (query.isBlank()) allLabels
        else allLabels.filter { it.contains(query, ignoreCase = true) }
        filtered = base.sortedWith(compareByDescending<String> { it in checkedLabels }.thenBy { it.lowercase() })
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemSoundLabelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSoundLabelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val label = filtered[position]
        val b = holder.binding

        b.checkBox.setOnCheckedChangeListener(null)
        b.labelName.text = label
        b.checkBox.isChecked = label in checkedLabels

        val isOverridden = label in urgencyOverrides
        val effectiveUrgency = urgencyOverrides[label] ?: urgencyClassifier.classify(label)
        b.urgencyBadge.text = effectiveUrgency.name
        b.urgencyBadge.setTextColor(effectiveUrgency.color)
        // Underline when overridden to signal customization
        b.urgencyBadge.paintFlags = if (isOverridden)
            b.urgencyBadge.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        else
            b.urgencyBadge.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()

        b.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checkedLabels.add(label) else checkedLabels.remove(label)
            onToggle(label, isChecked)
            applyFilter()
        }

        b.urgencyBadge.setOnClickListener {
            showUrgencyPicker(holder, label, effectiveUrgency, isOverridden)
        }
    }

    override fun getItemCount() = filtered.size

    private fun showUrgencyPicker(
        holder: VH,
        label: String,
        current: Urgency,
        isOverridden: Boolean,
    ) {
        val urgencyOptions = Urgency.entries.map { it.name }
        val options = (urgencyOptions + "Reset to default").toTypedArray()
        val resetIdx = urgencyOptions.size
        val currentIdx = if (isOverridden) Urgency.entries.indexOf(current) else -1

        AlertDialog.Builder(holder.itemView.context)
            .setTitle("Urgency for \"$label\"")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                if (which == resetIdx) {
                    urgencyOverrides = urgencyOverrides - label
                    onUrgencyChange(label, null)
                } else {
                    val chosen = Urgency.entries[which]
                    urgencyOverrides = urgencyOverrides + (label to chosen)
                    onUrgencyChange(label, chosen)
                }
                notifyDataSetChanged()
                dialog.dismiss()
            }
            .show()
    }
}
