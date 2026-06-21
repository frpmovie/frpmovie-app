package com.frpmovie.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Si ya inició sesión antes, entrar directo
        val prefs = getSharedPreferences("frp", MODE_PRIVATE)
        val savedUser = prefs.getString("user", null)
        val savedPass = prefs.getString("pass", null)
        if (savedUser != null && savedPass != null) {
            goToMain(savedUser, savedPass)
            return
        }

        binding.btnLogin.setOnClickListener {
            val user = binding.etUser.text.toString().trim()
            val pass = binding.etPass.text.toString().trim()
            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Escribe usuario y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            login(user, pass)
        }
    }

    private fun login(user: String, pass: String) {
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "Entrando..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(Config.playerApi(user, pass)).build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val userInfo = json.optJSONObject("user_info")
                val auth = userInfo?.optInt("auth", 0) ?: 0

                withContext(Dispatchers.Main) {
                    if (auth == 1) {
                        // Guardar sesión
                        getSharedPreferences("frp", MODE_PRIVATE).edit()
                            .putString("user", user)
                            .putString("pass", pass)
                            .apply()
                        goToMain(user, pass)
                    } else {
                        Toast.makeText(this@LoginActivity, "Usuario o contraseña incorrectos", Toast.LENGTH_LONG).show()
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.text = "Entrar"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "No se pudo conectar. Revisa tu internet.", Toast.LENGTH_LONG).show()
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = "Entrar"
                }
            }
        }
    }

    private fun goToMain(user: String, pass: String) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("user", user)
        i.putExtra("pass", pass)
        startActivity(i)
        finish()
    }
}
