package com.brewdog.catamap.constants

/**
 * Constantes globales de l'application
 */
object AppConstants {
    
    // MAP CONFIGURATION
    object Map {
        const val MAX_SCALE = 2.0f
        const val DEFAULT_ROTATION = 0f
        const val ROTATION_SMOOTHING_BUFFER_SIZE = 3
        const val MAX_ROTATION_DELTA = 10f
        const val MAX_DECODER_THREADS = 4
        const val MIN_DECODER_THREADS = 2
        const val DECODER_THREAD_PRIORITY = Thread.NORM_PRIORITY + 1
    }
    
    // COMPASS CONFIGURATION
    object Compass {
        const val SENSOR_UPDATE_INTERVAL_MS = 50L
        const val MAP_ROTATION_INTERVAL_MS = 20L
        const val SMOOTH_ANGLE_THRESHOLD = 5f
        const val SMOOTH_ALPHA_SMALL = 0.03f
        const val SMOOTH_ALPHA_LARGE = 0.08f
    }
    
    // MINIMAP CONFIGURATION
    object Minimap {
        const val SCREEN_PERCENT = 0.50f  // 40% de la largeur écran
        const val UPDATE_THROTTLE_MS = 66L  // ~15fps
        const val VIEWPORT_STROKE_WIDTH_DP = 2f
        const val VIEWPORT_CORNER_RADIUS_DP = 0f
        const val VIEWPORT_COLOR = android.graphics.Color.RED
        const val SCALE_PERCENT = 0.15f  // 15% de la taille originale
        const val MIN_SIZE = 200
        const val MAX_SIZE = 1500
        const val QUALITY = 90
        const val MIN_VIEW_SIZE_DP = 120
    }
    
    // IMAGE PROCESSING
    object Image {
        const val SAMPLE_SIZE_FOR_DETECTION = 512
        const val LUMINOSITY_THRESHOLD = 0.5
        const val DARK_PIXEL_THRESHOLD = 0.55
        const val SAMPLE_POINTS_COUNT = 200
        const val CORNER_SIZE_PERCENT = 0.1
        const val CORNER_SAMPLE_STEP = 5
    }
    
    // STORAGE
    object Storage {
        const val PREFS_NAME = "map_database"
        const val KEY_DATABASE = "database_json"
    }
    
    // LOADING
    object Loading {
        const val MAX_ADJUST_RETRIES = 20
        const val LOADER_FADE_DURATION_MS = 100L
        const val MAP_READY_DELAY_MS = 10L
        const val MAP_CENTER_DELAY_MS = 50L
    }
    
    // UI
    object UI {
        const val BATTERY_SAVER_SENSOR_INTERVAL_MS = 100L
    }
    
    // INTENTS
    object Intent {
        const val EXTRA_SELECTED_MAP_ID = "selected_map_id"
    }
}
