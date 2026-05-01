package com.echoai.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import com.echoai.R
import com.echoai.databinding.ItemUrgencyOptionBinding
import com.echoai.databinding.SheetUrgencyPickerBinding
import com.echoai.domain.Urgency
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Bottom-sheet urgency picker. `onSelect(null)` means "reset to default".
 */
object UrgencyPickerSheet {

    fun show(
        ctx: Context,
        label: String,
        current: Urgency,
        isOverridden: Boolean,
        onSelect: (Urgency?) -> Unit,
    ) {
        val dialog = BottomSheetDialog(ctx, R.style.Theme_EchoAI_BottomSheet)
        val sheet = SheetUrgencyPickerBinding.inflate(LayoutInflater.from(ctx))
        sheet.sheetLabel.text = label

        for (urgency in Urgency.entries) {
            sheet.optionsContainer.addView(buildOption(ctx, sheet, urgency, urgency == current && isOverridden) {
                onSelect(urgency)
                dialog.dismiss()
            })
        }
        sheet.optionsContainer.addView(buildResetOption(ctx, sheet) {
            onSelect(null)
            dialog.dismiss()
        })

        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun buildOption(
        ctx: Context,
        parent: SheetUrgencyPickerBinding,
        urgency: Urgency,
        selected: Boolean,
        onClick: () -> Unit,
    ): View {
        val density = ctx.resources.displayMetrics.density
        val row = ItemUrgencyOptionBinding.inflate(
            LayoutInflater.from(ctx), parent.optionsContainer, false
        )
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(urgency.color)
        }
        row.colorDot.background = dot
        row.optionLabel.text = urgency.name
        row.optionLabel.setTextColor(if (selected) urgency.textColor else ContextCompat.getColor(ctx, R.color.text))
        row.checkmark.visibility = if (selected) View.VISIBLE else View.GONE

        if (selected) {
            row.root.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(urgency.tintBackground)
            }
        } else {
            row.root.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(0x0CFFFFFF)
            }
        }
        row.root.setOnClickListener { onClick() }
        return row.root
    }

    private fun buildResetOption(
        ctx: Context,
        parent: SheetUrgencyPickerBinding,
        onClick: () -> Unit,
    ): View {
        val density = ctx.resources.displayMetrics.density
        val row = ItemUrgencyOptionBinding.inflate(
            LayoutInflater.from(ctx), parent.optionsContainer, false
        )
        row.colorDot.visibility = View.GONE
        row.optionLabel.text = ctx.getString(R.string.reset_to_default)
        row.optionLabel.setTextColor(ContextCompat.getColor(ctx, R.color.muted))
        row.checkmark.visibility = View.GONE
        row.root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(0x0CFFFFFF)
        }
        row.root.setOnClickListener { onClick() }
        return row.root
    }
}
