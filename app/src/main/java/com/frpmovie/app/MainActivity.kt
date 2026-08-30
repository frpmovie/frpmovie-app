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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()
    private var mode = "xtream"
    private var server = ""
    private var user = ""
    private var pass = ""
    private var m3uUrl = ""
    private val allItems = mutableListOf<Channel>()
    private lateinit var adapter: ChannelAdapter
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private val categories = mutableListOf<Category>()
    private var currentTab = "live"
    private var currentCat = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra("mode") ?: "xtream"
        if (mode == "m3u") {
            m3uUrl = intent.getStringExtra("m3uUrl") ?: ""
        } else {
            server = intent.getStringExtra("server") ?: ""
            user = intent.getStringExtra("user") ?: ""
            pass = intent.getStringExtra("pass") ?: ""
        }

        // Arrancar el motor de VLC (lo más lento de abrir una película/serie)
        // en segundo plano desde ya, mientras el usuario navega el catálogo,
        // para que ya esté listo cuando toque reproducir algo.
        Thread { VlcEngine.get(applicationContext) }.start()

        adapter = ChannelAdapter(allItems) { item ->
            if (mode == "m3u") {
                val directUrl = item.directUrl
                if (directUrl != null) {
                    val i = Intent(this, PlayerActivity::class.java)
                    i.putExtra("url", directUrl)
                    i.putExtra("name", item.name)
                    i.putExtra("type", "live")
                    startActivity(i)
                }
            } else if (currentTab == "series") {
                val i = Intent(this, SeriesDetailActivity::class.java)
                i.putExtra("server", server)
                i.putExtra("user", user)
                i.putExtra("pass", pass)
                i.putExtra("seriesId", item.streamId)
                i.putExtra("name", item.name)
                i.putExtra("cover", item.logo)
                startActivity(i)
            } else {
                val url = when (currentTab) {
                    "movies" -> Config.movieUrl(server, user, pass, item.streamId)
                    else -> Config.liveUrl(server, user, pass, item.streamId)
                }
                val i = Intent(this, PlayerActivity::class.java)
                i.putExtra("url", url)
                i.putExtra("name", item.name)
                i.putExtra("type", currentTab)
                startActivity(i)
            }
        }

        gridLayoutManager = GridLayoutManager(this, 3)
        binding.recyclerChannels.layoutManager = gridLayoutManager
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

        binding.btnLogout.setOnClickListener {
            getSharedPreferences("frp", MODE_PRIVATE).edit().clear().apply()
            val i = Intent(this, LoginActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(i)
            finish()
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

        if (mode == "m3u") {
            // Una lista M3U no tiene la separación live/películas/series de
            // Xtream Codes: todo es un único catálogo con categorías propias.
            binding.tabRow.visibility = View.GONE
            adapter.setContentType("live")
            gridLayoutManager.spanCount = 3
            loadContent()
        } else {
            binding.tabRow.visibility = View.VISIBLE
            switchTab("live")
        }
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
        // Los logos de canales son chicos y caben bien en 3 columnas; las
        // carátulas de películas/series se ven mejor más grandes, con menos
        // columnas.
        adapter.setContentType(tab)
        gridLayoutManager.spanCount = if (tab == "live") 3 else 2
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
        if (mode == "m3u") {
            loadM3uContent()
            return
        }

        val streamsUrl = when (currentTab) {
            "movies" -> Config.vodStreams(server, user, pass)
            "series" -> Config.seriesStreams(server, user, pass)
            else -> Config.liveStreams(server, user, pass)
        }
        val catsUrl = when (currentTab) {
            "movies" -> Config.vodCategories(server, user, pass)
            "series" -> Config.seriesCategories(server, user, pass)
            else -> Config.liveCategories(server, user, pass)
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Los streams y las categorías son dos pedidos independientes:
                // lanzarlos en paralelo (en vez de uno detrás del otro) reduce
                // a la mitad el tiempo de espera al abrir cada pestaña.
                val streamsDeferred = async {
                    val req = Request.Builder().url(streamsUrl).build()
                    client.newCall(req).execute().body?.string() ?: "[]"
                }
                val catsDeferred = async {
                    val reqCat = Request.Builder().url(catsUrl).build()
                    client.newCall(reqCat).execute().body?.string() ?: "[]"
                }
                val body = streamsDeferred.await()
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

                val bodyCat = catsDeferred.await()
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

    private fun loadM3uContent() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(m3uUrl).build()
                val body = client.newCall(req).execute().body?.string() ?: ""
                val entries = M3uParser.parse(body)
                val list = entries.mapIndexed { index, e ->
                    Channel(streamId = index, name = e.name, logo = e.logo, category = e.group, directUrl = e.url)
                }
                val catList = mutableListOf<Category>()
                catList.add(Category("all", "📋 Todas", list.size))
                for (group in list.map { it.category }.distinct().sorted()) {
                    catList.add(Category(group, group, list.count { it.category == group }))
                }

                withContext(Dispatchers.Main) {
                    allItems.clear()
                    allItems.addAll(list)
                    catAdapter.update(catList)
                    catAdapter.setSelected("all")
                    currentCat = "all"
                    adapter.update(allItems)
                    binding.progress.visibility = View.GONE
                    binding.tvCount.text = "${allItems.size} canales"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progress.visibility = View.GONE
                    binding.tvCount.text = "Error al cargar la lista"
                }
            }
        }
    }
}

data class Channel(
    val streamId: Int,
    val name: String,
    val logo: String,
    val category: String,
    val directUrl: String? = null
)
