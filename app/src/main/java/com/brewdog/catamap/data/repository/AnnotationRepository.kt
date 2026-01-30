package com.brewdog.catamap.data.repository

import android.content.Context
import android.graphics.PointF
import android.net.Uri
import androidx.core.net.toUri
import com.brewdog.catamap.domain.annotation.models.*
import com.brewdog.catamap.utils.logging.Logger
import com.google.gson.*
import kotlinx.coroutines.*
import java.io.File
import java.lang.reflect.Type

/**
 * Repository pour la gestion de la persistance des annotations
 * Sauvegarde automatique en temps réel avec backup
 */
class AnnotationRepository(private val context: Context) {

    companion object {
        private const val TAG = "AnnotationRepository"
        private const val ANNOTATIONS_DIR = "annotations"
        private const val FILE_EXTENSION = ".json"
        private const val BACKUP_SUFFIX = ".backup"
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(PointF::class.java, PointFSerializer())
        .registerTypeAdapter(PointF::class.java, PointFDeserializer())
        .registerTypeAdapter(AnnotationEdit::class.java, AnnotationSerializer())
        .registerTypeAdapter(AnnotationEdit::class.java, AnnotationDeserializer())
        .setPrettyPrinting()
        .create()

    // Coroutine scope pour les sauvegardes asynchrones
    private val saveJobs = mutableMapOf<String, Job>()
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        Logger.i(TAG, "AnnotationRepository initialized")
        ensureAnnotationsDirectoryExists()
    }

    /**
     * Crée le répertoire d'annotations s'il n'existe pas
     */
    private fun ensureAnnotationsDirectoryExists() {
        val dir = File(context.filesDir, ANNOTATIONS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
            Logger.i(TAG, "Annotations directory created: ${dir.absolutePath}")
        }
    }

