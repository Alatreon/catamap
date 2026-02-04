package com.brewdog.catamap.ui.onboarding

import androidx.annotation.DrawableRes

/**
 * Représente une slide d'onboarding
 * @param iconRes Ressource de l'icône à afficher
 * @param title Titre de la slide
 * @param description Description/contenu de la slide
 */
data class OnboardingSlide(
    @DrawableRes val iconRes: Int,
    val title: String,
    val description: String
)
