package com.frpmovie.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.frpmovie.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private class Catalog(val items: List<Channel>, val categories: List<Category>, val hero: Channel?)

    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    private var mode = "xtream"
    private var server = ""
    private var user = ""
    private var pass = ""
    private var m3uUrl = ""

    private val allItems = mutableListOf<Channel>()
    private val categories = mutableListOf<Category>()
    private var heroItem: Channel? = null
    // Cada pestaña se descarga una sola vez por sesión: volver a ella es instantáneo.
    private val catalogCache = mutableMapOf<String, Catalog>()

    private lateinit var gridAdapter: ChannelAdapter
    private lateinit var rowAdapter: RowAdapter
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
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

        // Arrancar el motor de VLC en segundo plano mientras se navega el
        // catálogo, para que ya esté listo cuando toque reproducir algo.
        Thread { VlcEngine.get(applicationContext) }.start()

        gridAdapter = ChannelAdapter(emptyList()) { openItem(it) }
        gridLayoutManager = GridLayoutManager(this, 3)
        binding.recyclerChannels.layoutManager = gridLayoutManager
        binding.recyclerChannels.adapter = gridAdapter

        rowAdapter = RowAdapter(onItemClick = { openItem(it) }, onSeeAll = { selectCategory(it) })
        binding.recyclerRows.layoutManager = LinearLayoutManager(this)
        binding.recyclerRows.adapter = rowAdapter

        catAdapter = CategoryAdapter(categories) { selectCategory(it.id) }
        binding.recyclerCategories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerCategories.adapter = catAdapter

        binding.btnSearchIcon.setOnClickListener {
            if (binding.etSearch.visibility == View.VISIBLE) closeSearch() else openSearch()
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refreshView() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.btnLive.setOnClickListener { switchTab("live") }
        binding.btnMovies.setOnClickListener { switchTab("movies") }
        binding.btnSeries.setOnClickListener { switchTab("series") }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.etSearch.visibility == View.VISIBLE -> closeSearch()
                    currentCat != "all" -> selectCategory("all")
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (mode == "m3u") {
            // Una lista M3U no separa en vivo/películas/series: es un único
            // catálogo con sus propias categorías.
            binding.tabRow.visibility = View.GONE
            currentTab = "live"
            gridAdapter.setContentType("live")
            gridLayoutManager.spanCount = spanFor("live")
            loadTab()
        } else {
            binding.tabRow.visibility = View.VISIBLE
            switchTab("live")
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver del reproductor, la fila "Vistos recientemente" se actualiza.
        if (allItems.isNotEmpty() && currentCat == "all" && searchQuery().isEmpty()) showRows()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Navegación
    // ------------------------------------------------------------------

    private fun switchTab(tab: String) {
        currentTab = tab
        currentCat = "all"
        if (binding.etSearch.visibility == View.VISIBLE) closeSearch()
        updateNav()
        gridAdapter.setContentType(tab)
        gridLayoutManager.spanCount = spanFor(tab)
        loadTab()
    }

    private fun updateNav() {
        val brand = ContextCompat.getColor(this, R.color.brand)
        val ink = ContextCompat.getColor(this, R.color.ink)
        val muted = ContextCompat.getColor(this, R.color.muted)
        val navItems = listOf(
            Triple(binding.ivNavLive, binding.tvNavLive, "live"),
            Triple(binding.ivNavMovies, binding.tvNavMovies, "movies"),
            Triple(binding.ivNavSeries, binding.tvNavSeries, "series")
        )
        for ((icon, label, id) in navItems) {
            styleNavItem(icon, label, id == currentTab, brand, ink, muted)
        }
    }

    private fun styleNavItem(icon: ImageView, label: TextView, active: Boolean, brand: Int, ink: Int, muted: Int) {
        icon.imageTintList = ColorStateList.valueOf(if (active) brand else muted)
        if (active) icon.setBackgroundResource(R.drawable.nav_indicator_bg) else icon.background = null
        label.setTextColor(if (active) ink else muted)
        label.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    // Columnas según el ancho real de la pantalla: 3 en celular, más en tablet/TV.
    private fun spanFor(tab: String): Int {
        val widthDp = resources.configuration.screenWidthDp
        val target = if (tab == "live" || mode == "m3u") 130 else 118
        return max(3, widthDp / target)
    }

    private fun selectCategory(catId: String) {
        currentCat = catId
        catAdapter.setSelected(catId)
        val index = categories.indexOfFirst { it.id == catId }
        if (index >= 0) binding.recyclerCategories.smoothScrollToPosition(index)
        refreshView()
        binding.recyclerChannels.scrollToPosition(0)
    }

    private fun searchQuery() = binding.etSearch.text.toString().trim()

    private fun openSearch() {
        binding.etSearch.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.setText("")
        binding.etSearch.visibility = View.GONE
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Cerrar sesión")
            .setMessage("¿Quieres salir y conectar otro servidor o lista?")
            .setPositiveButton("Salir") { _, _ ->
                getSharedPreferences("frp", MODE_PRIVATE).edit().clear().apply()
                RecentStore.clear(this)
                PlayerPlaylist.clear()
                val i = Intent(this, LoginActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(i)
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Mostrar contenido: inicio por filas o grilla filtrada
    // ------------------------------------------------------------------

    private fun refreshView() {
        if (currentCat == "all" && searchQuery().isEmpty()) showRows() else showGrid()
    }

    private fun isPosterTab() = mode != "m3u" && currentTab != "live"

    private fun showRows() {
        binding.recyclerRows.visibility = View.VISIBLE
        binding.recyclerChannels.visibility = View.GONE

        val rows = mutableListOf<HomeRow>()
        heroItem?.let {
            rows.add(HomeRow.Hero(it, if (currentTab == "series") "Ver serie" else "Reproducir"))
        }
        val recents = RecentStore.get(this, recentKey())
        if (recents.isNotEmpty()) rows.add(HomeRow.Items("Vistos recientemente", null, recents))

        val byCategory = allItems.groupBy { it.category }
        for (cat in categories) {
            if (cat.id == "all") continue
            val items = byCategory[cat.id] ?: continue
            rows.add(HomeRow.Items(cat.name, cat.id, items.take(40)))
        }
        rowAdapter.submit(rows, isPosterTab())
        binding.tvCount.text = "${allItems.size} ${tabNoun()}"
        showEmptyIfNeeded(allItems.isEmpty(), "No hay contenido disponible")
    }

    private fun showGrid() {
        binding.recyclerRows.visibility = View.GONE
        binding.recyclerChannels.visibility = View.VISIBLE

        val query = searchQuery()
        var filtered: List<Channel> = if (currentCat == "all") allItems else allItems.filter { it.category == currentCat }
        if (query.isNotEmpty()) filtered = filtered.filter { it.name.contains(query, ignoreCase = true) }
        gridAdapter.update(filtered)

        val catName = categories.firstOrNull { it.id == currentCat }?.name
        binding.tvCount.text = if (currentCat != "all" && catName != null) {
            "$catName · ${filtered.size}"
        } else {
            "${filtered.size} resultados"
        }
        showEmptyIfNeeded(filtered.isEmpty(), if (query.isNotEmpty()) "Sin resultados para \"$query\"" else "Esta categoría está vacía")
    }

    private fun showEmptyIfNeeded(empty: Boolean, message: String) {
        binding.tvEmpty.text = message
        binding.tvEmpty.visibility = if (empty && binding.progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun tabNoun() = when {
        mode == "m3u" -> "canales"
        currentTab == "movies" -> "películas"
        currentTab == "series" -> "series"
        else -> "canales"
    }

    // ------------------------------------------------------------------
    // Abrir contenido
    // ------------------------------------------------------------------

    private fun recentKey(): String =
        if (mode == "m3u") "m3u_${m3uUrl.hashCode()}" else "xt_${server.hashCode()}_${user}_$currentTab"

    private fun openItem(item: Channel) {
        RecentStore.add(this, recentKey(), item)
        when {
            mode == "m3u" -> {
                val directUrl = item.directUrl ?: return
                stageChannelPlaylist(item)
                play(directUrl, item.name, "live")
            }
            currentTab == "series" -> {
                val i = Intent(this, SeriesDetailActivity::class.java)
                i.putExtra("server", server)
                i.putExtra("user", user)
                i.putExtra("pass", pass)
                i.putExtra("seriesId", item.streamId)
                i.putExtra("name", item.name)
                i.putExtra("cover", item.logo)
                startActivity(i)
            }
            currentTab == "movies" -> {
                PlayerPlaylist.clear()
                play(Config.movieUrl(server, user, pass, item.streamId), item.name, "movies")
            }
            else -> {
                stageChannelPlaylist(item)
                play(Config.liveUrl(server, user, pass, item.streamId), item.name, "live")
            }
        }
    }

    // La lista de "canales" dentro del reproductor: la categoría del canal
    // elegido (más útil que miles de canales mezclados), o todos si no tiene.
    private fun stageChannelPlaylist(item: Channel) {
        val source = allItems.filter { it.category == item.category }.ifEmpty { allItems }
        PlayerPlaylist.label = "Canales"
        PlayerPlaylist.items = source.mapNotNull { c ->
            val u = if (mode == "m3u") c.directUrl else Config.liveUrl(server, user, pass, c.streamId)
            u?.let { PlayerPlaylist.Item(c.streamId, c.name, it, c.logo) }
        }
    }

    private fun play(url: String, name: String, type: String) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra("url", url)
        i.putExtra("name", name)
        i.putExtra("type", type)
        startActivity(i)
    }

    // ------------------------------------------------------------------
    // Carga de catálogo
    // ------------------------------------------------------------------

    private fun loadTab() {
        val tab = currentTab
        catalogCache[tab]?.let {
            applyCatalog(it)
            return
        }
        loadJob?.cancel()
        allItems.clear()
        rowAdapter.submit(emptyList(), isPosterTab())
        gridAdapter.update(emptyList())
        binding.tvEmpty.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.tvCount.text = "Cargando…"

        loadJob = scope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) {
                    if (mode == "m3u") fetchM3u() else fetchXtream(tab)
                }
                catalogCache[tab] = catalog
                // Si el usuario ya cambió de pestaña mientras cargaba, esta
                // respuesta queda en caché pero no pisa lo que está viendo.
                if (currentTab == tab) applyCatalog(catalog)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (currentTab == tab) {
                    binding.progress.visibility = View.GONE
                    binding.tvCount.text = "Error al cargar"
                    showEmptyIfNeeded(true, "No se pudo cargar el contenido.\nRevisa tu conexión e inténtalo de nuevo.")
                }
            }
        }
    }

    private fun applyCatalog(catalog: Catalog) {
        binding.progress.visibility = View.GONE
        allItems.clear()
        allItems.addAll(catalog.items)
        heroItem = catalog.hero
        currentCat = "all"
        catAdapter.update(catalog.categories)
        catAdapter.setSelected("all")
        binding.recyclerCategories.scrollToPosition(0)
        binding.recyclerRows.scrollToPosition(0)
        refreshView()
    }

    private suspend fun fetchXtream(tab: String): Catalog = kotlinx.coroutines.coroutineScope {
        val streamsUrl = when (tab) {
            "movies" -> Config.vodStreams(server, user, pass)
            "series" -> Config.seriesStreams(server, user, pass)
            else -> Config.liveStreams(server, user, pass)
        }
        val catsUrl = when (tab) {
            "movies" -> Config.vodCategories(server, user, pass)
            "series" -> Config.seriesCategories(server, user, pass)
            else -> Config.liveCategories(server, user, pass)
        }
        // Streams y categorías son pedidos independientes: van en paralelo.
        val streamsDeferred = async { httpGet(streamsUrl) ?: "[]" }
        val catsDeferred = async { httpGet(catsUrl) ?: "[]" }

        val arr = JSONArray(streamsDeferred.await())
        val list = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = if (tab == "series") o.optInt("series_id") else o.optInt("stream_id")
            val logo = if (tab == "live") o.optString("stream_icon") else o.optString("cover", o.optString("stream_icon"))
            list.add(Channel(streamId = id, name = o.optString("name"), logo = logo, category = o.optString("category_id")))
        }

        val arrCat = JSONArray(catsDeferred.await())
        val counts = list.groupingBy { it.category }.eachCount()
        val catList = mutableListOf(Category("all", "Todo", list.size))
        for (i in 0 until arrCat.length()) {
            val o = arrCat.getJSONObject(i)
            val catId = o.optString("category_id")
            val count = counts[catId] ?: 0
            if (count > 0) catList.add(Category(catId, o.optString("category_name"), count))
        }

        val hero = if (tab == "live") null else list.filter { it.logo.startsWith("http") }.take(60).randomOrNull()
        Catalog(list, catList, hero)
    }

    private fun fetchM3u(): Catalog {
        val body = httpGet(m3uUrl) ?: ""
        val list = M3uParser.parse(body).mapIndexed { index, e ->
            Channel(streamId = index, name = e.name, logo = e.logo, category = e.group, directUrl = e.url)
        }
        val counts = list.groupingBy { it.category }.eachCount()
        val catList = mutableListOf(Category("all", "Todo", list.size))
        for (group in counts.keys.sorted()) {
            catList.add(Category(group, group, counts[group] ?: 0))
        }
        return Catalog(list, catList, null)
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp -> resp.body?.string() }
    }
}

data class Channel(
    val streamId: Int,
    val name: String,
    val logo: String,
    val category: String,
    val directUrl: String? = null
)
