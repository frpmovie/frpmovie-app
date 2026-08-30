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
                    // 800ms daba el arranque más rápido posible, pero dejaba muy
                    // poco colchón: en streams con calidad adaptativa, el corte al
                    // cambiar de variante (nueva descarga a mitad de reproducción)
                    // se notaba como un microcorte. Con el motor ya precalentado
                    // (VlcEngine) el arranque sigue siendo rápido aun con más buffer.
                    "--network-caching=1300",
                    "--file-caching=1300",
                    "--http-reconnect",
                    "--no-drop-late-frames",
                    "--no-skip-frames",
                    // Comprime el rango dinámico del audio: evita que canales con
                    // pistas 5.1/E-AC3 mezcladas a estéreo (downmix) suenen
                    // "saturados"/distorsionados en los picos, sin afectar audible-
                    // mente a los canales que ya vienen en estéreo normal.
                    "--audio-filter=compressor"
                )
            ).also { instance = it }
        }
    }
}
