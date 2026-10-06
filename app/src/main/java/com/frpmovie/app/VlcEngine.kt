package com.frpmovie.app

import android.content.Context
import org.videolan.libvlc.LibVLC

// Arrancar el motor de LibVLC (cargar las librerías nativas) es lo más lento
// de abrir un video, mucho más que crear el MediaPlayer en sí. Esto mantiene
// una sola instancia para toda la vida de la app, así que solo el primer
// video paga ese arranque.
object VlcEngine {
    @Volatile
    private var instance: LibVLC? = null

    fun get(context: Context): LibVLC {
        return instance ?: synchronized(this) {
            instance ?: LibVLC(
                context.applicationContext,
                arrayListOf(
                    // Valor base; PlayerActivity lo ajusta por medio (en vivo vs.
                    // película/serie) con la opción :network-caching. VLC es el
                    // motor de respaldo, así que prioriza no cortarse.
                    "--network-caching=3000",
                    "--http-reconnect",
                    // Si un cuadro llega tarde, VLC lo descarta y sigue sincronizado
                    // (igual que la app oficial de VLC). Forzar a mostrar todos los
                    // cuadros hacía que el video se fuera atrasando y luego
                    // "saltara" para resincronizarse: el microcorte con retroceso.
                    "--audio-time-stretch",
                    // Comprime el rango dinámico del audio: evita que canales con
                    // pistas 5.1/E-AC3 mezcladas a estéreo suenen saturados.
                    "--audio-filter=compressor"
                )
            ).also { instance = it }
        }
    }
}
