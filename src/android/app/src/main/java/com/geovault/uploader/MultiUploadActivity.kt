package com.geovault.uploader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class MultiUploadActivity : AppCompatActivity() {
    private lateinit var filesRecyclerView: RecyclerView
    private lateinit var fileCountText: TextView
    private lateinit var uploadProgressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var uploadAllButton: Button
    private lateinit var cancelButton: Button
    
    private lateinit var adapter: FileQueueAdapter
    private val files = mutableListOf<FileItem>()
    
    private var isUploading = false
    private var currentUploadIndex = 0
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }
    
    companion object {
        private const val PREF_SERVER_URL = "server_url"
        private const val PREF_API_KEY = "api_key"
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
        setContentView(R.layout.activity_multi_upload)
        
        filesRecyclerView = findViewById(R.id.filesRecyclerView)
        fileCountText = findViewById(R.id.fileCountText)
        uploadProgressBar = findViewById(R.id.uploadProgressBar)
        statusText = findViewById(R.id.statusText)
        uploadAllButton = findViewById(R.id.uploadAllButton)
        cancelButton = findViewById(R.id.cancelButton)
        
        // Setup RecyclerView
        adapter = FileQueueAdapter(files)
        filesRecyclerView.layoutManager = LinearLayoutManager(this)
        filesRecyclerView.adapter = adapter
        
        uploadAllButton.setOnClickListener {
            startUploadQueue()
        }
        
        cancelButton.setOnClickListener {
            if (!isUploading) {
                finish()
            }
        }
        
        // Handle the share intent
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            
            uris?.forEach { uri ->
                val filename = getFilenameFromUri(uri)
                val size = getFileSizeFromUri(uri)
                files.add(FileItem(uri, filename, size))
            }
            
            updateFileCount()
            adapter.notifyDataSetChanged()
        }
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
            val path = uri.path
            if (path != null) {
                filename = File(path).name
            }
        }
        return filename
    }
    
    private fun getFileSizeFromUri(uri: Uri): Long {
        var size = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Fallback: try to open and measure
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    size = stream.available().toLong()
                }
            } catch (e: Exception) {
                // Leave as 0
            }
        }
        return size
    }
    
    private fun updateFileCount() {
        fileCountText.text = "${files.size} file${if (files.size != 1) "s" else ""}"
    }
    
    private fun startUploadQueue() {
        if (isUploading) return
        
        val serverUrl = normalizeServerUrl(prefs.getString(PREF_SERVER_URL, "") ?: "")
        val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
        
        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            statusText.text = "Please configure settings first"
            statusText.visibility = View.VISIBLE
            return
        }
        
        isUploading = true
        currentUploadIndex = 0
        uploadAllButton.isEnabled = false
        cancelButton.text = "Close"
        uploadProgressBar.visibility = View.VISIBLE
        uploadProgressBar.max = files.size
        uploadProgressBar.progress = 0
        
        uploadNextFile()
    }
    
    private fun uploadNextFile() {
        if (currentUploadIndex >= files.size) {
            // All done
            finishUpload()
            return
        }
        
        val file = files[currentUploadIndex]
        adapter.updateFileStatus(currentUploadIndex, FileStatus.UPLOADING)
        
        statusText.text = "Uploading ${currentUploadIndex + 1}/${files.size}..."
        statusText.visibility = View.VISIBLE
        
        uploadFile(file, currentUploadIndex)
    }
    
    private fun uploadFile(fileItem: FileItem, index: Int) {
        val serverUrl = normalizeServerUrl(prefs.getString(PREF_SERVER_URL, "") ?: "")
        val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
        val addSuffix = prefs.getBoolean(PREF_ADD_SUFFIX, true)
        
        // Apply suffix if enabled
        val finalFilename = if (addSuffix) {
            val (nameWithoutExt, extension) = splitFilename(fileItem.filename)
            val suffix = "_android_upload"
            if (extension.isNotEmpty()) {
                "${nameWithoutExt}${suffix}.$extension"
            } else {
                "${fileItem.filename}${suffix}"
            }
        } else {
            fileItem.filename
        }
        
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(fileItem.uri)
            if (inputStream == null) {
                adapter.updateFileStatus(index, FileStatus.ERROR, "Could not read file")
                currentUploadIndex++
                uploadNextFile()
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
                        adapter.updateFileStatus(index, FileStatus.ERROR, "Connection failed")
                        uploadProgressBar.progress = index + 1
                        currentUploadIndex++
                        uploadNextFile()
                    }
                    tempFile.delete()
                }
                
                override fun onResponse(call: Call, response: Response) {
                    val success = response.isSuccessful
                    val errorMsg = if (!success) {
                        try {
                            val body = response.body?.string() ?: ""
                            if (body.isNotEmpty() && body.trimStart().startsWith("{")) {
                                val json = org.json.JSONObject(body)
                                json.optString("error", "Upload failed (${response.code})")
                            } else {
                                "Upload failed (${response.code})"
                            }
                        } catch (e: Exception) {
                            "Upload failed (${response.code})"
                        }
                    } else null
                    
                    runOnUiThread {
                        if (success) {
                            adapter.updateFileStatus(index, FileStatus.SUCCESS)
                        } else {
                            adapter.updateFileStatus(index, FileStatus.ERROR, errorMsg)
                        }
                        uploadProgressBar.progress = index + 1
                        currentUploadIndex++
                        uploadNextFile()
                    }
                    
                    tempFile.delete()
                    response.close()
                }
            })
        } catch (e: Exception) {
            adapter.updateFileStatus(index, FileStatus.ERROR, "Error: ${e.message}")
            currentUploadIndex++
            uploadNextFile()
        }
    }
    
    private fun splitFilename(filename: String): Pair<String, String> {
        val lastDotIndex = filename.lastIndexOf('.')
        return if (lastDotIndex > 0 && lastDotIndex < filename.length - 1) {
            Pair(filename.substring(0, lastDotIndex), filename.substring(lastDotIndex + 1))
        } else {
            Pair(filename, "")
        }
    }
    
    private fun finishUpload() {
        isUploading = false
        
        val successCount = files.count { it.status == FileStatus.SUCCESS }
        val errorCount = files.count { it.status == FileStatus.ERROR }
        
        statusText.text = when {
            errorCount == 0 -> "✓ All $successCount files uploaded successfully!"
            successCount == 0 -> "✗ All $errorCount files failed to upload"
            else -> "Upload complete: $successCount succeeded, $errorCount failed"
        }
        
        uploadAllButton.visibility = View.GONE
        
        // Auto-close if all succeeded
        if (errorCount == 0) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                finish()
            }, 2500)
        }
    }
}

