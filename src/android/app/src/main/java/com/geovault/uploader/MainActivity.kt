package com.geovault.uploader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var filenameEdit: EditText
    private lateinit var suffixText: TextView
    private lateinit var uploadButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    
    private lateinit var validationLayout: android.view.ViewGroup
    private lateinit var uploaderLayout: android.view.ViewGroup
    private lateinit var validationProgressBar: ProgressBar
    private lateinit var validationStatusText: TextView
    private lateinit var validationTitleText: TextView
    private lateinit var settingsButton: Button
    
    private var fileUri: Uri? = null
    private var originalFilename: String? = null
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }
    
    companion object {
        private const val PREF_SERVER_URL = "server_url"
        private const val PREF_API_KEY = "api_key"
        private const val PREF_ADD_SUFFIX = "add_suffix"
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
        setContentView(R.layout.activity_main)
        
        filenameEdit = findViewById(R.id.filenameEdit)
        suffixText = findViewById(R.id.suffixText)
        uploadButton = findViewById(R.id.uploadButton)
        cancelButton = findViewById(R.id.cancelButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        
        validationLayout = findViewById(R.id.validationLayout)
        uploaderLayout = findViewById(R.id.uploaderLayout)
        validationProgressBar = findViewById(R.id.validationProgressBar)
        validationStatusText = findViewById(R.id.validationStatusText)
        validationTitleText = findViewById(R.id.validationTitleText)
        settingsButton = findViewById(R.id.settingsButton)
        
        // Set up settings button click listener
        settingsButton.setOnClickListener {
            showSettingsDialog()
        }
        
        // Check if we have settings configured
        val serverUrl = normalizeServerUrl(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
        
        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            showSettingsDialog()
            return
        }
        
        uploadButton.setOnClickListener {
            uploadFile()
        }
        
        cancelButton.setOnClickListener {
            finish()
        }
        
        // Handle the share intent
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            showValidationScreen()
            return
        }
        
        when (intent.action) {
            Intent.ACTION_SEND -> {
                fileUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                originalFilename = intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            else -> {
                // Regular launch - show validation screen
                showValidationScreen()
                return
            }
        }
        
        if (fileUri == null) {
            showValidationScreen()
            return
        }
        
        // We have a file, show uploader
        showUploaderScreen()
        
        // Extract filename from URI if not provided
        if (originalFilename.isNullOrEmpty()) {
            originalFilename = getFilenameFromUri(fileUri!!)
        }
        
        // Use the full original filename (with extension) as the base
        val baseFilename = originalFilename ?: "uploaded_file"
        
        filenameEdit.setText(baseFilename)
        
        // Show preview with suffix if enabled
        val addSuffix = prefs.getBoolean(PREF_ADD_SUFFIX, true)
        if (addSuffix) {
            val (nameWithoutExt, extension) = splitFilename(baseFilename)
            val suffix = "_android_upload"
            val previewFilename = if (extension.isNotEmpty()) {
                "${nameWithoutExt}${suffix}.$extension"
            } else {
                "${baseFilename}${suffix}"
            }
            suffixText.text = "Will be saved as: $previewFilename"
        } else {
            suffixText.text = "Will be saved as: $baseFilename"
        }
    }
    
    private fun showValidationScreen() {
        validationLayout.visibility = android.view.View.VISIBLE
        uploaderLayout.visibility = android.view.View.GONE
        // Ensure settings button is clickable
        settingsButton.isEnabled = true
        validateApiKey()
    }
    
    private fun showUploaderScreen() {
        validationLayout.visibility = android.view.View.GONE
        uploaderLayout.visibility = android.view.View.VISIBLE
    }
    
    private fun getFilenameFromUri(uri: Uri): String {
        var filename = "uploaded_file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    filename = cursor.getString(nameIndex) ?: filename
                }
            }
        } catch (e: Exception) {
            // Fallback to URI path
            val path = uri.path
            if (path != null) {
                filename = File(path).name
            }
        }
        return filename
    }
    
    private fun splitFilename(filename: String): Pair<String, String> {
        val lastDotIndex = filename.lastIndexOf('.')
        return if (lastDotIndex > 0 && lastDotIndex < filename.length - 1) {
            // Has extension
            Pair(filename.substring(0, lastDotIndex), filename.substring(lastDotIndex + 1))
        } else {
            // No extension
            Pair(filename, "")
        }
    }
    
    private fun uploadFile() {
        var serverUrl = normalizeServerUrl(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
        
        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            showSettingsDialog()
            return
        }
        
        if (fileUri == null) {
            showError(getString(R.string.no_file))
            return
        }
        
        val userFilename = filenameEdit.text.toString().trim()
        if (userFilename.isEmpty()) {
            showError(getString(R.string.enter_filename))
            return
        }
        
        // Apply suffix if enabled
        val addSuffix = prefs.getBoolean(PREF_ADD_SUFFIX, true)
        val finalFilename = if (addSuffix) {
            val (nameWithoutExt, extension) = splitFilename(userFilename)
            val suffix = "_android_upload"
            if (extension.isNotEmpty()) {
                "${nameWithoutExt}${suffix}.$extension"
            } else {
                "${userFilename}${suffix}"
            }
        } else {
            userFilename
        }
        
        // Show progress
        progressBar.visibility = View.VISIBLE
        uploadButton.isEnabled = false
        cancelButton.isEnabled = false
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.uploading)
        
        // Read file content
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(fileUri!!)
            if (inputStream == null) {
                showError(getString(R.string.error_read_file))
                return
            }
            
            // Create a temporary file
            val tempFile = File(cacheDir, finalFilename)
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            
            // Build the request
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    finalFilename,
                    tempFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            
            val uploadUrl = "$serverUrl/api/item/import/upload"
            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        uploadButton.isEnabled = true
                        cancelButton.isEnabled = true
                        val errorMsg = e.message ?: "Unknown error"
                        val cleanErrorMsg = errorMsg.replace(Regex("^Failed to connect to /"), "Failed to connect to ")
                        showError("Connection failed\n$cleanErrorMsg")
                    }
                    tempFile.delete()
                }
                
                override fun onResponse(call: Call, response: Response) {
                    // Read response body before checking status (can only read once)
                    val responseBody = try {
                        response.body?.string() ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    
                    val statusCode = response.code
                    
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        uploadButton.isEnabled = true
                        cancelButton.isEnabled = true
                        
                        if (response.isSuccessful) {
                            statusText.text = getString(R.string.upload_success)
                            // Close after a longer delay for readability
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                finish()
                            }, 2500)
                        } else {
                            // Parse error message from response
                            val serverMessage = try {
                                if (responseBody.isNotEmpty() && responseBody.trimStart().startsWith("{")) {
                                    val json = org.json.JSONObject(responseBody)
                                    json.optString("error", json.optString("message", ""))
                                } else {
                                    ""
                                }
                            } catch (e: Exception) {
                                ""
                            }
                            
                            val errorMessage = when (statusCode) {
                                400 -> "Upload failed (400)\nInvalid request. Check your file format."
                                401 -> "Upload failed (401)\nAPI key is invalid or expired.\nCheck Settings."
                                403 -> "Upload failed (403)\nAccess denied. Check API key permissions."
                                404 -> "Upload failed (404)\nServer endpoint not found.\nCheck your server URL in Settings."
                                500 -> "Upload failed (500)\nServer error. Try again later."
                                else -> "Upload failed ($statusCode)"
                            }
                            
                            // Add server message if available and not too long
                            val fullMessage = if (serverMessage.isNotEmpty() && serverMessage.length < 100) {
                                "$errorMessage\n\n$serverMessage"
                            } else if (serverMessage.isNotEmpty()) {
                                "$errorMessage\n\n${serverMessage.take(100)}..."
                            } else {
                                errorMessage
                            }
                            
                            showError(fullMessage)
                        }
                    }
                    tempFile.delete()
                    response.close()
                }
            })
        } catch (e: Exception) {
            progressBar.visibility = View.GONE
            uploadButton.isEnabled = true
            cancelButton.isEnabled = true
            showError("Error: ${e.message}")
        }
    }
    
    private fun showError(message: String) {
        statusText.visibility = View.VISIBLE
        statusText.text = message
    }
    
    private fun validateApiKey() {
        var serverUrl = normalizeServerUrl(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
        
        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            validationTitleText.text = getString(R.string.config_required)
            validationStatusText.text = getString(R.string.config_settings_first)
            validationProgressBar.visibility = android.view.View.GONE
            return
        }
        
        validationTitleText.text = getString(R.string.validating_key)
        validationProgressBar.visibility = android.view.View.VISIBLE
        validationStatusText.text = getString(R.string.connecting_server)
        
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        
        // Construct the full URL
        val validateUrl = "$serverUrl/api/user/api-keys/validate/"
        
        val request = Request.Builder()
            .url(validateUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Connection", "close")
            .post("{}".toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread {
                    validationTitleText.text = getString(R.string.validation_failed)
                    validationProgressBar.visibility = android.view.View.GONE
                    
                    val errorMsg = e.message ?: "Unknown error"
                    val cleanErrorMsg = errorMsg.replace(Regex("^Failed to connect to /"), "Failed to connect to ")
                    
                    // Special handling for "end of stream" errors
                    val displayMsg = if (cleanErrorMsg.contains("end of stream", ignoreCase = true)) {
                        "✗ Connection closed unexpectedly\n\nThe server may be slow or not responding.\nTap Settings to retry."
                    } else {
                        "✗ Connection failed\n\n$cleanErrorMsg\n\nCheck your server URL and network connection."
                    }
                    
                    validationStatusText.text = displayMsg
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                // Read response body before checking status (can only read once)
                val responseBody = try {
                    response.body?.string() ?: ""
                } catch (e: Exception) {
                    // Handle "end of stream" errors when reading body
                    runOnUiThread {
                        validationTitleText.text = getString(R.string.validation_failed)
                        validationProgressBar.visibility = android.view.View.GONE
                        validationStatusText.text = "✗ Error reading response\n\nThe server connection was interrupted.\nTap Settings to retry."
                    }
                    response.close()
                    return
                }
                
                val statusCode = response.code
                
                runOnUiThread {
                    validationProgressBar.visibility = android.view.View.GONE
                    
                    try {
                        if (response.isSuccessful) {
                            // Parse JSON response
                            val jsonResponse = try {
                                if (responseBody.isNotEmpty() && responseBody.trimStart().startsWith("{")) {
                                    org.json.JSONObject(responseBody)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }
                            
                            if (jsonResponse != null && jsonResponse.optBoolean("valid", false)) {
                                validationTitleText.text = getString(R.string.api_key_valid)
                                val keyName = jsonResponse.optString("key_name", "Unnamed Key")
                                validationStatusText.text = getString(R.string.api_key_valid_msg, keyName)
                            } else {
                                validationTitleText.text = getString(R.string.validation_failed)
                                validationStatusText.text = "✗ API key validation failed\n\nThe key may be invalid or disabled."
                            }
                        } else {
                            validationTitleText.text = getString(R.string.validation_failed)
                            
                            // Extract server message if available
                            val serverMessage = try {
                                if (responseBody.isNotEmpty() && responseBody.trimStart().startsWith("{")) {
                                    val json = org.json.JSONObject(responseBody)
                                    json.optString("error", "")
                                } else {
                                    ""
                                }
                            } catch (e: Exception) {
                                ""
                            }
                            
                            // Provide specific error messages based on status code
                            val errorMessage = when (statusCode) {
                                400 -> "✗ Invalid request (400)\n\nCheck your API key format."
                                401 -> "✗ Unauthorized (401)\n\nAPI key is invalid or expired.\nCheck Settings."
                                403 -> "✗ Forbidden (403)\n\nAPI key may be disabled or inactive."
                                404 -> "✗ Not found (404)\n\nValidation endpoint not found.\nCheck your server URL: $validateUrl"
                                500 -> "✗ Server error (500)\n\nTry again later."
                                else -> "✗ Validation failed ($statusCode)"
                            }
                            
                            // Add server message if available and not too long
                            val fullMessage = if (serverMessage.isNotEmpty() && serverMessage.length < 80) {
                                "$errorMessage\n\n$serverMessage"
                            } else {
                                errorMessage
                            }
                            
                            validationStatusText.text = fullMessage
                        }
                    } catch (e: Exception) {
                        validationTitleText.text = getString(R.string.validation_failed)
                        val errorMsg = if (e.message?.contains("end of stream", ignoreCase = true) == true) {
                            "✗ Connection interrupted\n\nTap Settings to retry."
                        } else {
                            "✗ Error: ${e.message ?: "Unknown error"}"
                        }
                        validationStatusText.text = errorMsg
                    } finally {
                        response.close()
                    }
                }
            }
        })
    }
    
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val serverUrlEdit = view.findViewById<EditText>(R.id.serverUrlEdit)
        val apiKeyEdit = view.findViewById<EditText>(R.id.apiKeyEdit)
        val addSuffixCheckbox = view.findViewById<android.widget.CheckBox>(R.id.addSuffixCheckbox)
        
        serverUrlEdit.setText(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        apiKeyEdit.setText(prefs.getString(PREF_API_KEY, "") ?: "")
        addSuffixCheckbox.isChecked = prefs.getBoolean(PREF_ADD_SUFFIX, true)
        
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.save_settings) { _, _ ->
                var serverUrl = normalizeServerUrl(serverUrlEdit.text.toString())
                val apiKey = apiKeyEdit.text.toString().trim()
                val addSuffix = addSuffixCheckbox.isChecked
                
                if (serverUrl.isEmpty() || apiKey.isEmpty()) {
                    Toast.makeText(this, getString(R.string.settings_required), Toast.LENGTH_LONG).show()
                    showSettingsDialog() // Show again
                } else {
                    prefs.edit()
                        .putString(PREF_SERVER_URL, serverUrl)
                        .putString(PREF_API_KEY, apiKey)
                        .putBoolean(PREF_ADD_SUFFIX, addSuffix)
                        .apply()
                    
                    Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                    
                    // Validate the API key instead of closing
                    if (fileUri != null) {
                        // We have a file, show uploader
                        showUploaderScreen()
                        handleIntent(intent)
                    } else {
                        // No file, show validation screen
                        showValidationScreen()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                // If we have settings, show validation screen; otherwise stay on settings
                val serverUrl = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
                val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
                if (serverUrl.isNotEmpty() && apiKey.isNotEmpty()) {
                    showValidationScreen()
                }
            }
            .setCancelable(false)
            .show()
    }
}

