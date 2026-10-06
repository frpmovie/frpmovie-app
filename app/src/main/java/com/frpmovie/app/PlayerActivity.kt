package com.frpmovie.app

import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.frpmovie.app.databinding.ActivityPlayerBinding
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs
import kotlin.math.max

class PlayerActivity : AppCompatActivity() {

    companion object {
        // Sin avance del reloj durante este tiempo (y sin pausa del usuario)
        // se considera que el stream quedó colgado y se reconecta solo.
        private const val STALL_TIMEOUT_MS = 15000L
        private const val MAX_RECONNECTS = 4
        // Si la última reconexión fue hace más que esto, la reproducción venía
        // sana y el contador de intentos vuelve a cero.
        private const val HEALTHY_PLAYBACK_MS = 30000L
        // VLC ahora es solo el respaldo: se le da un colchón amplio para que,
        // cuando entra, priorice no cortarse.
        private const val LIVE_CACHING_MS = 3000
        private const val VOD_CACHING_MS = 5000
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) FRPMovie/3.3"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var vlcPlayer: MediaPlayer? = null
    private var url: String = ""
    private var type: String = "live"
    private var usingVlc = false
    // Solo se permite un salto de motor (VLC -> Exo) por intento; si el
    // respaldo también falla, se reconecta (si ya venía andando) o se muestra
    // el error (si nunca llegó a arrancar).
    private var fallbackAttempted = false
    // Si ExoPlayer no pudo con este contenido y VLC sí, las reconexiones van
    // directo a VLC en vez de volver a probar ExoPlayer cada vez.
    private var preferVlc = false
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var shownPlayingIcon: Boolean? = null

    // --- Estado de salud de la reproducción ---
    private var userPaused = false
    private var everPlayed = false
    private var lastKnownPositionMs = 0L
    private var lastKnownDurationMs = 0L
    private var resumePositionMs = 0L
    private var lastObservedTime = -1L
    private var lastProgressAt = 0L
    private var reconnectAttempts = 0
    private var lastReconnectAt = 0L

    // Un error de VLC puede ser un tropiezo de red del que --http-reconnect se
    // recupera solo; se da un margen corto antes de cambiar de motor.
    private val errorRecoveryHandler = Handler(Looper.getMainLooper())
    private var errorRecoveryPending = false
    private val retryPlayRunnable = Runnable {
        errorRecoveryPending = false
        val vp = vlcPlayer
        if (usingVlc && vp != null && !vp.isPlaying) {
            vp.play()
        }
        errorRecoveryHandler.postDelayed(errorEscalateRunnable, 800)
    }
    private val errorEscalateRunnable = Runnable {
        val vp = vlcPlayer
        if (usingVlc && (vp == null || !vp.isPlaying)) {
            failOrFallback()
        }
    }

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private var resizeModeIndex = 0

    // 100 = volumen normal. VLC soporta amplificar por software hasta 200;
    // ExoPlayer no, así que con él se limita a 100.
    private var volumePercent = 100

    private val autoHideHandler = Handler(Looper.getMainLooper())
    private var overlayVisible = false

    private var playlistOpen = false
    private lateinit var playlistBackCallback: OnBackPressedCallback

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Overlay (título + botones)
    // ------------------------------------------------------------------

    private fun hideOverlayNow() {
        overlayVisible = false
        binding.titleBar.visibility = View.GONE
        binding.controlsRow.visibility = View.GONE
        binding.playbackControls.visibility = View.GONE
        binding.scrimTop.visibility = View.GONE
        binding.scrimBottom.visibility = View.GONE
        autoHideHandler.removeCallbacks(hideOverlayRunnable)
    }

    private val hideOverlayRunnable = Runnable { hideOverlayNow() }

    private fun showOverlay() {
        if (playlistOpen) return
        val wasHidden = !overlayVisible
        overlayVisible = true
        binding.titleBar.visibility = View.VISIBLE
        binding.controlsRow.visibility = View.VISIBLE
        binding.playbackControls.visibility = View.VISIBLE
        binding.scrimTop.visibility = View.VISIBLE
        binding.scrimBottom.visibility = View.VISIBLE
        autoHideHandler.removeCallbacks(hideOverlayRunnable)
        autoHideHandler.postDelayed(hideOverlayRunnable, 4500)
        // En TV/control remoto, el primer control visible debe tener el foco.
        if (wasHidden) {
            binding.btnPlayPause.requestFocus()
        }
    }

