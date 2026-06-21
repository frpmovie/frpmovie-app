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

    private val allChannels = mutableListOf<Channel>()
    private lateinit var adapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        user = intent.getStringExtra("user") ?: ""
        pass = intent.getStringExtra("pass") ?: ""

        adapter = ChannelAdapter(allChannels) { channel ->
            val i = Intent(this, PlayerActivity::class.java)
            i.putExtra("url", Config.liveUrl(user, pass, channel.streamId))
            i.putExtra("name", channel.name)
            startActivity(i)
        }

        binding.recyclerChannels.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerChannels.adapter = adapter

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadChannels()
    }

    private fun filter(query: String) {
        val filtered = if (query.isEmpty()) allChannels
        else allChannels.filter { it.name.contains(query, ignoreCase = true) }
        adapter.update(filtered)
    }

    private fun loadChannels() {
        binding.progress.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(Config.liveStreams(user, pass)).build()
                val body = client.newCall(req).execute().body?.string() ?: "[]"
                val arr = JSONArray(body)
                val list = mutableListOf<Channel>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Channel(
                            streamId = o.optInt("stream_id"),
                            name = o.optString("name"),
                            logo = o.optString("stream_icon"),
                            category = o.optString("category_id")
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    allChannels.clear()
                    allChannels.addAll(list)
                    adapter.update(allChannels)
                    binding.progress.visibility = View.GONE
                    binding.tvCount.text = "${allChannels.size} canales"
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
