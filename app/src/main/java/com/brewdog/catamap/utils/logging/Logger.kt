package com.brewdog.catamap.utils.logging

import android.util.Log

/**
 * Logger centralisé pour toute l'application
 * Permet de contrôler facilement le niveau de logs et d'ajouter des préfixes
 */
object Logger {
    
    private const val APP_TAG = "CataMap"
    private var isDebugEnabled = true
    private var isVerboseEnabled = true
    
    enum class Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }
    
    /**
     * Active/désactive les logs debug
     */
    fun setDebugEnabled(enabled: Boolean) {
        isDebugEnabled = enabled
        i("Logger", "Debug logs ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Active/désactive les logs verbose
     */
    fun setVerboseEnabled(enabled: Boolean) {
        isVerboseEnabled = enabled
        i("Logger", "Verbose logs ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Log VERBOSE - Détails très fins (état, positions, etc.)
     */
    fun v(tag: String, message: String, throwable: Throwable? = null) {
        if (!isVerboseEnabled) return
        val fullTag = "$APP_TAG:$tag"
        if (throwable != null) {
            Log.v(fullTag, message, throwable)
        } else {
            Log.v(fullTag, message)
        }
    }
    
    /**
     * Log DEBUG - Informations de debug (méthodes appelées, valeurs)
     */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (!isDebugEnabled) return
        val fullTag = "$APP_TAG:$tag"
        if (throwable != null) {
            Log.d(fullTag, message, throwable)
        } else {
            Log.d(fullTag, message)
        }
    }
    
    /**
     * Log INFO - Informations importantes (changements d'état)
     */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        val fullTag = "$APP_TAG:$tag"
        if (throwable != null) {
            Log.i(fullTag, message, throwable)
        } else {
            Log.i(fullTag, message)
        }
    }
    
    /**
     * Log WARNING - Situations anormales mais gérables
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val fullTag = "$APP_TAG:$tag"
        if (throwable != null) {
            Log.w(fullTag, message, throwable)
        } else {
            Log.w(fullTag, message)
        }
    }
    
    /**
     * Log ERROR - Erreurs critiques
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullTag = "$APP_TAG:$tag"
        if (throwable != null) {
            Log.e(fullTag, message, throwable)
        } else {
            Log.e(fullTag, message)
        }
    }
    
    /**
     * Log l'entrée dans une méthode
     */
    fun entry(tag: String, methodName: String, vararg params: Any?) {
        if (!isDebugEnabled) return
        val paramsStr = params.joinToString(", ") { 
            when (it) {
                null -> "null"
                is String -> "\"$it\""
                else -> it.toString()
            }
        }
        d(tag, "→ $methodName($paramsStr)")
    }
    
    /**
     * Log la sortie d'une méthode
     */
    fun exit(tag: String, methodName: String, result: Any? = null) {
        if (!isDebugEnabled) return
        val resultStr = when (result) {
            null -> ""
            Unit -> ""
            else -> " = $result"
        }
        d(tag, "← $methodName$resultStr")
    }
    
    /**
     * Log une métrique de performance
     */
    fun perf(tag: String, operation: String, durationMs: Long) {
        i(tag, "$operation took ${durationMs}ms")
    }
    
    /**
     * Log l'état d'un objet
     */
    fun state(tag: String, objectName: String, state: Map<String, Any?>) {
        if (!isVerboseEnabled) return
        val stateStr = state.entries.joinToString(", ") { "${it.key}=${it.value}" }
        v(tag, "$objectName: { $stateStr }")
    }
}
