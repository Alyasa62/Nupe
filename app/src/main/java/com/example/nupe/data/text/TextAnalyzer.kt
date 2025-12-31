package com.example.nupe.data.text

import com.example.nupe.core.CoreConstants
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextAnalyzer @Inject constructor() {

    fun analyze(text: String): Boolean {
        // Enhance with Aho-Corasick or Bloom Filter later
        val lowerText = text.lowercase()
        for (keyword in CoreConstants.BAD_KEYWORDS) {
            if (lowerText.contains(keyword)) {
                return true
            }
        }
        return false
    }
}
