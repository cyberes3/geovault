package com.geovault.uploader

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var uploadButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var uploadSpinner: ImageView
    private lateinit var statusText: TextView
    
    private lateinit var validationLayout: androidx.core.widget.NestedScrollView
    private lateinit var uploaderLayout: androidx.core.widget.NestedScrollView
    private lateinit var validationSpinner: ImageView
    private lateinit var validationStatusText: TextView
    private lateinit var validationTitleText: TextView
    private lateinit var settingsButton: MaterialButton
    private lateinit var menuButton: ImageButton
    
    private lateinit var uploadRotationHelper: RotationHelper
    private lateinit var validationRotationHelper: RotationHelper
    
    private var fileUri: Uri? = null
    private var originalFilename: String? = null
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }
    
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // Settings were saved, refresh validation
            if (fileUri == null) {
                showValidationScreen()
            }
        }
    }
    
    companion object {
        private const val PREF_ADD_SUFFIX = "add_suffix"
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
        
        // Handle window insets for status bar and navigation bar
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply top padding to header
            headerView.updatePadding(top = insets.top + 20)
            WindowInsetsCompat.CONSUMED
        }
        
        filenameEdit = findViewById(R.id.filenameEdit)
        suffixText = findViewById(R.id.suffixText)
        uploadButton = findViewById(R.id.uploadButton)
        cancelButton = findViewById(R.id.cancelButton)
        uploadSpinner = findViewById(R.id.uploadSpinner)
        statusText = findViewById(R.id.statusText)
        
        validationLayout = findViewById(R.id.validationLayout)
        uploaderLayout = findViewById(R.id.uploaderLayout)
        validationSpinner = findViewById(R.id.validationSpinner)
        validationStatusText = findViewById(R.id.validationStatusText)
        validationTitleText = findViewById(R.id.validationTitleText)
        settingsButton = findViewById(R.id.settingsButton)
        menuButton = findViewById(R.id.menuButton)
        
        uploadRotationHelper = RotationHelper(uploadSpinner)
        validationRotationHelper = RotationHelper(validationSpinner)
        
        // Set up header menu button (always visible) and settings button click listeners
        menuButton.setOnClickListener {
            openSettings()
        }
        settingsButton.setOnClickListener {
            openSettings()
        }
        
        // Check if we have settings configured and account connected
        val serverUrl = normalizeServerUrl(GeovaultAuthManager.getServerUrl(this))
        if (serverUrl.isEmpty() || !GeovaultAuthManager.isLoggedIn(this)) {
            openSettings()
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
        val serverUrl = normalizeServerUrl(GeovaultAuthManager.getServerUrl(this))
        if (serverUrl.isEmpty() || !GeovaultAuthManager.isLoggedIn(this)) {
            openSettings()
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
        uploadSpinner.visibility = View.VISIBLE
        uploadRotationHelper.start()
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
            
            // Use auth-aware client so token is refreshed on 401 (avoids 401 after access token expiry)
            val client = RetrofitClient.getAuthenticatedOkHttpClient(this)
                .newBuilder()
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
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        uploadRotationHelper.stop()
                        uploadSpinner.visibility = View.GONE
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
                        if (isDestroyed) return@runOnUiThread
                        uploadRotationHelper.stop()
                        uploadSpinner.visibility = View.GONE
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
                            
                            if (statusCode == 401) {
                                resetOnAuthFailure(this@MainActivity)
                                return@runOnUiThread
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
            uploadRotationHelper.stop()
            uploadSpinner.visibility = View.GONE
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
        val serverUrl = normalizeServerUrl(GeovaultAuthManager.getServerUrl(this))
        if (serverUrl.isEmpty() || !GeovaultAuthManager.isLoggedIn(this)) {
            validationTitleText.text = getString(R.string.config_required)
            validationStatusText.text = getString(R.string.config_settings_first)
            validationRotationHelper.stop()
            validationSpinner.visibility = android.view.View.GONE
            return
        }

        validationTitleText.text = getString(R.string.validating_key)
        validationSpinner.visibility = android.view.View.VISIBLE
        validationRotationHelper.start()
        validationStatusText.text = getString(R.string.connecting_server)

        val client = RetrofitClient.getAuthenticatedOkHttpClient(this)
            .newBuilder()
            .retryOnConnectionFailure(true)
            .build()

        val statusUrl = "$serverUrl/api/user/status/"
        val request = Request.Builder()
            .url(statusUrl)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    validationTitleText.text = getString(R.string.validation_failed)
                    validationRotationHelper.stop()
                    validationSpinner.visibility = android.view.View.GONE
                    val errorMsg = e.message ?: "Unknown error"
                    val cleanErrorMsg = errorMsg.replace(Regex("^Failed to connect to /"), "Failed to connect to ")
                    val displayMsg = if (cleanErrorMsg.contains("end of stream", ignoreCase = true)) {
                        "✗ Connection closed unexpectedly\n\nTap Settings to retry."
                    } else {
                        "✗ Connection failed\n\n$cleanErrorMsg\n\nCheck your server URL and network connection."
                    }
                    validationStatusText.text = displayMsg
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = try { response.body?.string() ?: "" } catch (e: Exception) { "" }
                val statusCode = response.code
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    validationRotationHelper.stop()
                    validationSpinner.visibility = android.view.View.GONE
                    if (response.isSuccessful) {
                        validationTitleText.text = getString(R.string.api_key_valid)
                        validationStatusText.text = getString(R.string.api_key_valid_msg)
                    } else {
                        if (statusCode == 401) {
                            response.close()
                            resetOnAuthFailure(this@MainActivity)
                            return@runOnUiThread
                        }
                        validationTitleText.text = getString(R.string.validation_failed)
                        val msg = when (statusCode) {
                            401 -> "✗ Unauthorized.\n\nReconnect in Settings."
                            404 -> "✗ Not found.\n\nCheck your server URL."
                            else -> "✗ Request failed ($statusCode)"
                        }
                        validationStatusText.text = msg
                    }
                    response.close()
                }
            }
        })
    }
    
    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        settingsLauncher.launch(intent)
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

