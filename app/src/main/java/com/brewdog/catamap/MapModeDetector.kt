package com.brewdog.catamap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.palette.graphics.Palette
import androidx.core.graphics.get

/**
 * Utilitaire pour détecter automatiquement si une image de carte est en mode clair ou sombre
 */
object MapModeDetector {
    /**
     * Méthode 1 : Calcul de la luminosité moyenne
     */
    private fun analyzeWithLuminosity(bitmap: Bitmap): Boolean {
        var totalLuminosity = 0.0
        val sampleSize = 100 // Échantillonner 100 pixels

        for (i in 0 until sampleSize) {
            val x = (bitmap.width * Math.random()).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bitmap.height * Math.random()).toInt().coerceIn(0, bitmap.height - 1)

            val pixel = bitmap[x, y]
            val luminosity = calculateLuminosity(pixel)
            totalLuminosity += luminosity
        }

        val averageLuminosity = totalLuminosity / sampleSize

        // Seuil : < 0.5 = sombre, >= 0.5 = clair
        return averageLuminosity < 0.5
    }

    /**
     * Méthode 2 : Utilisation de Palette pour analyser les couleurs dominantes
     */
    private fun analyzeWithPalette(bitmap: Bitmap): Boolean {
        val palette = Palette.from(bitmap).generate()

        // Récupérer les couleurs dominantes
        val dominantColor = palette.dominantSwatch
        val vibrantColor = palette.vibrantSwatch
        val mutedColor = palette.mutedSwatch

        // Calculer la luminosité moyenne des couleurs dominantes
        val swatches = listOfNotNull(dominantColor, vibrantColor, mutedColor)
        if (swatches.isEmpty()) return false

        val avgLuminosity = swatches.map { swatch ->
            calculateLuminosity(swatch.rgb)
        }.average()

        return avgLuminosity < 0.5
    }

    /**
     * Méthode 3 : Analyse des coins de l'image (optimisée avec échantillonnage)
     * Les marges/légendes sont souvent révélatrices du mode
     */
    private fun analyzeCorners(bitmap: Bitmap): Boolean {
        val cornerSize = (minOf(bitmap.width, bitmap.height) * 0.1).toInt() // 10% de la taille
        val corners = listOf(
            // Coin supérieur gauche
            Pair(0, 0),
            // Coin supérieur droit
            Pair(bitmap.width - cornerSize, 0),
            // Coin inférieur gauche
            Pair(0, bitmap.height - cornerSize),
            // Coin inférieur droit
            Pair(bitmap.width - cornerSize, bitmap.height - cornerSize)
        )

        var totalLuminosity = 0.0
        var pixelCount = 0

        // Échantillonnage: analyser 1 pixel sur 5 pour réduire la charge
        val step = 5

        for ((startX, startY) in corners) {
            var x = startX
            while (x < (startX + cornerSize).coerceAtMost(bitmap.width)) {
                var y = startY
                while (y < (startY + cornerSize).coerceAtMost(bitmap.height)) {
                    val pixel = bitmap[x, y]
                    totalLuminosity += calculateLuminosity(pixel)
                    pixelCount++
                    y += step
                }
                x += step
            }
        }

        val avgLuminosity = totalLuminosity / pixelCount
        return avgLuminosity < 0.5
    }

    /**
     * Méthode 4 : Échantillonnage aléatoire de points sur toute l'image
     */
    private fun analyzeSamplePoints(bitmap: Bitmap): Boolean {
        val sampleCount = 200
        var darkPixelCount = 0

        for (i in 0 until sampleCount) {
            val x = (bitmap.width * Math.random()).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bitmap.height * Math.random()).toInt().coerceIn(0, bitmap.height - 1)

            val pixel = bitmap[x, y]
            val luminosity = calculateLuminosity(pixel)

            if (luminosity < 0.5) {
                darkPixelCount++
            }
        }

        // Si plus de 55% des pixels sont sombres
        return darkPixelCount > (sampleCount * 0.55)
    }

    /**
     * Calcule la luminosité d'un pixel (0.0 = noir, 1.0 = blanc)
     * Utilise la formule de luminosité perceptuelle
     */
    private fun calculateLuminosity(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        // Formule de luminosité perceptuelle (ITU-R BT.709)
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    }

    /**
     * Charge une image en basse résolution pour l'analyse
     */
    private fun loadSampledBitmap(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Première passe : obtenir les dimensions
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                // Calculer le facteur de réduction
                val scale = maxOf(
                    options.outWidth / maxSize,
                    options.outHeight / maxSize,
                    1
                )

                // Deuxième passe : charger l'image réduite
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val loadOptions = BitmapFactory.Options().apply {
                        inSampleSize = scale
                    }
                    BitmapFactory.decodeStream(stream, null, loadOptions)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MapModeDetector", "Error loading bitmap", e)
            null
        }
    }

    /**
     * Version synchrone pour les tests (utiliser avec précaution sur le thread principal)
     * Version corrigée avec gestion mémoire sécurisée
     */
    fun isImageDarkModeSync(context: Context, uri: Uri): Boolean {
        var bitmap: Bitmap? = null
        return try {
            bitmap = loadSampledBitmap(context, uri, maxSize = 512)
            if (bitmap == null) {
                return false
            }

            val methods = listOf(
                analyzeWithLuminosity(bitmap),
                analyzeWithPalette(bitmap),
                analyzeCorners(bitmap),
                analyzeSamplePoints(bitmap)
            )

            val darkVotes = methods.count { it }
            darkVotes >= methods.size / 2

        } catch (e: Exception) {
            android.util.Log.e("MapModeDetector", "Error detecting mode", e)
            false
        } finally {
            bitmap?.recycle()
        }
    }
}