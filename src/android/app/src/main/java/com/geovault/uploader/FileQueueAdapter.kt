package com.geovault.uploader

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class FileItem(
    val uri: android.net.Uri,
    var filename: String,
    val size: Long,
    var status: FileStatus = FileStatus.PENDING,
    var errorMessage: String? = null
)

enum class FileStatus {
    PENDING,
    UPLOADING,
    SUCCESS,
    ERROR
}

class FileQueueAdapter(
    private val files: MutableList<FileItem>
) : RecyclerView.Adapter<FileQueueAdapter.FileViewHolder>() {

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val statusIcon: TextView = itemView.findViewById(R.id.fileStatusIcon)
        val filenameEdit: EditText = itemView.findViewById(R.id.filenameEditText)
        val fileExtensionText: TextView = itemView.findViewById(R.id.fileExtensionText)
        val fileSizeText: TextView = itemView.findViewById(R.id.fileSizeText)
        val errorText: TextView = itemView.findViewById(R.id.fileErrorText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_queue, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        
        // Set status icon and color
        when (file.status) {
            FileStatus.PENDING -> {
                // Keep space reserved but hide icon
                holder.statusIcon.visibility = View.INVISIBLE
            }
            FileStatus.UPLOADING -> {
                holder.statusIcon.visibility = View.VISIBLE
                holder.statusIcon.text = "↑"
                holder.statusIcon.setTextColor(holder.itemView.context.getColor(R.color.primary_blue))
            }
            FileStatus.SUCCESS -> {
                holder.statusIcon.visibility = View.VISIBLE
                holder.statusIcon.text = "✓"
                holder.statusIcon.setTextColor(holder.itemView.context.getColor(R.color.success_green))
            }
            FileStatus.ERROR -> {
                holder.statusIcon.visibility = View.VISIBLE
                holder.statusIcon.text = "✗"
                holder.statusIcon.setTextColor(holder.itemView.context.getColor(R.color.error_red))
            }
        }
        
        // Split filename into base name and extension
        val (baseName, extension) = splitFilename(file.filename)
        
        // Set filename (editable only if pending) - only the base name
        holder.filenameEdit.setText(baseName)
        holder.fileExtensionText.text = if (extension.isNotEmpty()) ".$extension" else ""
        holder.filenameEdit.isEnabled = file.status == FileStatus.PENDING
        
        // Update filename when edited - reconstruct with extension
        holder.filenameEdit.removeTextChangedListener(holder.filenameEdit.tag as? TextWatcher)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val currentPosition = holder.bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    val newBaseName = s.toString()
                    // Reconstruct filename with original extension
                    val (_, ext) = splitFilename(files[currentPosition].filename)
                    files[currentPosition].filename = if (ext.isNotEmpty()) {
                        "$newBaseName.$ext"
                    } else {
                        newBaseName
                    }
                }
            }
        }
        holder.filenameEdit.addTextChangedListener(watcher)
        holder.filenameEdit.tag = watcher
        
        // Set file size
        holder.fileSizeText.text = formatFileSize(file.size)
        
        // Show error if present
        if (file.errorMessage != null) {
            holder.errorText.text = file.errorMessage
            holder.errorText.visibility = View.VISIBLE
        } else {
            holder.errorText.visibility = View.GONE
        }
    }

    override fun getItemCount() = files.size

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

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    fun updateFileStatus(position: Int, status: FileStatus, errorMessage: String? = null) {
        if (position < files.size) {
            files[position].status = status
            files[position].errorMessage = errorMessage
            notifyItemChanged(position)
        }
    }
}