    /**
     * Sauvegarde les annotations (avec debounce)
     * Sauvegarde en temps réel, déclenchée à chaque modification
     */
    fun saveAnnotations(mapId: String, annotations: MapAnnotations) {
        Logger.entry(TAG, "saveAnnotations", mapId)

        // Annuler le job précédent s'il existe
        saveJobs[mapId]?.cancel()

        // Créer un nouveau job avec debounce
        saveJobs[mapId] = saveScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveAnnotationsInternal(mapId, annotations)
        }
    }

    /**
     * Sauvegarde immédiate sans debounce
     * Utilisé lors de la fermeture du mode édition
     */
    suspend fun saveAnnotationsImmediate(mapId: String, annotations: MapAnnotations) {
        Logger.entry(TAG, "saveAnnotationsImmediate", mapId)
        saveJobs[mapId]?.cancel()
        saveAnnotationsInternal(mapId, annotations)
    }

    /**
     * Sauvegarde interne avec backup
     */
    private suspend fun saveAnnotationsInternal(mapId: String, annotations: MapAnnotations) {
        try {
            val file = getAnnotationFile(mapId)

            // Créer backup de l'ancienne version
            if (file.exists()) {
                createBackup(file)
            }

            // Mettre à jour le timestamp
            val updatedAnnotations = annotations.copy(
                lastModified = System.currentTimeMillis()
            )

            // Sérialiser en JSON
            val json = gson.toJson(updatedAnnotations)

            // Écrire dans le fichier
            file.writeText(json)

            Logger.i(TAG, "Annotations saved for map $mapId (${annotations.layers.size} layers, ${annotations.getTotalAnnotationCount()} annotations)")

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save annotations for map $mapId", e)
        }
    }

    /**
     * Crée un backup du fichier actuel
     */
    private fun createBackup(file: File) {
        try {
            val backup = File(file.parent, "${file.name}$BACKUP_SUFFIX")
            file.copyTo(backup, overwrite = true)
            Logger.d(TAG, "Backup created: ${backup.name}")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to create backup", e)
        }
    }

    /**
     * Charge les annotations d'une carte
     * Retourne null si aucun fichier n'existe
     */
    suspend fun loadAnnotations(mapId: String): MapAnnotations? = withContext(Dispatchers.IO) {
        Logger.entry(TAG, "loadAnnotations", mapId)

        try {
            val file = getAnnotationFile(mapId)

            if (!file.exists()) {
                Logger.d(TAG, "No annotations file for map $mapId")
                return@withContext null
            }

            // Lire et parser le JSON
            val json = file.readText()
            val annotations = gson.fromJson(json, MapAnnotations::class.java)

            // Valider la structure
            val validation = annotations.validate()
            if (validation is ValidationResult.Invalid) {
                Logger.w(TAG, "Invalid annotations structure: ${validation.errors}")
                // Tenter de charger le backup
                return@withContext loadBackup(mapId)
            }

            // Vérifier la version
            if (annotations.version != MapAnnotations.CURRENT_VERSION) {
                Logger.w(TAG, "Version mismatch: ${annotations.version} vs ${MapAnnotations.CURRENT_VERSION}")
                // TODO: Migration si nécessaire dans une future version
            }

            Logger.i(TAG, "Annotations loaded for map $mapId (${annotations.layers.size} layers)")
            annotations.logState()

            return@withContext annotations

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load annotations for map $mapId, trying backup", e)
            return@withContext loadBackup(mapId)
        }
    }

    /**
     * Charge le backup en cas d'erreur
     */
    private suspend fun loadBackup(mapId: String): MapAnnotations? {
        return try {
            val backupFile = File(
                getAnnotationFile(mapId).parent,
                "annotations_$mapId$FILE_EXTENSION$BACKUP_SUFFIX"
            )

            if (!backupFile.exists()) {
                Logger.w(TAG, "No backup available for map $mapId")
                return null
            }

            val json = backupFile.readText()
            val annotations = gson.fromJson(json, MapAnnotations::class.java)

            Logger.i(TAG, "Annotations loaded from backup for map $mapId")
            annotations

        } catch (e: Exception) {
            Logger.e(TAG, "Backup also failed for map $mapId", e)
            null
        }
    }

    /**
     * Supprime les annotations d'une carte
     */
    suspend fun deleteAnnotations(mapId: String): Boolean = withContext(Dispatchers.IO) {
        Logger.entry(TAG, "deleteAnnotations", mapId)

        try {
            val file = getAnnotationFile(mapId)
            val backup = File(file.parent, "${file.name}$BACKUP_SUFFIX")

            var deleted = false

            if (file.exists()) {
                deleted = file.delete()
                Logger.i(TAG, "Annotations file deleted: $deleted")
            }

            if (backup.exists()) {
                backup.delete()
                Logger.d(TAG, "Backup file deleted")
            }

            saveJobs[mapId]?.cancel()
            saveJobs.remove(mapId)

            deleted

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete annotations for map $mapId", e)
            false
        }
    }

    /**
     * Vérifie si des annotations existent pour une carte
     */
    fun hasAnnotations(mapId: String): Boolean {
        val file = getAnnotationFile(mapId)
        return file.exists()
    }

    /**
     * Récupère le chemin du fichier d'annotations
     */
    private fun getAnnotationFile(mapId: String): File {
        val dir = File(context.filesDir, ANNOTATIONS_DIR)
        return File(dir, "annotations_$mapId$FILE_EXTENSION")
    }

    /**
     * Nettoie les ressources
     */
    fun cleanup() {
        Logger.entry(TAG, "cleanup")
        saveJobs.values.forEach { it.cancel() }
        saveJobs.clear()
        Logger.i(TAG, "AnnotationRepository cleaned up")
    }

    // ========== SERIALIZERS GSON ==========

    /**
     * Serializer pour PointF
     */
    private class PointFSerializer : JsonSerializer<PointF> {
        override fun serialize(
            src: PointF?,
            typeOfSrc: Type?,
            context: JsonSerializationContext?
        ): JsonElement {
            return if (src != null) {
                JsonObject().apply {
                    addProperty("x", src.x)
                    addProperty("y", src.y)
                }
            } else {
                JsonNull.INSTANCE
            }
        }
    }

    /**
     * Deserializer pour PointF
     */
    private class PointFDeserializer : JsonDeserializer<PointF> {
        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?
        ): PointF? {
            if (json == null || json.isJsonNull) return null

            val obj = json.asJsonObject
            val x = obj.get("x").asFloat
            val y = obj.get("y").asFloat

            return PointF(x, y)
        }
    }

    /**
     * Serializer pour AnnotationEdit (polymorphic)
     */
    private class AnnotationSerializer : JsonSerializer<AnnotationEdit> {
        override fun serialize(
            src: AnnotationEdit?,
            typeOfSrc: Type?,
            context: JsonSerializationContext?
        ): JsonElement {
            if (src == null) return JsonNull.INSTANCE

            val obj = JsonObject()

            when (src) {
                is AnnotationEdit.Text -> {
                    obj.addProperty("type", "text")
                    obj.addProperty("id", src.id)
                    obj.addProperty("content", src.content)
                    obj.add("position", context?.serialize(src.position))
                    obj.addProperty("fontSize", src.fontSize)
                    obj.add("color", context?.serialize(src.color))
                    obj.addProperty("timestamp", src.timestamp)
                }
                is AnnotationEdit.Drawing -> {
                    obj.addProperty("type", "drawing")
                    obj.addProperty("id", src.id)
                    obj.add("points", context?.serialize(src.points))
                    obj.addProperty("strokeWidth", src.strokeWidth)
                    obj.add("color", context?.serialize(src.color))
                    obj.addProperty("timestamp", src.timestamp)
                }
            }

            return obj
        }
    }

    /**
     * Deserializer pour AnnotationEdit (polymorphic)
     */
    private class AnnotationDeserializer : JsonDeserializer<AnnotationEdit> {
        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?
        ): AnnotationEdit? {
            if (json == null || json.isJsonNull) return null

            val obj = json.asJsonObject
            val type = obj.get("type").asString

            return when (type) {
                "text" -> {
                    AnnotationEdit.Text(
                        id = obj.get("id").asString,
                        content = obj.get("content").asString,
                        position = context?.deserialize(obj.get("position"), PointF::class.java)
                            ?: PointF(0f, 0f),
                        fontSize = obj.get("fontSize").asFloat,
                        color = context?.deserialize(obj.get("color"), AnnotationColor::class.java)
                            ?: AnnotationColor(0, 0),
                        timestamp = obj.get("timestamp").asLong
                    )
                }
                "drawing" -> {
                    val pointsArray = obj.getAsJsonArray("points")
                    val points = mutableListOf<PointF>()
                    pointsArray.forEach { element ->
                        context?.deserialize<PointF>(element, PointF::class.java)?.let {
                            points.add(it)
                        }
                    }

                    AnnotationEdit.Drawing(
                        id = obj.get("id").asString,
                        points = points,
                        strokeWidth = obj.get("strokeWidth").asFloat,
                        color = context?.deserialize(obj.get("color"), AnnotationColor::class.java)
                            ?: AnnotationColor(0, 0),
                        timestamp = obj.get("timestamp").asLong
                    )
                }
                else -> null
            }
        }
    }
}