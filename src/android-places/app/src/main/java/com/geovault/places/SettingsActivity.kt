package com.geovault.places

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class SettingsActivity : AppCompatActivity() {
    private lateinit var serverUrlEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var saveButton: Button
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }
    
    companion object {
        private const val PREF_SERVER_URL = "server_url"
        private const val PREF_API_KEY = "api_key"
        private const val DEFAULT_SERVER_URL = ""
    }
    
    private fun normalizeServerUrl(url: String): String {
        var serverUrl = url.trim().trimStart('/').trimEnd('/')
        if (serverUrl.isNotEmpty() && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://$serverUrl"
        }
        return serverUrl
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        apiKeyEdit = findViewById(R.id.apiKeyEdit)
        saveButton = findViewById(R.id.saveButton)
        
        // Handle window insets for status bar and navigation bar
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            
            // Apply top padding to header
            headerView.updatePadding(top = systemBars.top + 20)
            
            // Apply the larger of the two bottom insets (navigation bar or keyboard)
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            view.updatePadding(bottom = bottomInset)
            
            windowInsets
        }
        
        // Load current settings
        serverUrlEdit.setText(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        apiKeyEdit.setText(prefs.getString(PREF_API_KEY, "") ?: "")
        
        saveButton.setOnClickListener {
            saveSettings()
        }
    }
    
    private fun saveSettings() {
        var serverUrl = normalizeServerUrl(serverUrlEdit.text.toString())
        val apiKey = apiKeyEdit.text.toString().trim()
        
        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.settings_required), Toast.LENGTH_LONG).show()
            return
        }
        
        prefs.edit()
            .putString(PREF_SERVER_URL, serverUrl)
            .putString(PREF_API_KEY, apiKey)
            .apply()
        
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        
        // Return result to indicate settings were saved
        setResult(RESULT_OK)
        finish()
    }

    override fun finish() {
        super.finish()
        safeNoAnimation()
    }

    private fun safeNoAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

