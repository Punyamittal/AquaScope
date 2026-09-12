package com.smriti.brain.database

object TaxonomyRouter {
    private val rules = listOf(
        MemoryTaxonomy.HEALTH to listOf(
            "medicine", "medication", "tablet", "pill", "dose", "mg", "bp", "glucose",
            "insulin", "fall", "heart", "ambulance", "smoke", "alarm"
        ),
        MemoryTaxonomy.MONEY to listOf("rs", "inr", "paid", "upi", "bank", "invoice", "₹"),
        MemoryTaxonomy.PEOPLE to listOf("call", "mom", "dad", "family", "friend", "name"),
        MemoryTaxonomy.ACCESS to listOf("otp", "password", "pin", "login", "unlock", "key"),
        MemoryTaxonomy.LOCATION to listOf("lat", "gps", "map", "arrived", "left"),
        MemoryTaxonomy.PLACES to listOf("home", "office", "hospital", "airport", "station"),
        MemoryTaxonomy.TOOLS to listOf("screenshot", "ocr", "clip", "game", "kill", "ir", "ac")
    )

    fun route(text: String): MemoryTaxonomy {
        val t = text.lowercase()
        for ((tax, keys) in rules) {
            if (keys.any { t.contains(it) }) return tax
        }
        return MemoryTaxonomy.TOOLS
    }
}
