package com.frpmovie.app

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
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
                binding.tvTitle.visibility = visibility
                binding.controlsRow.visibility = visibility
            }
        )

        binding.btnAspect.setOnClickListener { cycleAspectRatio() }
        binding.btnAudio.setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        binding.btnSubtitles.setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        binding.btnRetry.setOnClickListener { restart() }
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
        val p = player
        if (p == null || usingVlc) {
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
        initPlayer()
    }

    private fun releasePlayers() {
        player?.release()
        player = null
        vlcPlayer?.stop()
        vlcPlayer?.detachViews()
        vlcPlayer?.release()
        vlcPlayer = null
        libVlc?.release()
        libVlc = null
        usingVlc = false
    }

    override fun onStart() {
        super.onStart()
        if (!usingVlc) initPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayers()
    }
}
