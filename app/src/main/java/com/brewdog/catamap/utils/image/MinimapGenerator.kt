package com.brewdog.catamap.utils.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale

/**
 * Générateur de minimap avec redimensionnement intelligent
 * Préserve le ratio et limite la taille max du côté le plus long
 */
object MinimapGenerator {

    // PARAMÈTRES CONFIGURABLES
    private const val MINIMAP_SCALE_PERCENT = 0.15f  // 15% de la taille originale
    private const val MIN_MINIMAP_SIZE = 200         // Taille minimale (pour petites cartes)
    private const val MAX_MINIMAP_SIZE = 1500        // Taille maximale (sécurité mémoire)


    /**
     * Génère une minimap à partir d'une image source
     *
     * @param context Context Android
     * @param sourceUri URI de l'image source
     * @return URI de la minimap générée, ou null en cas d'erreur
     */
    suspend fun generateMinimap(
        context: Context,
        sourceUri: Uri
    ): Uri? = withContext(Dispatchers.IO) {
        var sourceBitmap: Bitmap? = null
        var minimapBitmap: Bitmap? = null

        try {
            // 1. Obtenir les dimensions de l'image source
            val sourceDimensions = getImageDimensions(context, sourceUri)
            if (sourceDimensions == null) {
                Log.e("MinimapGenerator", "Impossible d'obtenir les dimensions")
                return@withContext null
            }

            val (sourceWidth, sourceHeight) = sourceDimensions
            Log.d("MinimapGenerator", "Source: ${sourceWidth}×${sourceHeight}")

            // 2. Calculer la taille optimale de la minimap
            val (minimapWidth, minimapHeight) = calculateMinimapSize(sourceWidth, sourceHeight)
            Log.d("MinimapGenerator", "Minimap: ${minimapWidth}×${minimapHeight}")

            // 3. Charger l'image source avec un sample rate adapté
            sourceBitmap = loadBitmapOptimized(context, sourceUri, minimapWidth, minimapHeight)
            if (sourceBitmap == null) {
                Log.e("MinimapGenerator", "Impossible de charger l'image source")
                return@withContext null
            }

            // 4. Redimensionner au ratio exact
            minimapBitmap = sourceBitmap.scale(minimapWidth, minimapHeight)

            // 5. Sauvegarder en PNG (lossless, important pour les cartes)
            val outputFile = File(context.cacheDir, "minimap_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { out ->
                minimapBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Log.d("MinimapGenerator", "Minimap générée: ${outputFile.name}")

            return@withContext Uri.fromFile(outputFile)

        } catch (e: Exception) {
            Log.e("MinimapGenerator", "Erreur génération minimap", e)
            return@withContext null
        } finally {
            // Libérer la mémoire
            sourceBitmap?.recycle()
            minimapBitmap?.recycle()
        }
    }

    /**
    * Calcule la taille optimale de la minimap en préservant le ratio
    * Utilise un pourcentage de la taille originale (15%)
    */
    private fun calculateMinimapSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        // Calculer 15% de la taille originale
        var width = (sourceWidth * MINIMAP_SCALE_PERCENT).toInt()
        var height = (sourceHeight * MINIMAP_SCALE_PERCENT).toInt()

        // Appliquer les limites min/max
        width = width.coerceIn(MIN_MINIMAP_SIZE, MAX_MINIMAP_SIZE)
        height = height.coerceIn(MIN_MINIMAP_SIZE, MAX_MINIMAP_SIZE)

        // Si une dimension dépasse MAX, réduire proportionnellement
        if (width > MAX_MINIMAP_SIZE || height > MAX_MINIMAP_SIZE) {
            val ratio = sourceWidth.toFloat() / sourceHeight.toFloat()

            if (width > height) {
                width = MAX_MINIMAP_SIZE
                height = (MAX_MINIMAP_SIZE / ratio).toInt()
            } else {
                height = MAX_MINIMAP_SIZE
                width = (MAX_MINIMAP_SIZE * ratio).toInt()
            }
        }

        Log.d(
            "MinimapGenerator",
            "Source: ${sourceWidth}×${sourceHeight} → Minimap: ${width}×${height} (${(MINIMAP_SCALE_PERCENT * 100).toInt()}%)"
        )

        return Pair(width, height)
    }

    /**
     * Obtient les dimensions d'une image sans la charger en mémoire
     */
    private fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, options)
                Pair(options.outWidth, options.outHeight)
            }
        } catch (e: Exception) {
            Log.e("MinimapGenerator", "Erreur lecture dimensions", e)
            null
        }
    }

    /**
     * Charge un bitmap avec un sample rate optimal pour économiser la RAM
     */
    private fun loadBitmapOptimized(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            // Première passe : obtenir les dimensions
            val dimensions = getImageDimensions(context, uri) ?: return null
            val (sourceWidth, sourceHeight) = dimensions

            // Calculer le sample rate (puissance de 2)
            val sampleSize = calculateSampleSize(
                sourceWidth, sourceHeight,
                targetWidth, targetHeight
            )

            Log.d("MinimapGenerator", "Sample size: $sampleSize")

            // Deuxième passe : charger avec le sample rate
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.e("MinimapGenerator", "Erreur chargement bitmap", e)
            null
        }
    }

    /**
     * Calcule le sample rate optimal (puissance de 2)
     */
    private fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sampleSize = 1

        if (sourceWidth > targetWidth || sourceHeight > targetHeight) {
            val halfWidth = sourceWidth / 2
            val halfHeight = sourceHeight / 2

            // Doubler le sample size tant que les dimensions restent > target
            while ((halfWidth / sampleSize) >= targetWidth &&
                (halfHeight / sampleSize) >= targetHeight
            ) {
                sampleSize *= 2
            }
        }

        return sampleSize
    }
}