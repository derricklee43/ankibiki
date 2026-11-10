package com.derricklee.ankidict

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.derricklee.ankidict.databinding.DialogPhotoRescanBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A single confirmation; dismissal (including Back/outside) is always cancellation. */
object PhotoRescanDialog {
    fun create(context: Context, onDecision: (PhotoRescanDecision) -> Unit): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.photo_rescan_dialog_title)
        val binding = DialogPhotoRescanBinding.inflate(LayoutInflater.from(builder.context))
        val dialog = builder.setView(binding.root).create()
        var decided = false
        fun decide(decision: PhotoRescanDecision) {
            if (decided) return
            decided = true
            dialog.dismiss()
            onDecision(decision)
        }
        binding.acceptRescanButton.setOnClickListener { decide(PhotoRescanDecision.ACCEPT) }
        binding.skipExtractionButton.setOnClickListener { decide(PhotoRescanDecision.SKIP_TEXT_EXTRACTION) }
        binding.cancelRescanButton.setOnClickListener { decide(PhotoRescanDecision.CANCEL) }
        dialog.setOnCancelListener { decide(PhotoRescanDecision.CANCEL) }
        dialog.setOnDismissListener { decide(PhotoRescanDecision.CANCEL) }
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }
}
