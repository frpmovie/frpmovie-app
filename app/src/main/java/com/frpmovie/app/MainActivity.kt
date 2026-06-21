package com.frpmovie.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.frpmovie.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()
    private lateinit var user: String
    private lateinit var pass: String
    private val allItems = mutableListOf<Channel>()
    private lateinit var adapter: ChannelAdapter
    private var currentTab = "live"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        user = intent.getStringExtra("user") ?: ""
        pass = intent.getStringExtra("pass") ?: ""

        adapter = ChannelAdapter(allItems) { item ->
            val url = when (currentTab) {
                "movies" -> Config.movieUrl(user, pass, item.streamId)
                "series" -> Config.seriesUrl(user, pass, item.streamId)
                else -> Config.liveUrl(user, pass, item.streamId)
            }
            val i = Intent(this, PlayerActivity::class.java)
            i.putExtra("url", url)
            i.putExtra("name", item.name)
            startActivity(i)
        }

        binding.recyclerChannels.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerChannels.adapter = adapter

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.btnLive.setOnClickListener { switchTab("live") }
        binding.btnMovies.setOnClickListener { switchTab("movies") }
        binding.btnSeries.setOnClickListener { switchTab("series") }

        switchTab("live")
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        binding.etSearch.setText("")
        // Resaltar el boton activo
        val brand = androidx.core.content.ContextCompat.getColor(this, R.color.brand)
        val transparent = android.graphics.Color.TRANSPARENT
        binding.btnLive.setBackgroundColor(if (tab == "live") brand else transparent)
        binding.btnMovies.setBackgroundColor(if (tab == "movies") brand else transparent)
        binding.btnSeries.setBackgroundColor(if (tab == "series") brand else transparent)
        loadContent()
    }

    private fun filter(query: String) {
        val filtered = if (query.isEmpty()) allItems
        else allItems.filter { it.name.contains(query, ignoreCase = true) }
        adapter.update(filtered)
    }

    private fun loadContent() {
        binding.progress.visibility = View.VISIBLE
        binding.tvCount.text = "Cargando..."
        val urlStr = when (currentTab) {
            "movies" -> Config.vodStreams(user, pass)
            "series" -> Config.seriesStreams(user, pass)
            else -> Config.liveStreams(user, pass)
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(urlStr).build()
                val body = client.newCall(req).execute().body?.string() ?: "[]"
                val arr = JSONArray(body)
                val list = mutableListOf<Channel>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    // series usa series_id, el resto usa stream_id
                    val id = if (currentTab == "series") o.optInt("series_id") else o.optInt("stream_id")
                    val logo = if (currentTab == "live") o.optString("stream_icon") else o.optString("cover", o.optString("stream_icon"))
                    list.add(
                        Channel(
                            streamId = id,
                            name = o.optString("name"),
                            logo = logo,
                            category = o.optString("category_id")
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    allItems.clear()
                    allItems.addAll(list)
                    adapter.update(allItems)
                    binding.progress.visibility = View.GONE
                    val tipo = when (currentTab) {
                        "movies" -> "películas"
                        "series" -> "series"
                        else -> "canales"
                    }
                    binding.tvCount.text = "${allItems.size} $tipo"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progress.visibility = View.GONE
                    binding.tvCount.text = "Error al cargar"
                }
            }
        }
    }
}

data class Channel(
    val streamId: Int,
    val name: String,
    val logo: String,
    val category: String
)
