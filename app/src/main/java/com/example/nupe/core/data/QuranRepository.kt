package com.example.nupe.core.data

import javax.inject.Inject
import javax.inject.Singleton

data class Verse(
    val arabic: String,
    val info: String, // Translation
    val reference: String
)

@Singleton
class QuranRepository @Inject constructor() {

    private val verses = listOf(
        Verse(
            arabic = "أَلَمْ يَعْلَمْ بِأَنَّ اللَّهَ يَرَىٰ",
            info = "Does he not know that Allah sees?",
            reference = "Surah Al-Alaq 96:14"
        ),
        Verse(
            arabic = "وَاعْلَمُوا أَنَّ اللَّهَ يَعْلَمُ مَا فِي أَنفُسِكُمْ فَاحْذَرُوهُ",
            info = "And know that Allah knows what is in your minds, so fear Him.",
            reference = "Surah Al-Baqarah 2:235"
        ),
        Verse(
            arabic = "قُل لِّلْمُؤْمِنِينَ يَغُضُّوا مِنْ أَبْصَارِهِمْ وَيَحْفَظُوا فُرُوجَهُمْ ۚ ذَٰلِكَ أَزْكَىٰ لَهُمْ",
            info = "Tell the believing men to reduce [some] of their vision and guard their private parts. That is purer for them.",
            reference = "Surah An-Nur 24:30"
        ),
        Verse(
            arabic = "إِنَّ السَّمْعَ وَالْبَصَرَ وَالْفُؤَادَ كُلُّ أُولَٰئِكَ كَانَ عَنْهُ مَسْئُولًا",
            info = "Indeed, the hearing, the sight and the heart - about all those [one] will be questioned.",
            reference = "Surah Al-Isra 17:36"
        ),
        Verse(
            arabic = "يَا أَيُّهَا الَّذِينَ آمَنُوا تُوبُوا إِلَى اللَّهِ تَوْبَةً نَّصُوحًا",
            info = "O you who have believed, repent to Allah with sincere repentance.",
            reference = "Surah At-Tahrim 66:8"
        )
    )

    fun getRandomVerse(): Verse {
        return verses.random()
    }
}
