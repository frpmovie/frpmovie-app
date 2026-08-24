package com.frpmovie.app

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.SeekBar
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
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
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

    // 100 = volumen normal. VLC soporta amplificar por software hasta 200
    // (igual que el propio VLC de escritorio); ExoPlayer no, así que en vivo
    // se limita a 100.
    private var volumePercent = 100

    private val autoHideHandler = Handler(Looper.getMainLooper())

    private fun hideOverlayNow() {
        binding.tvTitle.visibility = View.GONE
        binding.controlsRow.visibility = View.GONE
        binding.vlcControls.visibility = View.GONE
        binding.scrimTop.visibility = View.GONE
        binding.scrimBottom.visibility = View.GONE
        binding.playerView.hideController()
        autoHideHandler.removeCallbacks(hideOverlayRunnable)
    }

    private val hideOverlayRunnable = Runnable { hideOverlayNow() }

    // Temporizador propio: el auto-ocultado nativo del controlador de ExoPlayer
    // se reinicia con cada rebuffer (frecuente en IPTV inestable) y a veces
    // nunca llega a ocultarse. Este no depende de eso.
    private fun showOverlay() {
        val wasHidden = binding.tvTitle.visibility != View.VISIBLE
        binding.tvTitle.visibility = View.VISIBLE
        binding.controlsRow.visibility = View.VISIBLE
        binding.vlcControls.visibility = if (usingVlc) View.VISIBLE else View.GONE
        binding.scrimTop.visibility = View.VISIBLE
        binding.scrimBottom.visibility = View.VISIBLE
        binding.playerView.showController()
        autoHideHandler.removeCallbacks(hideOverlayRunnable)
        autoHideHandler.postDelayed(hideOverlayRunnable, 4000)
        // En TV/control remoto, el primer control visible debe tener el foco de
        // una vez; si no, el usuario necesita adivinar hacia dónde mover el d-pad.
        if (wasHidden && usingVlc) {
            binding.btnPlayPause.requestFocus()
        }
    }

    private fun toggleOverlay() {
        if (binding.tvTitle.visibility == View.VISIBLE) hideOverlayNow() else showOverlay()
    }

    // Con control remoto (Android TV / Fire TV) no hay toques: el primer paso
    // de cualquier tecla de dirección debe revelar los controles en vez de
    // perderse en botones invisibles.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && binding.tvTitle.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MENU -> {
                    showOverlay()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Pequeño zoom al enfocar con d-pad, igual que en las grillas de canales/episodios.
    private fun applyTvFocusEffect(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.scaleX = if (hasFocus) 1.12f else 1f
            v.scaleY = if (hasFocus) 1.12f else 1f
        }
    }

    // --- Gestos estilo Netflix: toque = mostrar/ocultar controles,
    // arrastrar en la mitad izquierda = brillo, mitad derecha = volumen. ---
    private lateinit var gestureDetector: GestureDetector
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var adjustingBrightness = false
    private var adjustingVolume = false
    private var dragStartBrightness = 0f
    private var dragStartVolume = 100

    private val gestureIndicatorHideRunnable = Runnable { binding.gestureIndicator.visibility = View.GONE }

    private fun showGestureIndicator(icon: String, value: String) {
        binding.tvGestureIcon.text = icon
        binding.tvGestureValue.text = value
        binding.gestureIndicator.visibility = View.VISIBLE
        autoHideHandler.removeCallbacks(gestureIndicatorHideRunnable)
        autoHideHandler.postDelayed(gestureIndicatorHideRunnable, 700)
    }

    private fun maxVolumePercent() = if (usingVlc) 200 else 100

    private fun applyVolume() {
        if (usingVlc) {
            vlcPlayer?.setVolume(volumePercent)
        } else {
            player?.volume = (volumePercent / 100f).coerceIn(0f, 1f)
        }
    }

    private fun setVolumePercent(v: Int) {
        volumePercent = v.coerceIn(0, maxVolumePercent())
        applyVolume()
        showGestureIndicator(if (volumePercent == 0) "🔇" else "🔊", "$volumePercent%")
    }

    private fun currentBrightness(): Float {
        val attrBrightness = window.attributes.screenBrightness
        if (attrBrightness in 0f..1f) return attrBrightness
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun setBrightness(v: Float) {
        val clamped = v.coerceIn(0.02f, 1f)
        val lp = window.attributes
        lp.screenBrightness = clamped
        window.attributes = lp
        showGestureIndicator("🔆", "${(clamped * 100).toInt()}%")
    }

    private fun handleGestureTouch(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartY = event.y
                adjustingBrightness = false
                adjustingVolume = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = dragStartY - event.y
                val dx = event.x - dragStartX
                if (!adjustingBrightness && !adjustingVolume && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    if (dragStartX < view.width / 2f) {
                        adjustingBrightness = true
                        dragStartBrightness = currentBrightness()
                    } else {
                        adjustingVolume = true
                        dragStartVolume = volumePercent
                    }
                    dragStartY = event.y
                }
                if (adjustingBrightness) {
                    val delta = (dragStartY - event.y) / view.height
                    setBrightness(dragStartBrightness + delta)
                } else if (adjustingVolume) {
                    val delta = ((dragStartY - event.y) / view.height) * 200
                    setVolumePercent((dragStartVolume + delta).toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                adjustingBrightness = false
                adjustingVolume = false
            }
        }
    }

    // VLC no trae su propia barra de progreso como PlayerView; se actualiza a mano.
    private val positionHandler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            updateVlcProgress()
            positionHandler.postDelayed(this, 500)
        }
    }

    private fun updateVlcProgress() {
        val vp = vlcPlayer ?: return
        val length = vp.length
        if (length > 0) {
            binding.seekBar.progress = ((vp.time * 1000) / length).toInt()
            binding.tvDuration.text = formatTime(length)
        }
        binding.tvPosition.text = formatTime(vp.time)
        binding.btnPlayPause.text = if (vp.isPlaying) "⏸" else "▶"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
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

        // El propio PlayerView ya no recibe toques directos (los intercepta
        // gestureLayer para poder distinguir toque de arrastre), así que el
        // mostrar/ocultar se maneja todo desde acá.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleOverlay()
                return true
            }
        })
        binding.gestureLayer.setOnTouchListener { v, event ->
            handleGestureTouch(v, event)
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.btnAspect.setOnClickListener { cycleAspectRatio() }
        binding.btnAudio.setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        binding.btnSubtitles.setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        binding.btnRetry.setOnClickListener { restart() }
        for (btn in listOf(binding.btnAspect, binding.btnAudio, binding.btnSubtitles, binding.btnPlayPause, binding.btnRetry)) {
            applyTvFocusEffect(btn)
        }

        binding.btnPlayPause.setOnClickListener {
            val vp = vlcPlayer ?: return@setOnClickListener
            if (vp.isPlaying) vp.pause() else vp.play()
            showOverlay()
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // fromUser cubre tanto arrastrar con el dedo como mover con las
                // flechas del control remoto (que no dispara start/stopTrackingTouch).
                if (!fromUser) return
                val vp = vlcPlayer
                val length = vp?.length ?: 0
                if (vp != null && length > 0) {
                    vp.time = (length * progress) / 1000
                }
                showOverlay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                positionHandler.removeCallbacks(positionRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                positionHandler.post(positionRunnable)
            }
        })

        showOverlay()
    }

    private fun startPlayback() {
        // Siempre se intenta primero con ExoPlayer: arranca mucho más rápido
        // que VLC y la gran mayoría del contenido (audio AAC/MP3) funciona
        // perfecto. Si el audio del archivo no tiene decodificador disponible
        // en el dispositivo (común en VOD con AC-3/E-AC-3, que ExoPlayer no
        // soporta sin la extensión FFmpeg) o si truena un error, se cae a VLC
        // automáticamente — ver onTracksChanged/onPlayerError en initPlayer().
        initPlayer()
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
        applyVolume()

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!usingVlc) {
                    switchToVlc()
                } else {
                    showError()
                }
            }

            // A veces ExoPlayer no truena un error: si el dispositivo no tiene
            // decodificador para el audio del archivo (típico con AC-3/E-AC-3),
            // sigue reproduciendo el video mudo en vez de fallar. Se detecta acá
            // apenas se conocen las pistas y se pasa a VLC antes de que se note.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (usingVlc) return
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audioGroups.isNotEmpty() && audioGroups.none { it.isSupported }) {
                    switchToVlc()
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
            val engine = VlcEngine.get(this)
            vlcPlayer = MediaPlayer(engine)
            vlcPlayer?.attachViews(binding.vlcLayout, null, false, false)
            applyVlcScale()
            applyVolume()

            vlcPlayer?.setEventListener { event ->
                if (event.type == MediaPlayer.Event.EncounteredError) {
                    runOnUiThread { showError() }
                }
            }

            val media = Media(engine, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            vlcPlayer?.media = media
            media.release()
            vlcPlayer?.play()
            binding.vlcControls.visibility = View.VISIBLE
            positionHandler.removeCallbacks(positionRunnable)
            positionHandler.post(positionRunnable)
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
        positionHandler.removeCallbacks(positionRunnable)
        player?.release()
        player = null
        usingVlc = false

        // stop()/release() del MediaPlayer son llamadas nativas que pueden
        // bloquear un momento (sobre todo si el stream estaba reconectando por
        // red), lo que congelaba la app al salir de un canal. detachViews() sí
        // debe ir en el hilo principal (toca Views); el resto se libera en
        // segundo plano. El motor LibVLC (VlcEngine) NO se libera acá: es
        // compartido para toda la app y se reutiliza en la siguiente reproducción.
        val vlcToRelease = vlcPlayer
        vlcPlayer = null
        if (vlcToRelease != null) {
            vlcToRelease.detachViews()
            Thread {
                vlcToRelease.stop()
                vlcToRelease.release()
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
