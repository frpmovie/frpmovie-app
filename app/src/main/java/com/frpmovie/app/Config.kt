package com.frpmovie.app

object Config {
    // Normaliza la URL del servidor que escribe el usuario (sin barra final,
    // con esquema por defecto si no lo puso).
    fun normalizeServer(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        return s
    }

    // Endpoints Xtream Codes
    fun playerApi(server: String, user: String, pass: String) =
        "$server/player_api.php?username=$user&password=$pass"

    // Canales en vivo
    fun liveStreams(server: String, user: String, pass: String) =
        "$server/player_api.php?username=$user&password=$pass&action=get_live_streams"
    fun liveCategories(server: String, user: String, pass: String) =
        "$server/player_api.php?username=$user&password=$pass&action=get_live_categories"

    // Películas (VOD)
    fun vodStreams(server: String, user: String, pass: String) =
        "$server/player_api.php?username=$user&password=$pass&action=get_vod_streams"
    fun vodCategories(server: String, user: String, pass: String) =
        "$server/player_api.php?username=$user&password=$pass&action=get_vod_categories"

    // Series
    fun seriesStreams(server: String, user: String, pass: String) =
        "$server/player_api.php?username=$user&password=$pass&action=get_series"
    fun seriesCategories(server: String, user: String, pass: String) =
        "$server/player_api.php?username=$user&password=$pass&action=get_series_categories"
    // Detalle de una serie: temporadas y episodios
    fun seriesInfo(server: String, user: String, pass: String, seriesId: Int) =
        "$server/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId"

    // URL de reproducción de un canal en vivo (directo, sin proxy web)
    fun liveUrl(server: String, user: String, pass: String, streamId: Int) =
        "$server/live/$user/$pass/$streamId.ts"

    // URL de reproducción de una película
    fun movieUrl(server: String, user: String, pass: String, streamId: Int) =
        "$server/movie/$user/$pass/$streamId.mp4"

    // URL de reproducción de un episodio de una serie
    fun seriesUrl(server: String, user: String, pass: String, episodeId: Int, ext: String) =
        "$server/series/$user/$pass/$episodeId.${ext.ifBlank { "mp4" }}"
}
