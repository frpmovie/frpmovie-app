package com.frpmovie.app

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.frpmovie.app.databinding.ActivityPlayerBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private var url: String = ""
    private var type: String = "live"
    private var usingVlc = false

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private var resizeModeIndex = 0

    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable {
        binding.tvTitle.visibility = View.GONE
        binding.controlsRow.visibility = View.GONE
        binding.playerView.hideController()
    }

    // Temporizador propio: el auto-ocultado nativo del controlador de ExoPlayer
    // se reinicia con cada rebuffer (frecuente en IPTV inestable) y a veces
    // nunca llega a ocultarse. Este no depende de eso.
    private fun showOverlay() {
        binding.tvTitle.visibility = View.VISIBLE
        binding.controlsRow.visibility = View.VISIBLE
        autoHideHandler.removeCallbacks(hideOverlayRunnable)
        autoHideHandler.postDelayed(hideOverlayRunnable, 4000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.playerView.keepScreenOn = true
        binding.vlcLayout.keepScreenOn = true
        binding.root.keepScreenOn = true

        url = intent.getStringExtra("url") ?: ""
        type = intent.getStringExtra("type") ?: "live"
        val name = intent.getStringExtra("name") ?: "Reproduciendo"
        binding.tvTitle.text = name

        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) showOverlay()
            }
        )
        binding.vlcLayout.setOnClickListener { showOverlay() }

        binding.btnAspect.setOnClickListener { cycleAspectRatio() }
        binding.btnAudio.setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        binding.btnSubtitles.setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        binding.btnRetry.setOnClickListener { restart() }

        showOverlay()
    }

    private fun startPlayback() {
        if (type == "live") {
            // TV en vivo: ExoPlayer arranca más rápido y el audio (AAC/MP2) es compatible.
            initPlayer()
        } else {
            // Películas/series suelen traer audio AC-3/E-AC-3, que ExoPlayer no
            // decodifica sin la extensión FFmpeg (no incluida). VLC sí la soporta,
            // así que para VOD se usa directo en vez de esperar a que ExoPlayer falle.
            switchToVlc()
        }
    }

    private fun initPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Android) ExoPlayer FRPMovie")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000, 25000, 1500, 3000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        binding.playerView.player = player
        binding.playerView.resizeMode = resizeModes[resizeModeIndex]

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!usingVlc) {
                    switchToVlc()
                } else {
                    showError()
                }
            }
        })

        // La URL de canales en vivo es un stream MPEG-TS crudo, no una lista HLS:
        // forzar el mimetype de HLS aquí rompía la reproducción de esos canales.
        val mimeType = when {
            url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            type == "live" -> MimeTypes.VIDEO_MP2T
            else -> null
        }
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(mimeType)
            .build()

        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun switchToVlc() {
        usingVlc = true
        player?.release()
        player = null
        binding.playerView.visibility = View.GONE
        binding.vlcLayout.visibility = View.VISIBLE

        try {
            val options = arrayListOf(
                "--network-caching=2000",
                "--http-reconnect",
                "--no-drop-late-frames",
                "--no-skip-frames",
                "--file-caching=2000"
            )
            libVlc = LibVLC(this, options)
            vlcPlayer = MediaPlayer(libVlc)
            vlcPlayer?.attachViews(binding.vlcLayout, null, false, false)
            applyVlcScale()

            vlcPlayer?.setEventListener { event ->
                if (event.type == MediaPlayer.Event.EncounteredError) {
                    runOnUiThread { showError() }
                }
            }

            val media = Media(libVlc, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            vlcPlayer?.media = media
            media.release()
            vlcPlayer?.play()
        } catch (e: Exception) {
            showError()
        }
    }

    private fun cycleAspectRatio() {
        resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
        val label = when (resizeModes[resizeModeIndex]) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Estirar"
            else -> "Ajustar"
        }
        if (usingVlc) {
            applyVlcScale()
        } else {
            binding.playerView.resizeMode = resizeModes[resizeModeIndex]
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun applyVlcScale() {
        // LibVLC no tiene un modo de recorte tipo "zoom"; se usa ajustar/estirar.
        vlcPlayer?.videoScale = if (resizeModeIndex == 0) {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT
        } else {
            MediaPlayer.ScaleType.SURFACE_FILL
        }
    }

    private fun showTrackDialog(trackType: Int) {
        if (usingVlc) {
            showVlcTrackDialog(trackType)
        } else {
            showExoTrackDialog(trackType)
        }
    }

    private fun showVlcTrackDialog(trackType: Int) {
        val vp = vlcPlayer ?: return
        val isAudio = trackType == C.TRACK_TYPE_AUDIO
        val tracks = if (isAudio) vp.audioTracks else vp.spuTracks
        if (tracks == null || tracks.isEmpty()) {
            Toast.makeText(this, "No hay pistas disponibles", Toast.LENGTH_SHORT).show()
            return
        }
        val currentId = if (isAudio) vp.audioTrack else vp.spuTrack
        val labels = tracks.map { it.name }.toTypedArray()
        val selectedIndex = tracks.indexOfFirst { it.id == currentId }.let { if (it == -1) 0 else it }

        AlertDialog.Builder(this)
            .setTitle(if (isAudio) "Audio" else "Subtítulos")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                if (isAudio) vp.setAudioTrack(tracks[which].id) else vp.setSpuTrack(tracks[which].id)
                dialog.dismiss()
            }
            .show()
    }

    private fun showExoTrackDialog(trackType: Int) {
        val p = player
        if (p == null) {
            Toast.makeText(this, "No disponible con este reproductor", Toast.LENGTH_SHORT).show()
            return
        }
        val groups = p.currentTracks.groups.filter { it.type == trackType && it.isSupported }
        if (groups.isEmpty()) {
            Toast.makeText(this, "No hay pistas disponibles", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = mutableListOf<String>()
        val entries = mutableListOf<Pair<androidx.media3.common.TrackGroup, Int>>()
        var selectedIndex = -1

        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add("Desactivados")
            entries.add(Pair(groups[0].mediaTrackGroup, -1))
            selectedIndex = 0
        }

        for (group in groups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label ?: format.language ?: "Pista ${entries.size + 1}"
                labels.add(label)
                entries.add(Pair(group.mediaTrackGroup, i))
                if (group.isTrackSelected(i)) selectedIndex = entries.size - 1
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Subtítulos")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                val (trackGroup, index) = entries[which]
                val builder = p.trackSelectionParameters.buildUpon()
                if (index == -1) {
                    builder.setTrackTypeDisabled(trackType, true)
                } else {
                    builder.setTrackTypeDisabled(trackType, false)
                    builder.setOverrideForType(TrackSelectionOverride(trackGroup, index))
                }
                p.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }
            .show()
    }

    private fun showError() {
        binding.errorOverlay.visibility = View.VISIBLE
    }

    private fun restart() {
        binding.errorOverlay.visibility = View.GONE
        releasePlayers()
        binding.playerView.visibility = View.VISIBLE
        binding.vlcLayout.visibility = View.GONE
        startPlayback()
    }

    private fun releasePlayers() {
        player?.release()
        player = null
        usingVlc = false

        // stop()/release() de VLC son llamadas nativas que pueden bloquear un
        // momento (sobre todo si el stream estaba reconectando por red), lo que
        // congelaba la app al salir de un canal. detachViews() sí debe ir en el
        // hilo principal (toca Views); el resto se libera en segundo plano.
        val vlcToRelease = vlcPlayer
        val libVlcToRelease = libVlc
        vlcPlayer = null
        libVlc = null
        if (vlcToRelease != null) {
            vlcToRelease.detachViews()
            Thread {
                vlcToRelease.stop()
                vlcToRelease.release()
                libVlcToRelease?.release()
            }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!usingVlc) startPlayback()
    }

    override fun onStop() {
        super.onStop()
        autoHideHandler.removeCallbacksAndMessages(null)
        releasePlayers()
    }
}
