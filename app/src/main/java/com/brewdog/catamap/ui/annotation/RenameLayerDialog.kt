package com.brewdog.catamap.ui.annotation

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.brewdog.catamap.R
import com.brewdog.catamap.utils.logging.Logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog pour renommer un calque
 */
class RenameLayerDialog : DialogFragment() {

    companion object {
        private const val TAG = "RenameLayerDialog"
        private const val ARG_LAYER_ID = "layer_id"
        private const val ARG_CURRENT_NAME = "current_name"

        fun newInstance(
            layerId: String,
            currentName: String,
            existingNames: List<String>,
            onConfirm: (String) -> Unit
        ): RenameLayerDialog {
            return RenameLayerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_LAYER_ID, layerId)
                    putString(ARG_CURRENT_NAME, currentName)
                }
                this.existingNames = existingNames
                this.onConfirm = onConfirm
            }
        }
    }

    private var existingNames: List<String> = emptyList()
    private var onConfirm: ((String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Logger.entry(TAG, "onCreateDialog")

        val context = requireContext()
        val currentName = arguments?.getString(ARG_CURRENT_NAME) ?: ""

        // Créer l'input
        val input = EditText(context).apply {
            setText(currentName)
            hint = "Nom du calque"
            setPadding(50, 30, 50, 30)
            inputType = InputType.TYPE_CLASS_TEXT

            // Sélectionner tout le texte
            setSelectAllOnFocus(true)
            requestFocus()
        }

        // Créer le dialog avec le thème personnalisé
        val dialog = MaterialAlertDialogBuilder(context, R.style.Theme_CataMap_Dialog)
            .setTitle("Renommer le calque")
            .setView(input)
            .setPositiveButton("Renommer", null) // null pour gérer manuellement
            .setNegativeButton("Annuler", null)
            .create()

        // Gérer le clic sur "Renommer" manuellement
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val newName = input.text.toString().trim()

                // Validation de base
                if (newName.isEmpty()) {
                    input.error = "Le nom ne peut pas être vide"
                    return@setOnClickListener
                }

                // Pas de changement
                if (newName.equals(currentName, ignoreCase = true)) {
                    dismiss()
                    return@setOnClickListener
                }

                // Vérifier la longueur uniquement
                if (newName.length > 30) {
                    input.error = "Maximum 30 caractères"
                    return@setOnClickListener
                }

                // Vérifier l'unicité (insensible à la casse)
                val isDuplicate = existingNames.any {
                    it.equals(newName, ignoreCase = true) && !it.equals(currentName, ignoreCase = true)
                }
                if (isDuplicate) {
                    input.error = "Un calque avec ce nom existe déjà"
                    return@setOnClickListener
                }

                Logger.d(TAG, "New layer name validated: $newName")
                onConfirm?.invoke(newName)
                dismiss()
            }
        }

        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onConfirm = null
    }
}