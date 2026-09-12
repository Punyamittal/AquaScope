package com.smriti.brain.database

enum class MemoryTaxonomy(val wire: String) {
    TOOLS("Tools"),
    HEALTH("Health"),
    PLACES("Places"),
    LOCATION("Location"),
    MONEY("Money"),
    PEOPLE("People"),
    ACCESS("Access");

    companion object {
        fun fromWire(value: String): MemoryTaxonomy =
            entries.firstOrNull { it.wire.equals(value, true) || it.name.equals(value, true) }
                ?: TOOLS
    }
}
