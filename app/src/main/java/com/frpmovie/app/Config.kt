package com.frpmovie.app

object Config {
    // Servidor FRPMovie
    const val SERVER = "https://stream.frpmovie.com"

    // Endpoints Xtream Codes
    fun playerApi(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass"

    fun liveStreams(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_live_streams"

    fun liveCategories(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_live_categories"

    fun vodStreams(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_vod_streams"

    fun vodCategories(user: String, pass: String) =
        "$SERVER/player_api.php?username=$user&password=$pass&action=get_vod_categories"

    // URL de reproducción de un canal en vivo
    fun liveUrl(user: String, pass: String, streamId: Int) =
        "$SERVER/live/$user/$pass/$streamId.ts?web=1"

    // URL de reproducción de una película
    fun movieUrl(user: String, pass: String, streamId: Int) =
        "$SERVER/movie/$user/$pass/$streamId.mp4"
}