    private fun toggleOverlay() {
        if (overlayVisible) hideOverlayNow() else showOverlay()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Teclas de control remoto que funcionan siempre, se vea o no el overlay.
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekRelative(10000)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekRelative(-10000)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    playAdjacent(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    playAdjacent(-1)
                    return true
                }
            }
            // Con el panel de canales abierto, el d-pad navega la lista.
            if (!playlistOpen && !overlayVisible) {
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
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyTvFocusEffect(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.scaleX = if (hasFocus) 1.12f else 1f
            v.scaleY = if (hasFocus) 1.12f else 1f
        }
    }

    // ------------------------------------------------------------------
    // Gestos: toque = mostrar/ocultar, arrastre izq. = brillo, der. = volumen
    // ------------------------------------------------------------------

    private lateinit var gestureDetector: GestureDetector
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var adjustingBrightness = false
    private var adjustingVolume = false
    private var dragStartBrightness = 0f
    private var dragStartVolume = 100

    private val gestureIndicatorHideRunnable = Runnable { binding.gestureIndicator.visibility = View.GONE }

    private fun showGestureIndicator(iconRes: Int, value: String) {
        binding.ivGestureIcon.setImageResource(iconRes)
        binding.tvGestureValue.text = value
        binding.gestureIndicator.visibility = View.VISIBLE
        autoHideHandler.removeCallbacks(gestureIndicatorHideRunnable)
        autoHideHandler.postDelayed(gestureIndicatorHideRunnable, 700)
    }

    private fun maxVolumePercent() = 200

    // VLC amplifica por software de forma nativa hasta 200. Con ExoPlayer, lo
    // que pasa de 100 se amplifica con el LoudnessEnhancer del sistema sobre
    // la sesión de audio del reproductor.
    private fun applyVolume() {
        if (usingVlc) {
            vlcPlayer?.setVolume(volumePercent)
            return
        }
        val p = player ?: return
        p.volume = (volumePercent / 100f).coerceIn(0f, 1f)
        val boost = volumePercent - 100
        try {
            if (boost > 0) {
                val enhancer = loudnessEnhancer ?: LoudnessEnhancer(p.audioSessionId).also { loudnessEnhancer = it }
                enhancer.setTargetGain(boost * 8)
                enhancer.setEnabled(true)
            } else {
                loudnessEnhancer?.setEnabled(false)
            }
        } catch (e: Exception) {
            // Algunos equipos no ofrecen el efecto; el volumen queda en 100%.
        }
    }

