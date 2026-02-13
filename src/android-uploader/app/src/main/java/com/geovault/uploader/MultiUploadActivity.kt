package com.geovault.uploader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
    private lateinit var menuButton: ImageButton
    
    private lateinit var adapter: FileQueueAdapter
    private val files = mutableListOf<FileItem>()
    
    private var isUploading = false
    private var currentUploadIndex = 0
    private var currentCall: okhttp3.Call? = null
    private var isCancelled = false
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }
    
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Settings were saved, but we don't need to do anything special here
        // The upload will use the new settings automatically
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
        
        // Handle window insets for status bar and navigation bar
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        val bottomView = findViewById<View>(R.id.bottomLayout)
        
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply top padding to header
            headerView.updatePadding(top = insets.top + 20)
            // Apply bottom padding to bottom action area
            bottomView.updatePadding(bottom = insets.bottom + 20)
            WindowInsetsCompat.CONSUMED
        }
        uploadProgressBar = findViewById(R.id.uploadProgressBar)
        statusText = findViewById(R.id.statusText)
        uploadAllButton = findViewById(R.id.uploadAllButton)
        cancelButton = findViewById(R.id.cancelButton)
        menuButton = findViewById(R.id.menuButton)
        
        // Setup RecyclerView
        adapter = FileQueueAdapter(files)
        filesRecyclerView.layoutManager = LinearLayoutManager(this)
        filesRecyclerView.adapter = adapter
        
        // Setup menu button
        menuButton.setOnClickListener {
            showMenu(it)
        }
        
        uploadAllButton.setOnClickListener {
            startUploadQueue()
        }
        
        cancelButton.setOnClickListener {
            if (isUploading) {
                // Cancel the upload process
                cancelUpload()
            } else {
                // Close the app if not uploading
                finish()
            }
        }
        
        // Handle the share intent
        handleIntent(intent)
    }
    
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.upload_menu, popup.menu)
        
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.menu_settings -> {
                    openSettings()
                    true
                }
                else -> false
            }
        }
        
        popup.show()
    }
    
    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        settingsLauncher.launch(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                
                uris?.forEach { uri ->
                    val filename = getFilenameFromUri(uri)
                    val size = getFileSizeFromUri(uri)
                    
                    if (isValidFileType(filename)) {
                        files.add(FileItem(uri, filename, size))
                    } else {
                        // Add invalid file to list with error status
                        val invalidFile = FileItem(
                            uri = uri,
                            filename = filename,
                            size = size,
                            status = FileStatus.ERROR,
                            errorMessage = "Invalid file type. Only KMZ, KML, and GPX files are allowed."
                        )
                        files.add(invalidFile)
                    }
                }
                
                updateFileCount()
                adapter.notifyDataSetChanged()
            }
            Intent.ACTION_SEND -> {
                // Handle single file share - add it to the list just like multiple files
                val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                
                uri?.let {
                    val filename = getFilenameFromUri(it)
                    val size = getFileSizeFromUri(it)
                    
                    if (isValidFileType(filename)) {
                        files.add(FileItem(it, filename, size))
                    } else {
                        // Add invalid file to list with error status
                        val invalidFile = FileItem(
                            uri = it,
                            filename = filename,
                            size = size,
                            status = FileStatus.ERROR,
                            errorMessage = "Invalid file type. Only KMZ, KML, and GPX files are allowed."
                        )
                        files.add(invalidFile)
                    }
                    
                    updateFileCount()
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }
    
    private fun isValidFileType(filename: String): Boolean {
        val extension = getFileExtension(filename).lowercase()
        return extension in listOf("kmz", "kml", "gpx")
    }
    
    private fun getFileExtension(filename: String): String {
        val lastDotIndex = filename.lastIndexOf('.')
        return if (lastDotIndex > 0 && lastDotIndex < filename.length - 1) {
            filename.substring(lastDotIndex + 1)
        } else {
            ""
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
        val validFilesCount = files.count { 
            it.status != FileStatus.ERROR || 
            it.errorMessage?.contains("Invalid file type", ignoreCase = true) != true 
        }
        
        fileCountText.text = "${files.size} file${if (files.size != 1) "s" else ""}"
        
        // Show/hide upload button based on whether there are valid files
        uploadAllButton.visibility = if (validFilesCount == 0) View.GONE else View.VISIBLE
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
        
        // Count only valid files (exclude invalid file types)
        val validFilesCount = files.count { 
            it.status != FileStatus.ERROR || 
            it.errorMessage?.contains("Invalid file type", ignoreCase = true) != true 
        }
        
        if (validFilesCount == 0) {
            statusText.text = "No valid files to upload"
            statusText.visibility = View.VISIBLE
            return
        }
        
        isUploading = true
        isCancelled = false
        currentUploadIndex = 0
        uploadAllButton.visibility = View.GONE
        cancelButton.text = getString(R.string.cancel_button)
        
        // Only show progress bar if there are 2+ valid files
        if (validFilesCount > 1) {
            uploadProgressBar.visibility = View.VISIBLE
            uploadProgressBar.max = validFilesCount
            uploadProgressBar.progress = 0
        } else {
            uploadProgressBar.visibility = View.GONE
        }
        
        uploadNextFile()
    }
    
    private fun uploadNextFile() {
        if (isCancelled) {
            // Upload was cancelled, stop processing
            finishUpload()
            return
        }
        
        // Skip invalid files and find next valid file to upload
        while (currentUploadIndex < files.size) {
            val file = files[currentUploadIndex]
            
            // Skip files with invalid file type errors
            val isInvalidFileType = file.status == FileStatus.ERROR && 
                (file.errorMessage?.contains("Invalid file type", ignoreCase = true) == true)
            
            if (isInvalidFileType) {
                currentUploadIndex++
                continue
            }
            
            // Found a valid file, upload it
            adapter.updateFileStatus(currentUploadIndex, FileStatus.UPLOADING)
            
            // Count valid files (exclude invalid file types)
            val validFileCount = files.count { 
                it.status != FileStatus.ERROR || 
                it.errorMessage?.contains("Invalid file type", ignoreCase = true) != true 
            }
            
            // Count files that have been processed (uploaded successfully or failed upload, but not invalid file types)
            val uploadedCount = files.take(currentUploadIndex).count { 
                val isInvalidType = it.status == FileStatus.ERROR && 
                    (it.errorMessage?.contains("Invalid file type", ignoreCase = true) == true)
                !isInvalidType && (it.status == FileStatus.SUCCESS || it.status == FileStatus.ERROR)
            }
            
            statusText.text = "Uploading ${uploadedCount + 1}/$validFileCount..."
            statusText.visibility = View.VISIBLE
            
            uploadFile(file, currentUploadIndex)
            return
        }
        
        // All valid files done
        finishUpload()
    }
    
    private fun cancelUpload() {
        isCancelled = true
        isUploading = false
        
        // Cancel the current HTTP call if it exists
        currentCall?.cancel()
        currentCall = null
        
        // Mark current file as cancelled if it's uploading
        if (currentUploadIndex < files.size) {
            val currentFile = files[currentUploadIndex]
            if (currentFile.status == FileStatus.UPLOADING) {
                adapter.updateFileStatus(currentUploadIndex, FileStatus.PENDING)
            }
        }
        
        // Mark all pending files as cancelled
        for (i in currentUploadIndex until files.size) {
            if (files[i].status == FileStatus.PENDING) {
                adapter.updateFileStatus(i, FileStatus.PENDING)
            }
        }
        
        statusText.text = "Upload cancelled"
        statusText.visibility = View.VISIBLE
        uploadProgressBar.visibility = View.GONE
        uploadAllButton.visibility = View.VISIBLE
        cancelButton.text = getString(R.string.cancel_button)
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
            
            val call = client.newCall(request)
            currentCall = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    runOnUiThread {
                        if (isCancelled) {
                            adapter.updateFileStatus(index, FileStatus.PENDING)
                        } else {
                            adapter.updateFileStatus(index, FileStatus.ERROR, "Connection failed")
                            uploadProgressBar.progress = index + 1
                            currentUploadIndex++
                            uploadNextFile()
                        }
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
                        if (isCancelled) {
                            adapter.updateFileStatus(index, FileStatus.PENDING)
                        } else {
                            if (success) {
                                adapter.updateFileStatus(index, FileStatus.SUCCESS)
                            } else {
                                adapter.updateFileStatus(index, FileStatus.ERROR, errorMsg)
                            }
                            uploadProgressBar.progress = index + 1
                            currentUploadIndex++
                            uploadNextFile()
                        }
                    }
                    
                    tempFile.delete()
                    response.close()
                }
            })
        } catch (e: Exception) {
            runOnUiThread {
                if (isCancelled) {
                    adapter.updateFileStatus(index, FileStatus.PENDING)
                } else {
                    adapter.updateFileStatus(index, FileStatus.ERROR, "Error: ${e.message}")
                    currentUploadIndex++
                    uploadNextFile()
                }
            }
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
        currentCall = null
        
        val successCount = files.count { it.status == FileStatus.SUCCESS }
        // Count only upload errors, not invalid file type errors
        val errorCount = files.count { 
            it.status == FileStatus.ERROR && 
            it.errorMessage?.contains("Invalid file type", ignoreCase = true) != true 
        }
        
        if (isCancelled) {
            statusText.text = "Upload cancelled"
        } else {
            statusText.text = when {
                errorCount == 0 -> "✓ All $successCount files uploaded successfully!"
                successCount == 0 -> "✗ All $errorCount files failed to upload"
                else -> "Upload complete: $successCount succeeded, $errorCount failed"
            }
        }
        
        uploadAllButton.visibility = View.GONE
        uploadProgressBar.visibility = View.GONE
        
        // Change cancel button to Close after upload completes
        cancelButton.isEnabled = true
        cancelButton.text = "Close"
    }
}


