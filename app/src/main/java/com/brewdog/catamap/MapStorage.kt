package com.brewdog.catamap

import android.content.Context
import android.net.Uri
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
 * Gestion du stockage persistant des cartes et catégories
 */
class MapStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("map_database", Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriSerializer())
        .registerTypeAdapter(Uri::class.java, UriDeserializer())
        .setPrettyPrinting()
        .create()

    companion object {
        private const val KEY_DATABASE = "database_json"
    }

    /**
     * Charge la base de données depuis le stockage
     */
    fun load(): MapDatabase {
        val json = prefs.getString(KEY_DATABASE, null)
        val database = if (json != null) {
            try {
                gson.fromJson(json, MapDatabase::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                MapDatabase() // Retourne une base vide en cas d'erreur
            }
        } else {
            // Premier lancement : créer la carte d'exemple
            createDefaultDatabase()
        }
        return database
    }

    /**
     * Crée la base de données avec la carte d'exemple au premier lancement
     */
    private fun createDefaultDatabase(): MapDatabase {
        val database = MapDatabase()

        // Créer la carte d'exemple avec les images embarquées
        val exampleMap = MapItem(
            id = "example_map_fdp_2019",
            name = "Carte d'exemple FDP 2019",
            categoryId = Category.UNCATEGORIZED_ID,
            lightImageUri = null, // Les images drawable n'ont pas d'URI
            darkImageUri = null,
            dateAdded = System.currentTimeMillis(),
            isDefault = true,
            hasLightMode = true,
            hasDarkMode = true,
            isBuiltIn = true // Carte embarquée
        )

        database.addOrUpdateMap(exampleMap)
        save(database) // Sauvegarder immédiatement

        return database
    }

    /**
     * Sauvegarde la base de données
     */
    fun save(database: MapDatabase): Boolean {
        return try {
            val json = gson.toJson(database)
            prefs.edit().putString(KEY_DATABASE, json).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Efface toutes les données (pour debug/reset)
     */
    fun clear() {
        prefs.edit().clear().apply()
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
            return if (uriString.isNullOrEmpty()) null else Uri.parse(uriString)
        }
    }
}