package com.frpmovie.app

// Comparte con PlayerActivity la lista de "qué más se puede ver desde acá"
// (los demás canales en vivo, o los capítulos de la serie actual) sin pasarla
// por el Intent: una lista de canales IPTV puede tener miles de elementos y
// superaría fácil el límite de tamaño de una transacción de Binder si fuera
// un extra. Al vivir en memoria del proceso, MainActivity/SeriesDetailActivity
// la dejan lista justo antes de abrir PlayerActivity.
object PlayerPlaylist {
    data class Item(val id: Int, val name: String, val url: String, val logo: String = "")

    var label: String = ""
    var items: List<Item> = emptyList()

    fun clear() {
        label = ""
        items = emptyList()
    }
}
