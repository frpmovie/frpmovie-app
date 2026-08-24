package com.frpmovie.app

import android.content.Context
import org.videolan.libvlc.LibVLC

// Arrancar el motor de LibVLC (cargar las librerías nativas) es lo más lento
// de abrir un video, mucho más que crear el MediaPlayer en sí. Antes se creaba
// (y destruía) un LibVLC nuevo cada vez que se abría una película o episodio;
// esto mantiene una sola instancia para toda la vida de la app, así que solo
// el primer video paga ese arranque.
object VlcEngine {
    @Volatile
    private var instance: LibVLC? = null

    fun get(context: Context): LibVLC {
        return instance ?: synchronized(this) {
            instance ?: LibVLC(
                context.applicationContext,
                arrayListOf(
                    "--network-caching=800",
                    "--http-reconnect",
                    "--no-drop-late-frames",
                    "--no-skip-frames",
                    "--file-caching=800"
                )
            ).also { instance = it }
        }
    }
}
