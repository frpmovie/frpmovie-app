package com.frpmovie.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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
    private lateinit var catAdapter: CategoryAdapter
    private val categories = mutableListOf<Category>()
    private var currentTab = "live"
    private var currentCat = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        user = intent.getStringExtra("user") ?: ""
        pass = intent.getStringExtra("pass") ?: ""

        // Arrancar el motor de VLC (lo más lento de abrir una película/serie)
        // en segundo plano desde ya, mientras el usuario navega el catálogo,
        // para que ya esté listo cuando toque reproducir algo.
        Thread { VlcEngine.get(applicationContext) }.start()

        adapter = ChannelAdapter(allItems) { item ->
            if (currentTab == "series") {
                val i = Intent(this, SeriesDetailActivity::class.java)
                i.putExtra("user", user)
                i.putExtra("pass", pass)
                i.putExtra("seriesId", item.streamId)
                i.putExtra("name", item.name)
                i.putExtra("cover", item.logo)
                startActivity(i)
            } else {
                val url = when (currentTab) {
                    "movies" -> Config.movieUrl(user, pass, item.streamId)
                    else -> Config.liveUrl(user, pass, item.streamId)
                }
                val i = Intent(this, PlayerActivity::class.java)
                i.putExtra("url", url)
                i.putExtra("name", item.name)
                i.putExtra("type", currentTab)
                startActivity(i)
            }
        }

        binding.recyclerChannels.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerChannels.adapter = adapter

        catAdapter = CategoryAdapter(categories) { cat ->
            currentCat = cat.id
            binding.drawerLayout.closeDrawers()
            applyFilter()
        }
        binding.recyclerCategories.layoutManager = LinearLayoutManager(this)
        binding.recyclerCategories.adapter = catAdapter

        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }

        binding.btnSearchIcon.setOnClickListener {
            if (binding.etSearch.visibility == View.VISIBLE) {
                binding.etSearch.visibility = View.GONE
                binding.etSearch.setText("")
            } else {
                binding.etSearch.visibility = View.VISIBLE
                binding.etSearch.requestFocus()
            }
        }

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { applyFilter() }
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
        currentCat = "all"
        binding.etSearch.setText("")
        binding.etSearch.visibility = View.GONE
        val brand = androidx.core.content.ContextCompat.getColor(this, R.color.brand)
        val ink = androidx.core.content.ContextCompat.getColor(this, R.color.ink)
        val muted = androidx.core.content.ContextCompat.getColor(this, R.color.muted)
        for ((btn, id) in listOf(binding.btnLive to "live", binding.btnMovies to "movies", binding.btnSeries to "series")) {
            val active = id == tab
            btn.backgroundTintList = if (active) android.content.res.ColorStateList.valueOf(brand) else null
            btn.setTextColor(if (active) ink else muted)
        }
        loadContent()
    }

    private fun applyFilter() {
        val query = binding.etSearch.text.toString()
        var filtered = if (currentCat == "all") allItems
            else allItems.filter { it.category == currentCat }
        if (query.isNotEmpty()) {
            filtered = filtered.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.update(filtered)
        binding.tvCount.text = "${filtered.size} de ${allItems.size}"
    }

    private fun loadContent() {
        binding.progress.visibility = View.VISIBLE
        binding.tvCount.text = "Cargando..."
        val streamsUrl = when (currentTab) {
            "movies" -> Config.vodStreams(user, pass)
            "series" -> Config.seriesStreams(user, pass)
            else -> Config.liveStreams(user, pass)
        }
        val catsUrl = when (currentTab) {
            "movies" -> Config.vodCategories(user, pass)
            "series" -> Config.seriesCategories(user, pass)
            else -> Config.liveCategories(user, pass)
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(streamsUrl).build()
                val body = client.newCall(req).execute().body?.string() ?: "[]"
                val arr = JSONArray(body)
                val list = mutableListOf<Channel>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
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

                val reqCat = Request.Builder().url(catsUrl).build()
                val bodyCat = client.newCall(reqCat).execute().body?.string() ?: "[]"
                val arrCat = JSONArray(bodyCat)
                val catList = mutableListOf<Category>()
                catList.add(Category("all", "📋 Todas", list.size))
                for (i in 0 until arrCat.length()) {
                    val o = arrCat.getJSONObject(i)
                    val catId = o.optString("category_id")
                    val catName = o.optString("category_name")
                    val count = list.count { it.category == catId }
                    if (count > 0) {
                        catList.add(Category(catId, catName, count))
                    }
                }

                withContext(Dispatchers.Main) {
                    allItems.clear()
                    allItems.addAll(list)
                    catAdapter.update(catList)
                    catAdapter.setSelected("all")
                    currentCat = "all"
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
