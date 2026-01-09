package com.brewdog.catamap

import android.content.Context
import android.graphics.*
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

object MapImageConverter {

    enum class ConversionMode {
        INVERT,
        SMART_DARKEN,
        SMART_LIGHTEN
    }

    /**
     * Version optimisée avec GARANTIE de dimensions identiques
     */
    suspend fun generateAlternateVersionOptimized(
        context: Context,
        sourceUri: Uri,
        mode: ConversionMode
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // 1. Obtenir les DIMENSIONS EXACTES de l'image source
            val originalDimensions = getImageDimensions(context, sourceUri)
            if (originalDimensions == null) {
                android.util.Log.e("MapImageConverter", "Cannot get original dimensions")
                return@withContext null
            }

            val (originalWidth, originalHeight) = originalDimensions
            android.util.Log.d("MapImageConverter", "Original dimensions: ${originalWidth}x${originalHeight}")

            // 2. Charger l'image source
            val sourceBitmap = loadBitmapFullSize(context, sourceUri)
            if (sourceBitmap == null) {
                android.util.Log.e("MapImageConverter", "Cannot load source bitmap")
                return@withContext null
            }

            android.util.Log.d("MapImageConverter", "Loaded bitmap: ${sourceBitmap.width}x${sourceBitmap.height}")

            // 3. Appliquer la transformation
            val transformedBitmap = when (mode) {
                ConversionMode.INVERT -> applyColorMatrix(sourceBitmap, getInvertMatrix())
                ConversionMode.SMART_DARKEN -> applyColorMatrix(sourceBitmap, getDarkenMatrix())
                ConversionMode.SMART_LIGHTEN -> applyColorMatrix(sourceBitmap, getLightenMatrix())
            }

            // 4. 🔧 FORCER le redimensionnement exact aux dimensions originales
            val finalBitmap = if (transformedBitmap.width != originalWidth || transformedBitmap.height != originalHeight) {
                android.util.Log.d("MapImageConverter", "Resizing from ${transformedBitmap.width}x${transformedBitmap.height} to ${originalWidth}x${originalHeight}")
                val resized = transformedBitmap.scale(originalWidth, originalHeight)
                transformedBitmap.recycle()
                resized
            } else {
                android.util.Log.d("MapImageConverter", "Dimensions already match, no resize needed")
                transformedBitmap
            }

            // 5. Sauvegarder en PNG (compression lossless)
            val outputFile = File(context.cacheDir, "converted_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            android.util.Log.d("MapImageConverter", "✅ Generated: ${finalBitmap.width}x${finalBitmap.height} → ${outputFile.name}")

            sourceBitmap.recycle()
            finalBitmap.recycle()

            Uri.fromFile(outputFile)

        } catch (e: Exception) {
            android.util.Log.e("MapImageConverter", "Error in conversion", e)
            null
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
            android.util.Log.e("MapImageConverter", "Error getting dimensions", e)
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
            android.util.Log.e("MapImageConverter", "Error loading bitmap", e)
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

    private fun getDarkenMatrix() = ColorMatrix(
        floatArrayOf(
            0.4f, 0f, 0f, 0f, 0f,
            0f, 0.4f, 0f, 0f, 0f,
            0f, 0f, 0.4f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    private fun getLightenMatrix() = ColorMatrix(
        floatArrayOf(
            1.2f, 0f, 0f, 0f, 50f,
            0f, 1.2f, 0f, 0f, 50f,
            0f, 0f, 1.2f, 0f, 50f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}