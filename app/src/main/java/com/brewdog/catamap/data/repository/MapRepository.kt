package com.brewdog.catamap.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.data.models.MapDatabase
import com.brewdog.catamap.data.models.MapItem
import com.brewdog.catamap.utils.logging.Logger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Repository pour la gestion du stockage persistant des cartes et catégories
 * Implémente le pattern Repository pour séparer la logique de stockage
 */
class MapRepository(context: Context) {

    companion object {
        private const val TAG = "MapRepository"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        AppConstants.Storage.PREFS_NAME, 
        Context.MODE_PRIVATE
    )
    
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriSerializer())
        .registerTypeAdapter(Uri::class.java, UriDeserializer())
        .setPrettyPrinting()
        .create()

    init {
        Logger.i(TAG, "MapRepository initialized")
    }

    /**
     * Charge la base de données depuis le stockage
     */
    fun loadDatabase(): MapDatabase {
        Logger.entry(TAG, "loadDatabase")
        val startTime = System.currentTimeMillis()
        
        val json = prefs.getString(AppConstants.Storage.KEY_DATABASE, null)
        
        val database = if (json != null) {
            Logger.d(TAG, "Found existing database JSON (${json.length} chars)")
            try {
                val db = gson.fromJson(json, MapDatabase::class.java)
                Logger.i(TAG, "Database loaded: ${db.maps.size} maps, ${db.categories.size} categories")
                db
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to parse database JSON, creating new database", e)
                MapDatabase()
            }
        } else {
            Logger.i(TAG, "No existing database found, creating default database")
            createDefaultDatabase()
        }
        
        val duration = System.currentTimeMillis() - startTime
        Logger.perf(TAG, "loadDatabase", duration)
        Logger.exit(TAG, "loadDatabase", "MapDatabase with ${database.maps.size} maps")
        
        return database
    }

    /**
     * Crée la base de données avec la carte d'exemple au premier lancement
     */
    private fun createDefaultDatabase(): MapDatabase {
        Logger.entry(TAG, "createDefaultDatabase")

        val database = MapDatabase()

        // Sauvegarder immédiatement
        saveDatabase(database)

        Logger.i(TAG, "Default database created with example map")
        Logger.exit(TAG, "createDefaultDatabase")

        return database
    }

    /**
     * Sauvegarde la base de données
     */
    fun saveDatabase(database: MapDatabase): Boolean {
        Logger.entry(TAG, "saveDatabase", "maps=${database.maps.size}")
        val startTime = System.currentTimeMillis()
        
        return try {
            val json = gson.toJson(database)
            Logger.d(TAG, "Database serialized to JSON (${json.length} chars)")
            
            prefs.edit {
                putString(AppConstants.Storage.KEY_DATABASE, json)
            }
            
            val duration = System.currentTimeMillis() - startTime
            Logger.perf(TAG, "saveDatabase", duration)
            Logger.i(TAG, "Database saved successfully")
            Logger.exit(TAG, "saveDatabase", true)
            true
            
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save database", e)
            Logger.exit(TAG, "saveDatabase", false)
            false
        }
    }

    /**
     * Efface toutes les données (pour reset)
     */
    fun clearAll(): Boolean {
        Logger.entry(TAG, "clearAll")
        return try {
            prefs.edit {
                clear()
            }
            Logger.w(TAG, "All data cleared")
            Logger.exit(TAG, "clearAll", true)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to clear data", e)
            Logger.exit(TAG, "clearAll", false)
            false
        }
    }

    /**
     * Exporte la database en JSON (pour backup)
     */
    fun exportToJson(database: MapDatabase): String {
        Logger.entry(TAG, "exportToJson")
        val json = gson.toJson(database)
        Logger.i(TAG, "Database exported to JSON (${json.length} chars)")
        Logger.exit(TAG, "exportToJson")
        return json
    }

    /**
     * Importe une database depuis JSON (pour restore)
     */
    fun importFromJson(json: String): MapDatabase? {
        Logger.entry(TAG, "importFromJson", "json.length=${json.length}")
        return try {
            val database = gson.fromJson(json, MapDatabase::class.java)
            Logger.i(TAG, "Database imported: ${database.maps.size} maps, ${database.categories.size} categories")
            Logger.exit(TAG, "importFromJson", "success")
            database
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import database from JSON", e)
            Logger.exit(TAG, "importFromJson", "null")
            null
        }
    }

    /**
     * Vérifie si une database existe
     */
    fun hasSavedDatabase(): Boolean {
        val exists = prefs.contains(AppConstants.Storage.KEY_DATABASE)
        Logger.d(TAG, "Database exists: $exists")
        return exists
    }

    /**
     * Serializer pour Uri (conversion en String)
     */
    private class UriSerializer : JsonSerializer<Uri> {
        override fun serialize(
            src: Uri?,
            typeOfSrc: Type?,
            context: JsonSerializationContext?
        ): JsonElement {
            return JsonPrimitive(src?.toString() ?: "")
        }
    }

    /**
     * Deserializer pour Uri (conversion depuis String)
     */
    private class UriDeserializer : JsonDeserializer<Uri> {
        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?
        ): Uri? {
            val uriString = json?.asString
            return if (uriString.isNullOrEmpty()) null else uriString.toUri()
        }
    }
}
