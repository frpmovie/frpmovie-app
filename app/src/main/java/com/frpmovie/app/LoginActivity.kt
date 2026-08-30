package com.frpmovie.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.frpmovie.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val client = OkHttpClient()
    private var mode = "xtream"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Si ya inició sesión antes, entrar directo
        val prefs = getSharedPreferences("frp", MODE_PRIVATE)
        when (prefs.getString("mode", null)) {
            "xtream" -> {
                val server = prefs.getString("server", null)
                val savedUser = prefs.getString("user", null)
                val savedPass = prefs.getString("pass", null)
                if (server != null && savedUser != null && savedPass != null) {
                    goToMainXtream(server, savedUser, savedPass)
                    return
                }
            }
            "m3u" -> {
                val m3uUrl = prefs.getString("m3u_url", null)
                if (m3uUrl != null) {
                    goToMainM3u(m3uUrl)
                    return
                }
            }
        }

        binding.btnModeXtream.setOnClickListener { setMode("xtream") }
        binding.btnModeM3u.setOnClickListener { setMode("m3u") }

        binding.btnLogin.setOnClickListener {
            if (mode == "xtream") {
                val server = binding.etServer.text.toString().trim()
                val user = binding.etUser.text.toString().trim()
                val pass = binding.etPass.text.toString().trim()
                if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(this, "Completa servidor, usuario y contraseña", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                loginXtream(Config.normalizeServer(server), user, pass)
            } else {
                val url = binding.etM3uUrl.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "Escribe la URL de tu lista M3U", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                loginM3u(url)
            }
        }
    }

    private fun setMode(newMode: String) {
        mode = newMode
        val brand = ContextCompat.getColor(this, R.color.brand)
        val ink = ContextCompat.getColor(this, R.color.ink)
        val muted = ContextCompat.getColor(this, R.color.muted)
        val xtreamActive = mode == "xtream"
        binding.btnModeXtream.backgroundTintList = if (xtreamActive) ColorStateList.valueOf(brand) else null
        binding.btnModeXtream.setTextColor(if (xtreamActive) ink else muted)
        binding.btnModeM3u.backgroundTintList = if (!xtreamActive) ColorStateList.valueOf(brand) else null
        binding.btnModeM3u.setTextColor(if (!xtreamActive) ink else muted)
        binding.groupXtream.visibility = if (xtreamActive) View.VISIBLE else View.GONE
        binding.groupM3u.visibility = if (xtreamActive) View.GONE else View.VISIBLE
    }

    private fun loginXtream(server: String, user: String, pass: String) {
        setLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(Config.playerApi(server, user, pass)).build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val userInfo = json.optJSONObject("user_info")
                val auth = userInfo?.optInt("auth", 0) ?: 0

                withContext(Dispatchers.Main) {
                    if (auth == 1) {
                        getSharedPreferences("frp", MODE_PRIVATE).edit()
                            .putString("mode", "xtream")
                            .putString("server", server)
                            .putString("user", user)
                            .putString("pass", pass)
                            .apply()
                        goToMainXtream(server, user, pass)
                    } else {
                        Toast.makeText(this@LoginActivity, "Usuario o contraseña incorrectos", Toast.LENGTH_LONG).show()
                        setLoading(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "No se pudo conectar. Revisa el servidor y tu internet.", Toast.LENGTH_LONG).show()
                    setLoading(false)
                }
            }
        }
    }

    private fun loginM3u(url: String) {
        setLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(url).build()
                val body = client.newCall(req).execute().body?.string() ?: ""
                if (!body.contains("#EXTM3U") && !body.contains("#EXTINF")) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "Esa URL no parece una lista M3U válida", Toast.LENGTH_LONG).show()
                        setLoading(false)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    getSharedPreferences("frp", MODE_PRIVATE).edit()
                        .putString("mode", "m3u")
                        .putString("m3u_url", url)
                        .apply()
                    goToMainM3u(url)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "No se pudo cargar la lista. Revisa la URL y tu internet.", Toast.LENGTH_LONG).show()
                    setLoading(false)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text = if (loading) "Entrando..." else "Entrar"
    }

    private fun goToMainXtream(server: String, user: String, pass: String) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("mode", "xtream")
        i.putExtra("server", server)
        i.putExtra("user", user)
        i.putExtra("pass", pass)
        startActivity(i)
        finish()
    }

    private fun goToMainM3u(url: String) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("mode", "m3u")
        i.putExtra("m3uUrl", url)
        startActivity(i)
        finish()
    }
}
