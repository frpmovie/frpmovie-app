package com.frpmovie.app

object Config {
    // Servidor FRPMovie
    const val SERVER = "https://stream.frpmovie.com"

    // Endpoints Xtream Codes
    fun playerApi(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass"

    // Canales en vivo
    fun liveStreams(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_live_streams"
    fun liveCategories(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_live_categories"

    // Películas (VOD)
    fun vodStreams(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_vod_streams"
    fun vodCategories(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_vod_categories"

    // Series
    fun seriesStreams(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_series"
    fun seriesCategories(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_series_categories"
    // Detalle de una serie: temporadas y episodios
    fun seriesInfo(user: String, pass: String, seriesId: Int) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId"

    // URL de reproducción de un canal en vivo (directo, sin proxy web)
    fun liveUrl(user: String, pass: String, streamId: Int) =
        "$SERVER/live/$user/$pass/$streamId.ts"

    // URL de reproducción de una película
    fun movieUrl(user: String, pass: String, streamId: Int) =
        "$SERVER/movie/$user/$pass/$streamId.mp4"

    // URL de reproducción de un episodio de una serie
    fun seriesUrl(user: String, pass: String, episodeId: Int, ext: String) =
        "$SERVER/series/$user/$pass/$episodeId.${ext.ifBlank { "mp4" }}"
}