    private fun setVolumePercent(v: Int) {
        volumePercent = v.coerceIn(0, maxVolumePercent())
        applyVolume()
        showGestureIndicator(if (volumePercent == 0) R.drawable.ic_volume_off else R.drawable.ic_volume, "$volumePercent%")
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
        showGestureIndicator(R.drawable.ic_brightness, "${(clamped * 100).toInt()}%")
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

    // ------------------------------------------------------------------
    // Progreso + vigilancia de la reproducción (cada 500ms)
    // ------------------------------------------------------------------

    private val positionHandler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            // Se agenda primero: si checkPlaybackHealth() reconecta, el
            // releasePlayers() de la reconexión cancela este tick y queda un
            // solo ciclo corriendo (el que arranca el nuevo reproductor).
            positionHandler.postDelayed(this, 500)
            updatePlaybackProgress()
            checkPlaybackHealth()
        }
    }

    private fun updatePlaybackProgress() {
        val posMs: Long
        val durMs: Long
        val playing: Boolean
        if (usingVlc) {
            val vp = vlcPlayer ?: return
            posMs = vp.time
            durMs = vp.length
            playing = vp.isPlaying
        } else {
            val p = player ?: return
            posMs = p.currentPosition
            durMs = p.duration
            // playWhenReady y no isPlaying: mientras recarga no debe parpadear
            // el ícono a "play" como si estuviera pausado.
            playing = p.playWhenReady
        }
        if (posMs > 0) lastKnownPositionMs = posMs
        if (durMs > 0) {
            lastKnownDurationMs = durMs
            binding.seekBar.progress = ((posMs * 1000) / durMs).toInt()
            binding.tvDuration.text = formatTime(durMs)
        } else {
            binding.tvDuration.text = if (type == "live") "EN VIVO" else "--:--"
        }
        binding.tvPosition.text = formatTime(posMs.coerceAtLeast(0))
        if (shownPlayingIcon != playing) {
            shownPlayingIcon = playing
            binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    // Si el reloj deja de avanzar sin que el usuario haya pausado, el stream
    // quedó colgado (típico cuando el servidor IPTV corta la conexión sin
    // avisar): se reconecta solo en vez de quedarse congelado.
    private fun checkPlaybackHealth() {
        val now = SystemClock.elapsedRealtime()
        val t: Long? = if (usingVlc) vlcPlayer?.time else player?.currentPosition
        if (t == null || userPaused || binding.errorOverlay.visibility == View.VISIBLE) {
            lastProgressAt = now
            if (userPaused) binding.bufferingSpinner.visibility = View.GONE
            return
        }
        if (t != lastObservedTime) {
            if (lastObservedTime > 0 && t > 0) {
                everPlayed = true
                binding.bufferingSpinner.visibility = View.GONE
            }
            lastObservedTime = t
            lastProgressAt = now
            return
        }
        // Solo se vigila una vez que el stream ya arrancó: el arranque inicial
        // lo cubren los propios errores de VLC.
        if (everPlayed && now - lastProgressAt > STALL_TIMEOUT_MS) {
            lastProgressAt = now
            reconnect()
        }
    }

    private fun seekRelative(deltaMs: Long) {
        if (usingVlc) {
            val vp = vlcPlayer ?: return
            val length = vp.length
            val target = vp.time + deltaMs
            vp.time = if (length > 0) target.coerceIn(0, length) else target.coerceAtLeast(0)
        } else {
            val p = player ?: return
            val duration = p.duration
            val target = p.currentPosition + deltaMs
            p.seekTo(if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0))
        }
        lastProgressAt = SystemClock.elapsedRealtime()
        updatePlaybackProgress()
        showOverlay()
    }

    private fun togglePlayPause() {
        if (usingVlc) {
            val vp = vlcPlayer ?: return
            if (vp.isPlaying) {
                vp.pause()
                userPaused = true
            } else {
                vp.play()
                userPaused = false
            }
        } else {
            val p = player ?: return
            if (p.playWhenReady) {
                p.pause()
                userPaused = true
            } else {
                p.play()
                userPaused = false
            }
        }
        lastProgressAt = SystemClock.elapsedRealtime()
        updatePlaybackProgress()
        showOverlay()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

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
        binding.tvTitle.text = intent.getStringExtra("name") ?: "Reproduciendo"
        binding.tvLiveBadge.visibility = if (type == "live") View.VISIBLE else View.GONE

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
        binding.btnRewind.setOnClickListener { seekRelative(-10000) }
        binding.btnForward.setOnClickListener { seekRelative(10000) }
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnPlaylist.setOnClickListener { showPlaylistPanel() }
        binding.btnClosePlaylist.setOnClickListener { hidePlaylistPanel() }
        binding.playlistScrim.setOnClickListener { hidePlaylistPanel() }
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(this)
        // Solo tiene sentido si quien abrió el reproductor (MainActivity para
        // canales, SeriesDetailActivity para episodios) dejó algo en la lista.
        binding.btnPlaylist.visibility = if (PlayerPlaylist.items.isNotEmpty()) View.VISIBLE else View.GONE
        for (btn in listOf(
            binding.btnAspect, binding.btnAudio, binding.btnSubtitles, binding.btnPlayPause,
            binding.btnRetry, binding.btnRewind, binding.btnForward, binding.btnPlaylist
        )) {
            applyTvFocusEffect(btn)
        }

        playlistBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hidePlaylistPanel()
            }
        }
        onBackPressedDispatcher.addCallback(this, playlistBackCallback)

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // fromUser cubre tanto arrastrar con el dedo como mover con las
                // flechas del control remoto (que no dispara start/stopTrackingTouch).
                if (!fromUser) return
                if (usingVlc) {
                    val vp = vlcPlayer
                    val length = vp?.length ?: 0
                    if (vp != null && length > 0) vp.time = (length * progress) / 1000
                } else {
                    val p = player
                    val duration = p?.duration ?: 0
                    if (p != null && duration > 0) p.seekTo((duration * progress) / 1000)
                }
                lastProgressAt = SystemClock.elapsedRealtime()
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

    override fun onStart() {
        super.onStart()
        acquireWifiLock()
        if (vlcPlayer == null && player == null) {
            // Al volver de segundo plano, las películas/series siguen donde
            // iban en vez de empezar de cero; en vivo se retoma el directo.
            startPlayback(if (type == "live") 0L else lastKnownPositionMs)
        }
    }

    override fun onStop() {
        super.onStop()
        autoHideHandler.removeCallbacksAndMessages(null)
        releasePlayers()
        releaseWifiLock()
    }

    // Sin esto, el Wi-Fi del equipo puede entrar en ahorro de energía durante
    // la reproducción y el caudal tiene baches: microcortes que no se ven en
    // apps como TiviMate/Televizo, que sí lo mantienen despierto.
    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "frpmovie:playback").apply { setReferenceCounted(false) }
            }
            wifiLock?.acquire()
        } catch (e: Exception) {
            // Equipos sin Wi-Fi (TV por cable): no es crítico.
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Motores de reproducción
    // ------------------------------------------------------------------

    private fun startPlayback(resumeAt: Long = 0L) {
        // ExoPlayer es el motor principal: es el mismo que usan TiviMate o
        // Televizo y el que mejor aguanta los baches de red (buffer de hasta
        // 50s, reintentos automáticos). Con la extensión FFmpeg decodifica
        // también AC-3/E-AC-3/DTS, que era lo único que antes obligaba a usar
        // VLC. VLC queda de respaldo para lo que ExoPlayer no pueda abrir.
        resumePositionMs = resumeAt
        fallbackAttempted = false
        userPaused = false
        lastObservedTime = -1L
        lastProgressAt = SystemClock.elapsedRealtime()
        if (preferVlc) switchToVlc() else initExoPlayer()
    }

    private fun initExoPlayer() {
        usingVlc = false
        val vp = vlcPlayer
        vlcPlayer = null
        if (vp != null) {
            vp.setEventListener(null as MediaPlayer.EventListener?)
            vp.detachViews()
            Thread { vp.stop(); vp.release() }.start()
        }
        binding.vlcLayout.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.bufferingSpinner.visibility = View.GONE

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(15000)

        // Ajustes de TS pensados para IPTV: arrancar en cualquier keyframe (no
        // solo IDR, que en muchos canales tarda) y detectar cuadros en streams
        // H.264 sin delimitadores; también habilita audio DTS dentro de TS.
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
            )
            .setConstantBitrateSeekingEnabled(true)

        // Un corte de red se reintenta solo (con espera creciente) sin cortar
        // la reproducción, en vez de tirar error al primer tropiezo.
        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        // Buffer amplio: hasta 50s por delante en películas/series; arranca con
        // 2s y, después de un corte, junta 4s de colchón antes de seguir para
        // no volver a cortarse enseguida.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 50000, 2000, 4000)
            .build()

        // FFmpeg entra solo cuando el equipo no tiene decodificador propio para
        // el audio (AC-3, E-AC-3, DTS, TrueHD...).
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        player = p
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        p.audioSessionId = audioManager.generateAudioSessionId()
        binding.playerView.player = p
        binding.playerView.resizeMode = resizeModes[resizeModeIndex]
        applyVolume()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player !== p) return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                // Error de red (códigos 2xxx) en algo que ya venía andando: es
                // un corte del servidor, se reconecta con el mismo motor.
                val networkError = error.errorCode in 2000..2999
                if (everPlayed && networkError) reconnect() else failOrFallback()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) everPlayed = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (player === p && state == Player.STATE_ENDED) onPlaybackEnded()
            }

            // Si aun con FFmpeg no hay cómo decodificar el audio o el video
            // (p. ej. HEVC 10-bit en un equipo sin soporte), pasa a VLC, que
            // decodifica por software.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (player !== p) return
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val audioBroken = audio.isNotEmpty() && audio.none { it.isSupported }
                val videoBroken = video.isNotEmpty() && video.none { it.isSupported }
                if (audioBroken || videoBroken) failOrFallback()
            }
        })

        val mimeType = if (url.contains("m3u8", ignoreCase = true)) MimeTypes.APPLICATION_M3U8 else null
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(mimeType)
            .build()

        if (resumePositionMs > 0) {
            p.setMediaItem(mediaItem, resumePositionMs)
        } else {
            p.setMediaItem(mediaItem)
        }
        p.prepare()
        p.playWhenReady = true
        positionHandler.removeCallbacks(positionRunnable)
        positionHandler.post(positionRunnable)
    }

    private fun switchToVlc() {
        usingVlc = true
        releaseLoudnessEnhancer()
        player?.release()
        player = null
        binding.playerView.visibility = View.GONE
        binding.vlcLayout.visibility = View.VISIBLE
        binding.bufferingSpinner.visibility = View.VISIBLE

        try {
            val engine = VlcEngine.get(this)
            val vp = MediaPlayer(engine)
            vlcPlayer = vp
            vp.attachViews(binding.vlcLayout, null, false, false)
            applyVlcScale()
            applyVolume()

            vp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Buffering -> {
                        val percent = event.buffering
                        runOnUiThread {
                            if (vlcPlayer === vp) {
                                binding.bufferingSpinner.visibility =
                                    if (percent < 100f && !userPaused) View.VISIBLE else View.GONE
                            }
                        }
                    }
                    MediaPlayer.Event.Playing -> runOnUiThread {
                        if (vlcPlayer === vp) binding.bufferingSpinner.visibility = View.GONE
                    }
                    MediaPlayer.Event.EncounteredError -> runOnUiThread {
                        if (vlcPlayer === vp && !errorRecoveryPending) {
                            errorRecoveryPending = true
                            errorRecoveryHandler.postDelayed(retryPlayRunnable, 1000)
                        }
                    }
                    MediaPlayer.Event.EndReached -> runOnUiThread {
                        if (vlcPlayer === vp) onPlaybackEnded()
                    }
                }
            }

            val media = Media(engine, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=" + (if (type == "live") LIVE_CACHING_MS else VOD_CACHING_MS))
            if (resumePositionMs > 0) {
                media.addOption(":start-time=" + (resumePositionMs / 1000.0))
            }
            vp.media = media
            media.release()
            vp.play()
            positionHandler.removeCallbacks(positionRunnable)
            positionHandler.post(positionRunnable)
        } catch (e: Exception) {
            failOrFallback()
        }
    }

    private fun failOrFallback() {
        if (!fallbackAttempted) {
            fallbackAttempted = true
            if (usingVlc) {
                initExoPlayer()
            } else {
                preferVlc = true
                switchToVlc()
            }
            return
        }
        // Los dos motores fallaron. Si ya venía reproduciendo, es un corte de
        // red/servidor: se reconecta. Si nunca arrancó, el contenido no
        // funciona y se avisa.
        if (everPlayed) reconnect() else showError()
    }

    private fun reconnect() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectAt > HEALTHY_PLAYBACK_MS) reconnectAttempts = 0
        if (reconnectAttempts >= MAX_RECONNECTS) {
            releasePlayers()
            showError()
            return
        }
        reconnectAttempts++
        lastReconnectAt = now
        val resumeAt = if (type == "live") 0L else lastKnownPositionMs
        releasePlayers()
        startPlayback(resumeAt)
    }

    // Fin del stream: en vivo nunca "termina", así que es un corte y se
    // reconecta. En películas/series, si faltaba mucho también es un corte;
    // si de verdad terminó, en series se pasa solo al siguiente capítulo.
    private fun onPlaybackEnded() {
        if (isFinishing) return
        val reallyEnded = type != "live" && lastKnownDurationMs > 0 &&
            lastKnownPositionMs >= lastKnownDurationMs - 30000
        if (!reallyEnded) {
            reconnect()
            return
        }
        val next = adjacentPlaylistItem(1)
        if (type == "series" && next != null) {
            Toast.makeText(this, "Siguiente capítulo", Toast.LENGTH_SHORT).show()
            switchTo(next)
        } else {
            finish()
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

    // ------------------------------------------------------------------
    // Pistas de audio / subtítulos
    // ------------------------------------------------------------------

    private fun showTrackDialog(trackType: Int) {
        if (usingVlc) showVlcTrackDialog(trackType) else showExoTrackDialog(trackType)
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

    // ------------------------------------------------------------------
    // Error / reinicio
    // ------------------------------------------------------------------

    private fun showError() {
        binding.bufferingSpinner.visibility = View.GONE
        binding.errorOverlay.visibility = View.VISIBLE
        binding.btnRetry.requestFocus()
    }

    private fun restart() {
        binding.errorOverlay.visibility = View.GONE
        reconnectAttempts = 0
        lastReconnectAt = 0L
        releasePlayers()
        startPlayback(if (type == "live") 0L else lastKnownPositionMs)
    }

    // ------------------------------------------------------------------
    // Panel lateral de canales / capítulos
    // ------------------------------------------------------------------

    private fun showPlaylistPanel() {
        val items = PlayerPlaylist.items
        if (items.isEmpty()) return
        hideOverlayNow()
        playlistOpen = true
        playlistBackCallback.isEnabled = true

        val isChannels = PlayerPlaylist.label == "Canales"
        binding.tvPlaylistTitle.text = PlayerPlaylist.label
        binding.tvPlaylistCount.text = if (isChannels) "${items.size} canales disponibles" else "${items.size} capítulos"
        val currentIndex = items.indexOfFirst { it.url == url }
        binding.recyclerPlaylist.adapter = PlaylistAdapter(items, url, isChannels) { item ->
            hidePlaylistPanel()
            switchTo(item)
        }
        val lm = binding.recyclerPlaylist.layoutManager as LinearLayoutManager
        if (currentIndex >= 0) lm.scrollToPositionWithOffset(currentIndex, dp(120))

        binding.playlistScrim.alpha = 0f
        binding.playlistScrim.visibility = View.VISIBLE
        binding.playlistScrim.animate().alpha(1f).setDuration(220).start()

        val panel = binding.playlistPanel
        panel.visibility = View.VISIBLE
        panel.translationX = (if (panel.width > 0) panel.width else dp(360)).toFloat()
        panel.animate()
            .translationX(0f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // En TV, el foco arranca sobre lo que se está viendo.
        binding.recyclerPlaylist.post {
            lm.findViewByPosition(max(currentIndex, 0))?.requestFocus()
        }
    }

    private fun hidePlaylistPanel() {
        if (!playlistOpen) return
        playlistOpen = false
        playlistBackCallback.isEnabled = false
        val panel = binding.playlistPanel
        panel.animate()
            .translationX(panel.width.toFloat())
            .setDuration(180)
            .withEndAction { panel.visibility = View.GONE }
            .start()
        binding.playlistScrim.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { binding.playlistScrim.visibility = View.GONE }
            .start()
    }

    private fun adjacentPlaylistItem(offset: Int): PlayerPlaylist.Item? {
        val items = PlayerPlaylist.items
        val index = items.indexOfFirst { it.url == url }
        if (index < 0) return null
        return items.getOrNull(index + offset)
    }

    private fun playAdjacent(offset: Int) {
        val item = adjacentPlaylistItem(offset) ?: return
        switchTo(item)
    }

    private fun switchTo(item: PlayerPlaylist.Item) {
        if (item.url == url) return
        url = item.url
        binding.tvTitle.text = item.name
        binding.errorOverlay.visibility = View.GONE
        everPlayed = false
        lastKnownPositionMs = 0L
        lastKnownDurationMs = 0L
        reconnectAttempts = 0
        lastReconnectAt = 0L
        preferVlc = false
        releasePlayers()
        startPlayback(0L)
        showOverlay()
    }

    private fun releaseLoudnessEnhancer() {
        try {
            loudnessEnhancer?.release()
        } catch (e: Exception) {
        }
        loudnessEnhancer = null
    }

    private fun releasePlayers() {
        positionHandler.removeCallbacks(positionRunnable)
        errorRecoveryHandler.removeCallbacks(retryPlayRunnable)
        errorRecoveryHandler.removeCallbacks(errorEscalateRunnable)
        errorRecoveryPending = false
        binding.bufferingSpinner.visibility = View.GONE
        shownPlayingIcon = null
        releaseLoudnessEnhancer()
        player?.release()
        player = null
        usingVlc = false

        // stop()/release() del MediaPlayer son llamadas nativas que pueden
        // bloquear un momento (sobre todo si el stream estaba reconectando),
        // así que van en segundo plano. El motor LibVLC (VlcEngine) NO se
        // libera: es compartido y se reutiliza en la siguiente reproducción.
        val vlcToRelease = vlcPlayer
        vlcPlayer = null
        if (vlcToRelease != null) {
            vlcToRelease.setEventListener(null as MediaPlayer.EventListener?)
            vlcToRelease.detachViews()
            Thread {
                vlcToRelease.stop()
                vlcToRelease.release()
            }.start()
        }
    }
}
