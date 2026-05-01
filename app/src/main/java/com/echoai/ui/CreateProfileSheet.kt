package com.echoai.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import com.echoai.R
import com.echoai.databinding.SheetCreateProfileBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

object CreateProfileSheet {

    fun show(ctx: Context, onCreate: (name: String) -> Unit) {
        val dialog = BottomSheetDialog(ctx, R.style.Theme_EchoAI_BottomSheet)
        val sheet = SheetCreateProfileBinding.inflate(LayoutInflater.from(ctx))

        sheet.cancelButton.setOnClickListener { dialog.dismiss() }

        sheet.createButton.setOnClickListener {
            val name = sheet.profileNameInput.text.toString().trim()
            if (name.isNotEmpty()) {
                onCreate(name)
                dialog.dismiss()
            }
        }

        dialog.setContentView(sheet.root)
        dialog.show()

        sheet.profileNameInput.requestFocus()
        sheet.profileNameInput.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(sheet.profileNameInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
