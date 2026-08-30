package com.frpmovie.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.frpmovie.app.databinding.ActivitySeriesDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class SeriesDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySeriesDetailBinding
    private val client = OkHttpClient()
    private lateinit var server: String
    private lateinit var user: String
    private lateinit var pass: String
    private var seriesId: Int = 0
    private lateinit var seriesName: String
    private lateinit var episodeAdapter: EpisodeAdapter

    private val episodesBySeason = linkedMapOf<Int, List<Episode>>()
    private val seasonButtons = mutableMapOf<Int, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        server = intent.getStringExtra("server") ?: ""
        user = intent.getStringExtra("user") ?: ""
        pass = intent.getStringExtra("pass") ?: ""
        seriesId = intent.getIntExtra("seriesId", 0)
        seriesName = intent.getStringExtra("name") ?: "Serie"
        binding.tvSeriesName.text = seriesName

        binding.btnBack.setOnClickListener { finish() }

        episodeAdapter = EpisodeAdapter(emptyList()) { episode ->
            val url = Config.seriesUrl(server, user, pass, episode.id, episode.ext)
            val i = Intent(this, PlayerActivity::class.java)
            i.putExtra("url", url)
            i.putExtra("name", "$seriesName - ${episode.episodeNum}. ${episode.title}")
            i.putExtra("type", "series")
            startActivity(i)
        }
        binding.recyclerEpisodes.layoutManager = LinearLayoutManager(this)
        binding.recyclerEpisodes.adapter = episodeAdapter

        loadSeriesInfo()
    }

    private fun loadSeriesInfo() {
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(Config.seriesInfo(server, user, pass, seriesId)).build()
                val body = client.newCall(req).execute().body?.string() ?: "{}"
                val json = JSONObject(body)

                val info = json.optJSONObject("info")
                val plot = info?.optString("plot") ?: ""
                val cover = info?.optString("cover") ?: ""

                val episodesJson = json.optJSONObject("episodes") ?: JSONObject()
                val parsed = linkedMapOf<Int, List<Episode>>()
                val seasonKeys = episodesJson.keys().asSequence().toList()
                    .mapNotNull { it.toIntOrNull() }
                    .sorted()
                for (seasonNum in seasonKeys) {
                    val arr = episodesJson.optJSONArray(seasonNum.toString()) ?: continue
                    val list = mutableListOf<Episode>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val epInfo = o.optJSONObject("info")
                        list.add(
                            Episode(
                                id = o.optString("id").toIntOrNull() ?: 0,
                                episodeNum = o.optInt("episode_num", i + 1),
                                title = o.optString("title"),
                                ext = o.optString("container_extension", "mp4"),
                                duration = epInfo?.optString("duration") ?: "",
                                plot = epInfo?.optString("plot") ?: "",
                                image = epInfo?.optString("movie_image") ?: cover
                            )
                        )
                    }
                    if (list.isNotEmpty()) parsed[seasonNum] = list.sortedBy { it.episodeNum }
                }

                withContext(Dispatchers.Main) {
                    binding.progress.visibility = View.GONE
                    if (plot.isNotBlank()) {
                        binding.tvSeriesPlot.text = plot
                        binding.tvSeriesPlot.visibility = View.VISIBLE
                    }
                    episodesBySeason.clear()
                    episodesBySeason.putAll(parsed)
                    if (episodesBySeason.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                    } else {
                        buildSeasonTabs()
                        selectSeason(episodesBySeason.keys.first())
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progress.visibility = View.GONE
                    binding.tvEmpty.text = "No se pudo cargar la serie"
                    binding.tvEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun buildSeasonTabs() {
        binding.seasonTabs.removeAllViews()
        seasonButtons.clear()
        for (seasonNum in episodesBySeason.keys) {
            val btn = Button(this).apply {
                text = "Temporada $seasonNum"
                textSize = 13f
                isAllCaps = false
                setPadding(36, 16, 36, 16)
                background = ContextCompat.getDrawable(this@SeriesDetailActivity, R.drawable.chip_bg)
                setTextColor(ContextCompat.getColor(this@SeriesDetailActivity, R.color.muted))
                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = 8
                layoutParams = params
                setOnClickListener { selectSeason(seasonNum) }
            }
            seasonButtons[seasonNum] = btn
            binding.seasonTabs.addView(btn)
        }
    }

    private fun selectSeason(seasonNum: Int) {
        val brand = ContextCompat.getColor(this, R.color.brand)
        val muted = ContextCompat.getColor(this, R.color.muted)
        val ink = ContextCompat.getColor(this, R.color.ink)
        seasonButtons.forEach { (num, btn) ->
            val selected = num == seasonNum
            btn.backgroundTintList = if (selected) android.content.res.ColorStateList.valueOf(brand) else null
            btn.setTextColor(if (selected) ink else muted)
        }
        episodeAdapter.update(episodesBySeason[seasonNum] ?: emptyList())
    }
}
