package com.ridedeck.app.data

/** What a dashboard card slot can show. The speed card is always there; two slots hold the rest. */
enum class DashCard { NAV, MEDIA, APPS, NONE }

object DashLayout {
    val DEFAULT = listOf(DashCard.NAV, DashCard.MEDIA)

    /** Two names; one that is unknown (an old "WIDGET" setting, say) leaves that slot empty. */
    fun fromString(s: String?): List<DashCard> {
        val parts = s?.split(',')?.map { it.trim() }.orEmpty()
        if (parts.size != 2) return DEFAULT
        return normalize(parts.map { name -> DashCard.entries.firstOrNull { it.name == name } ?: DashCard.NONE })
    }

    fun toString(slots: List<DashCard>): String = slots.joinToString(",") { it.name }

    /** A card can only be in one slot: the later slot gives way. */
    fun normalize(slots: List<DashCard>): List<DashCard> =
        slots.mapIndexed { i, c -> if (c != DashCard.NONE && slots.take(i).contains(c)) DashCard.NONE else c }

}
