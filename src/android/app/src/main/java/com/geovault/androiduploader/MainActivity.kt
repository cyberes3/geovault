package com.geovault.androiduploader

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private var timestamp: String = ""
    
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
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (uris != null && uris.isNotEmpty()) {
                    fileUri = uris[0] // Handle only the first file for simplicity
                }
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
        
        // Generate ISO timestamp for suffix (local time)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.getDefault())
        timestamp = dateFormat.format(Date())
        val suffix = "_android_upload_$timestamp"
        
        // Use the full original filename (with extension) as the base
        val baseFilename = originalFilename ?: "uploaded_file"
        
        filenameEdit.setText(baseFilename)
        
        // Show preview with postfix inserted before extension
        val (nameWithoutExt, extension) = splitFilename(baseFilename)
        val previewFilename = if (extension.isNotEmpty()) {
            "${nameWithoutExt}${suffix}.$extension"
        } else {
            "${baseFilename}${suffix}"
        }
        suffixText.text = "Full filename: $previewFilename"
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
        
        // Split filename into base name and extension, then insert postfix before extension
        val (nameWithoutExt, extension) = splitFilename(userFilename)
        val suffix = "_android_upload_$timestamp"
        val finalFilename = if (extension.isNotEmpty()) {
            "${nameWithoutExt}${suffix}.$extension"
        } else {
            "${userFilename}${suffix}"
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
                        // Clean up error message if it contains a malformed URL
                        val cleanErrorMsg = errorMsg.replace(Regex("^Failed to connect to /"), "Failed to connect to ")
                        showError("Upload failed: $cleanErrorMsg\n\nAttempted URL: $uploadUrl")
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
                            Toast.makeText(this@MainActivity, getString(R.string.upload_success), Toast.LENGTH_SHORT).show()
                            // Close after a short delay
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                finish()
                            }, 1500)
                        } else {
                            // Parse error message from response
                            val errorMessage = try {
                                if (responseBody.isNotEmpty() && responseBody.trimStart().startsWith("{")) {
                                    // Try to parse as JSON
                                    val json = org.json.JSONObject(responseBody)
                                    json.optString("error", json.optString("message", "Unknown error"))
                                } else if (responseBody.isNotEmpty()) {
                                    // Not JSON, use raw response (truncated)
                                    responseBody.take(200)
                                } else {
                                    "No error details available"
                                }
                            } catch (e: Exception) {
                                if (responseBody.isNotEmpty()) {
                                    responseBody.take(200)
                                } else {
                                    "Unknown error"
                                }
                            }
                            
                            val fullErrorMessage = when (statusCode) {
                                400 -> "Bad request (400): $errorMessage"
                                401 -> "Unauthorized (401): $errorMessage\n\nPlease check your API key in settings."
                                403 -> "Forbidden (403): $errorMessage\n\nPlease check your API key permissions."
                                404 -> "Not found (404): $errorMessage\n\nPlease check the server URL: $uploadUrl"
                                500 -> "Server error (500): $errorMessage"
                                else -> "Upload failed (HTTP $statusCode): $errorMessage"
                            }
                            
                            showError("$fullErrorMessage\n\nURL: $uploadUrl")
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
        // Also show in Toast for immediate visibility
        Toast.makeText(this, message.take(100), Toast.LENGTH_LONG).show()
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        
        // Construct the full URL
        val validateUrl = "$serverUrl/api/user/api-keys/validate/"
        
        val request = Request.Builder()
            .url(validateUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post("{}".toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread {
                    validationTitleText.text = getString(R.string.validation_failed)
                    validationProgressBar.visibility = android.view.View.GONE
                    val errorMsg = e.message ?: "Unknown error"
                    // Clean up error message if it contains a malformed URL
                    val cleanErrorMsg = errorMsg.replace(Regex("^Failed to connect to /"), "Failed to connect to ")
                    validationStatusText.text = getString(R.string.connection_failed_msg, cleanErrorMsg, validateUrl)
                }
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
                                // Try to get error message from response
                                val errorMsg = if (jsonResponse != null) {
                                    jsonResponse.optString("error", "API key is invalid")
                                } else if (responseBody.isNotEmpty()) {
                                    // Response is not JSON - might be HTML or plain text
                                    if (responseBody.contains("<html>") || responseBody.contains("<!DOCTYPE")) {
                                        "Server returned HTML instead of JSON. Check server URL."
                                    } else {
                                        "Unexpected response format: ${responseBody.take(100)}"
                                    }
                                } else {
                                    "API key validation failed (empty response)"
                                }
                                validationStatusText.text = getString(R.string.validation_failed_msg, errorMsg, responseBody.take(200))
                            }
                        } else {
                            validationTitleText.text = getString(R.string.validation_failed)
                            
                            // Provide specific error messages based on status code
                            val errorMessage = when (statusCode) {
                                400 -> {
                                    val error = if (responseBody.isNotEmpty()) {
                                        try {
                                            val json = org.json.JSONObject(responseBody)
                                            json.optString("error", "Bad request")
                                        } catch (e: Exception) {
                                            "Bad request"
                                        }
                                    } else {
                                        "Bad request - invalid API key format"
                                    }
                                    "✗ Invalid request: $error\n\nPlease check your API key format in settings."
                                }
                                401 -> {
                                    "✗ Unauthorized (401)\n\nThe API key is invalid or has been deleted.\n\nPlease check your API key in settings and ensure it starts with 'gv_'."
                                }
                                403 -> {
                                    "✗ Forbidden (403)\n\nThe server rejected the request. This may indicate:\n• The API key is invalid or inactive\n• CSRF protection is blocking the request\n• The endpoint requires different authentication\n\nPlease verify your API key in settings."
                                }
                                404 -> {
                                    "✗ Not Found (404)\n\nThe validation endpoint was not found.\n\nURL: $validateUrl\n\nPlease check your server URL in settings."
                                }
                                500 -> {
                                    val error = if (responseBody.isNotEmpty()) {
                                        try {
                                            val json = org.json.JSONObject(responseBody)
                                            json.optString("error", "Server error")
                                        } catch (e: Exception) {
                                            "Server error"
                                        }
                                    } else {
                                        "Server error"
                                    }
                                    "✗ Server error (500): $error\n\nPlease try again later or contact support."
                                }
                                else -> {
                                    val errorDetail = if (responseBody.isNotEmpty()) {
                                        try {
                                            val json = org.json.JSONObject(responseBody)
                                            json.optString("error", responseBody.take(200))
                                        } catch (e: Exception) {
                                            responseBody.take(200)
                                        }
                                    } else {
                                        "Unknown error"
                                    }
                                    "✗ Validation failed (HTTP $statusCode)\n\n$errorDetail\n\nPlease check your server URL and API key in settings."
                                }
                            }
                            
                            validationStatusText.text = errorMessage
                        }
                    } catch (e: Exception) {
                        validationTitleText.text = getString(R.string.validation_failed)
                        validationStatusText.text = getString(R.string.error_processing_response, e.message ?: "Unknown error", statusCode)
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
        
        serverUrlEdit.setText(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        apiKeyEdit.setText(prefs.getString(PREF_API_KEY, "") ?: "")
        
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.save_settings) { _, _ ->
                var serverUrl = normalizeServerUrl(serverUrlEdit.text.toString())
                val apiKey = apiKeyEdit.text.toString().trim()
                
                if (serverUrl.isEmpty() || apiKey.isEmpty()) {
                    Toast.makeText(this, getString(R.string.settings_required), Toast.LENGTH_LONG).show()
                    showSettingsDialog() // Show again
                } else {
                    prefs.edit()
                        .putString(PREF_SERVER_URL, serverUrl)
                        .putString(PREF_API_KEY, apiKey)
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

