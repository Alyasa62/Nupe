package com.example.nupe.core

object CoreConstants {
    val SAFE_PACKAGES = setOf(
        "com.android.settings",
        "com.google.android.calculator",
        "com.android.systemui",
        "com.google.android.deskclock",
        "com.google.android.calendar",
        "com.android.vending", // Play Store (usually safe-ish for browsing apps)
        "com.google.android.apps.messaging",
        "com.google.android.dialer",
        "com.example.nupe"
    )

    const val SCROLL_DEBOUNCE_MS = 500L
    const val MODEL_FILENAME = "mobilenet_v1_1.0_224_quant.tflite" // Placeholder name
    const val LABEL_FILENAME = "labels.txt" // Placeholder
    
    val BAD_KEYWORDS = setOf(
        "nsfw", "porn", "xxx", "explicit", "adult"
    )

    val NUCLEAR_KEYWORDS = listOf(
        // English
        "porn", "xxx", "nude", "sex", "hentai", "boobs", "dick", "pussy", "milf",
        // Roman Urdu
        "nanga", "nangi", "gandi video", "sexy", "bhabi", "chudai", "lund", "phudi", "kuti", "randi"
    )
}
