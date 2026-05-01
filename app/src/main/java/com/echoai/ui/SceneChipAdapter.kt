package com.echoai.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.echoai.R
import com.echoai.domain.SoundProfile
import java.util.Collections

class SceneChipAdapter(
    private val onChipClick: (SoundProfile) -> Unit,
    private val onChipLongClick: (SoundProfile) -> Unit,
    private val onAddClick: () -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SoundProfile>()
    private var activeId: String = ""

    companion object {
        const val TYPE_CHIP = 0
        const val TYPE_ADD = 1
    }

    fun submitProfiles(profiles: List<SoundProfile>, activeId: String) {
        items.clear()
        items.addAll(profiles)
        this.activeId = activeId
        notifyDataSetChanged()
    }

    fun setActiveId(id: String) {
        if (activeId == id) return
        activeId = id
        notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    fun orderedIds(): List<String> = items.map { it.id }

    override fun getItemViewType(position: Int) =
        if (position < items.size) TYPE_CHIP else TYPE_ADD

    override fun getItemCount() = items.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_ADD) {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            val size = (36 * dp).toInt()
            val container = FrameLayout(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_chip_add)
                layoutParams = RecyclerView.LayoutParams(size, size)
            }
            val icon = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_plus)
                layoutParams = FrameLayout.LayoutParams(
                    (16 * dp).toInt(), (16 * dp).toInt(), android.view.Gravity.CENTER
                )
            }
            container.addView(icon)
            return AddVH(container)
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scene_chip, parent, false)
        return ChipVH(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ChipVH -> {
                val profile = items[position]
                val active = profile.id == activeId
                val ctx = holder.itemView.context

                holder.text.text = profile.name
                holder.itemView.background = ContextCompat.getDrawable(
                    ctx, if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive
                )
                val color = if (active) ContextCompat.getColor(ctx, R.color.bg)
                            else ContextCompat.getColor(ctx, R.color.muted)
                holder.text.setTextColor(color)
                holder.dragHandle.setColorFilter(color)

                holder.itemView.setOnClickListener { onChipClick(profile) }
                if (!profile.isPreset) {
                    holder.itemView.setOnLongClickListener {
                        onChipLongClick(profile)
                        true
                    }
                } else {
                    holder.itemView.setOnLongClickListener(null)
                }
                holder.dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onDragStart(holder)
                        true
                    } else false
                }
            }
            is AddVH -> {
                holder.itemView.setOnClickListener { onAddClick() }
            }
        }
    }

    inner class ChipVH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.chipText)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
    }

    inner class AddVH(view: View) : RecyclerView.ViewHolder(view)
}
