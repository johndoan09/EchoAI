package com.echoai.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.echoai.databinding.ItemSoundEventBinding
import com.echoai.domain.SoundEvent
import com.echoai.domain.Urgency

class SoundEventAdapter : ListAdapter<SoundEvent, SoundEventAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemSoundEventBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSoundEventBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val event = getItem(position)
        val b = holder.binding
        b.labelText.text = event.label
        b.confidenceText.text = "${(event.confidence * 100).toInt()}%"
        b.urgencyBadge.text = event.urgency.name
        b.urgencyBadge.backgroundTintList = ColorStateList.valueOf(event.urgency.color)
        b.urgencyStrip.setBackgroundColor(event.urgency.color)
        b.priorityIcon.visibility = if (event.isPrioritized) View.VISIBLE else View.GONE
        b.confidenceBar.progress = (event.confidence * 100).toInt()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SoundEvent>() {
            override fun areItemsTheSame(old: SoundEvent, new: SoundEvent) =
                old.label == new.label

            override fun areContentsTheSame(old: SoundEvent, new: SoundEvent) =
                old.confidence == new.confidence &&
                    old.urgency == new.urgency &&
                    old.isPrioritized == new.isPrioritized
        }
    }
}
