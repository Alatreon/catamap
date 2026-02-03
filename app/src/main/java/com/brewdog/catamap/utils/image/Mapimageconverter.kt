package com.brewdog.catamap.utils.image

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

object MapImageConverter {

    enum class ConversionMode {
        INVERT
    }

    /**
     * Version optimisée avec GARANTIE de dimensions identiques et gestion mémoire sécurisée
     */
    suspend fun generateAlternateVersionOptimized(
        context: Context,
        sourceUri: Uri,
        mode: ConversionMode
    ): Uri? = withContext(Dispatchers.IO) {
        var sourceBitmap: Bitmap? = null
        var transformedBitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null

        try {
            // 1. Obtenir les DIMENSIONS EXACTES de l'image source
            val originalDimensions = getImageDimensions(context, sourceUri)
            if (originalDimensions == null) {
                Log.e("MapImageConverter", "Cannot get original dimensions")
                return@withContext null
            }

            val (originalWidth, originalHeight) = originalDimensions
            Log.d("MapImageConverter", "Original dimensions: ${originalWidth}x${originalHeight}")

            // 2. Charger l'image source
            sourceBitmap = loadBitmapFullSize(context, sourceUri)
            if (sourceBitmap == null) {
                Log.e("MapImageConverter", "Cannot load source bitmap")
                return@withContext null
            }

            Log.d("MapImageConverter", "Loaded bitmap: ${sourceBitmap.width}x${sourceBitmap.height}")

            // 3. Appliquer la transformation (inversion des couleurs)
            transformedBitmap = applyColorMatrix(sourceBitmap, getInvertMatrix())

            // 4. FORCER le redimensionnement exact aux dimensions originales
            finalBitmap = if (transformedBitmap.width != originalWidth || transformedBitmap.height != originalHeight) {
                Log.d("MapImageConverter", "Resizing from ${transformedBitmap.width}x${transformedBitmap.height} to ${originalWidth}x${originalHeight}")
                val resized = transformedBitmap.scale(originalWidth, originalHeight)
                transformedBitmap.recycle()
                transformedBitmap = null
                resized
            } else {
                Log.d("MapImageConverter", "Dimensions already match, no resize needed")
                transformedBitmap
            }

            // 5. Sauvegarder en PNG (compression lossless)
            val outputFile = File(context.cacheDir, "converted_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Log.d("MapImageConverter", "Generated: ${finalBitmap.width}x${finalBitmap.height} → ${outputFile.name}")

            return@withContext Uri.fromFile(outputFile)

        } catch (e: Exception) {
            Log.e("MapImageConverter", "Error in conversion", e)
            return@withContext null
        } finally {
            // Garantir la libération de tous les bitmaps, même en cas d'erreur
            sourceBitmap?.recycle()
            transformedBitmap?.recycle()
            finalBitmap?.recycle()
        }
    }

    /**
     * Obtient les dimensions EXACTES de l'image sans la charger en mémoire
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
            Log.e("MapImageConverter", "Error getting dimensions", e)
            null
        }
    }

    /**
     * Charge l'image en taille réelle
     */
    private fun loadBitmapFullSize(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.e("MapImageConverter", "Error loading bitmap", e)
            null
        }
    }

    private fun applyColorMatrix(source: Bitmap, matrix: ColorMatrix): Bitmap {
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val result = createBitmap(source.width, source.height, config)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            isFilterBitmap = true
            isDither = true
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun getInvertMatrix() = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}