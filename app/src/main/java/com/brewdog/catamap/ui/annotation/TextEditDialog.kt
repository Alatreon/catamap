package com.brewdog.catamap.ui.annotation

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.ContextThemeWrapper
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.brewdog.catamap.R
import com.brewdog.catamap.utils.logging.Logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog pour saisir ou éditer du texte pour une annotation
 */
class TextEditDialog : DialogFragment() {

    companion object {
        private const val TAG = "TextEditDialog"
        private const val ARG_INITIAL_TEXT = "initial_text"

        fun newInstance(
            initialText: String = "",
            onConfirm: (String) -> Unit
        ): TextEditDialog {
            return TextEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_INITIAL_TEXT, initialText)
                }
                this.onConfirm = onConfirm
            }
        }
    }

    private var onConfirm: ((String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Logger.entry(TAG, "onCreateDialog")

        val context = ContextThemeWrapper(requireContext(), R.style.Theme_CataMap_Dialog)
        val initialText = arguments?.getString(ARG_INITIAL_TEXT) ?: ""

        // Créer l'input
        val input = EditText(context).apply {
            setText(initialText)
            hint = "Saisir le texte"
            setPadding(50, 30, 50, 30)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

            // Sélectionner tout le texte si édition
            if (initialText.isNotEmpty()) {
                setSelectAllOnFocus(true)
            }

            requestFocus()
        }

        // Créer le dialog
        val dialog = MaterialAlertDialogBuilder(context, R.style.Theme_CataMap_Dialog)
            .setTitle(if (initialText.isEmpty()) "Ajouter du texte" else "Modifier le texte")
            .setView(input)
            .setPositiveButton("OK", null) // null pour gérer manuellement
            .setNegativeButton("Annuler", null)
            .create()

        // Gérer le clic sur "OK" manuellement
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val text = input.text.toString()

                // Permettre le texte vide (pour suppression future)
                Logger.d(TAG, "Text confirmed: \"$text\"")
                onConfirm?.invoke(text)
                dismiss()
            }
        }

        // Afficher le clavier automatiquement
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onConfirm = null
    }
}