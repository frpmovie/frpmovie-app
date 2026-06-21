package com.frpmovie.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        url = intent.getStringExtra("url") ?: ""
        type = intent.getStringExtra("type") ?: "live"
        val name = intent.getStringExtra("name") ?: "Reproduciendo"
        binding.tvTitle.text = name
    }

    private fun initPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Android) ExoPlayer FRPMovie")

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        binding.playerView.player = player

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Si ExoPlayer falla, cambiar a libVLC
                if (!usingVlc) {
                    switchToVlc()
                }
            }
        })

        val mediaItem = if (type == "live") {
            MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        } else {
            MediaItem.fromUri(Uri.parse(url))
        }

        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun switchToVlc() {
        usingVlc = true
        // Liberar ExoPlayer
        player?.release()
        player = null
        binding.playerView.visibility = View.GONE
        binding.vlcLayout.visibility = View.VISIBLE

        Toast.makeText(this, "Cambiando a motor VLC...", Toast.LENGTH_SHORT).show()

        try {
            val options = arrayListOf(
                "--network-caching=1500",
                "--http-reconnect",
                "--no-drop-late-frames",
                "--no-skip-frames"
            )
            libVlc = LibVLC(this, options)
            vlcPlayer = MediaPlayer(libVlc)
            vlcPlayer?.attachViews(binding.vlcLayout, null, false, false)

            val media = Media(libVlc, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            vlcPlayer?.media = media
            media.release()
            vlcPlayer?.play()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo reproducir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!usingVlc) initPlayer()
    }

    override fun onStop() {
        super.onStop()
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
}
