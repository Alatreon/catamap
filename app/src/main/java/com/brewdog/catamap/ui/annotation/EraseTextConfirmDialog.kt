package com.brewdog.catamap.ui.annotation

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.brewdog.catamap.utils.logging.Logger

/**
 * Dialog de confirmation pour supprimer un texte avec la gomme
 */
class EraseTextConfirmDialog : DialogFragment() {

    companion object {
        private const val TAG = "EraseTextConfirmDialog"
        private const val ARG_TEXT_CONTENT = "text_content"

        fun newInstance(
            textContent: String,
            onConfirm: () -> Unit
        ): EraseTextConfirmDialog {
            return EraseTextConfirmDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TEXT_CONTENT, textContent)
                }
                this.onConfirmCallback = onConfirm
            }
        }
    }

    private var onConfirmCallback: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Logger.entry(TAG, "onCreateDialog")

        val textContent = arguments?.getString(ARG_TEXT_CONTENT) ?: ""

        return AlertDialog.Builder(requireContext())
            .setTitle("Supprimer")
            .setMessage("Supprimer le texte \"$textContent\" ?")
            .setPositiveButton("Supprimer") { _, _ ->
                Logger.d(TAG, "Delete confirmed for: \"$textContent\"")
                onConfirmCallback?.invoke()
            }
            .setNegativeButton("Annuler") { _, _ ->
                Logger.d(TAG, "Delete cancelled")
            }
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onConfirmCallback = null
    }
}