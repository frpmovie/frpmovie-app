package com.frpmovie.app

// Parser de listas M3U/M3U8 extendidas (#EXTM3U), el formato "lista externa"
// más común entre proveedores IPTV como alternativa a Xtream Codes.
object M3uParser {
    data class Entry(
        val name: String,
        val logo: String,
        val group: String,
        val url: String
    )

    private val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")
    private val groupRegex = Regex("group-title=\"([^\"]*)\"")

    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var pendingName: String? = null
        var pendingLogo = ""
        var pendingGroup = "General"

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
                    pendingLogo = logoRegex.find(line)?.groupValues?.get(1) ?: ""
                    pendingGroup = groupRegex.find(line)?.groupValues?.get(1)?.ifBlank { "General" } ?: "General"
                    pendingName = line.substringAfterLast(",").trim().ifBlank { "Sin nombre" }
                }
                line.startsWith("#") -> {
                    // Otras etiquetas (#EXTM3U, #EXTGRP, #EXTVLCOPT, etc.) se ignoran.
                }
                else -> {
                    val name = pendingName
                    if (name != null) {
                        entries.add(Entry(name, pendingLogo, pendingGroup, line))
                        pendingName = null
                        pendingLogo = ""
                        pendingGroup = "General"
                    }
                }
            }
        }
        return entries
    }
}
