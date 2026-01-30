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
 * Dialog pour ajouter un nouveau calque
 */
class AddLayerDialog : DialogFragment() {

    companion object {
        private const val TAG = "AddLayerDialog"

        fun newInstance(
            existingNames: List<String>,
            onConfirm: (String) -> Unit
        ): AddLayerDialog {
            return AddLayerDialog().apply {
                this.existingNames = existingNames
                this.onConfirm = onConfirm
            }
        }
    }

    private var existingNames: List<String> = emptyList()
    private var onConfirm: ((String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Logger.entry(TAG, "onCreateDialog")

        val context = ContextThemeWrapper(requireContext(), R.style.Theme_CataMap_Dialog)

        // Créer l'input
        val input = EditText(context)  // ← Utiliser themedContext au lieu de requireContext()
        input.hint = "Nom de la catégorie"
        input.setPadding(50, 30, 50, 30)  // Ajouter du padding pour mieux voir


        // Créer le dialog avec le thème personnalisé
        val dialog = MaterialAlertDialogBuilder(context, R.style.Theme_CataMap_Dialog)
            .setTitle("Nouveau calque")
            .setView(input)
            .setPositiveButton("Créer", null) // null pour gérer manuellement
            .setNegativeButton("Annuler", null)
            .create()

        // Gérer le clic sur "Créer" manuellement pour valider avant de fermer
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val name = input.text.toString().trim()

                // Validation de base
                if (name.isEmpty()) {
                    input.error = "Le nom ne peut pas être vide"
                    return@setOnClickListener
                }

                // Vérifier la longueur uniquement
                if (name.length > 30) {
                    input.error = "Maximum 30 caractères"
                    return@setOnClickListener
                }

                // Vérifier l'unicité (insensible à la casse)
                val isDuplicate = existingNames.any { it.equals(name, ignoreCase = true) }
                if (isDuplicate) {
                    input.error = "Un calque avec ce nom existe déjà"
                    return@setOnClickListener
                }

                Logger.d(TAG, "Layer name validated: $name")
                onConfirm?.invoke(name)
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