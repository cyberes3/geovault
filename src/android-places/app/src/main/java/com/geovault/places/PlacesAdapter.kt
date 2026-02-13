package com.geovault.places

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import androidx.recyclerview.widget.RecyclerView

class PlacesAdapter(
    private var places: List<Feature>,
    private var offlinePlaces: List<Feature>,
    private var offlineFeatures: List<OfflineFeature>,
    private val onNavigate: (Feature) -> Unit,
    private val onEdit: (Feature) -> Unit,
    private val onEditOffline: (OfflineFeature) -> Unit,
    private val onDelete: (Feature) -> Unit,
    private val onRevertOffline: (OfflineFeature) -> Unit,
    private val onViewDescription: (placeName: String, description: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var selectedId: Int? = null

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_OFFLINE_ITEM = 2
    }

    fun updateData(newPlaces: List<Feature>, newOfflinePlaces: List<Feature>, newOfflineFeatures: List<OfflineFeature>) {
        // Filter out places that have pending offline edits
        val offlineEditIds = newOfflineFeatures.mapNotNull { it.feature.properties.database_id }.toSet()
        places = newPlaces.filter { it.properties.database_id !in offlineEditIds }
        offlinePlaces = newOfflinePlaces
        offlineFeatures = newOfflineFeatures
        notifyDataSetChanged()
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(android.R.id.text1)
    }

    class PlaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardContent: View = itemView.findViewById(R.id.cardContent)
        val nameText: TextView = itemView.findViewById(R.id.placeName)
        val dateText: TextView = itemView.findViewById(R.id.placeDate)
        val coordinatesText: TextView = itemView.findViewById(R.id.placeCoordinates)
        val descriptionText: TextView = itemView.findViewById(R.id.placeDescription)
        val navigateButton: Button = itemView.findViewById(R.id.navigateButton)
        val editButton: Button = itemView.findViewById(R.id.editButton)
        val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
    }

    override fun getItemViewType(position: Int): Int {
        if (offlinePlaces.isNotEmpty()) {
            if (position == 0) return TYPE_HEADER
            if (position <= offlinePlaces.size) return TYPE_OFFLINE_ITEM
            if (position == offlinePlaces.size + 1) return TYPE_HEADER
        }
        return TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return HeaderViewHolder(view)
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_place, parent, false)
        return PlaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.itemView.setPadding(32, 32, 32, 8)
            holder.title.textSize = 14f
            holder.title.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
            if (offlinePlaces.isNotEmpty()) {
                holder.title.text = if (position == 0) "WAITING TO SYNC" else "SAVED PLACES"
            } else {
                holder.title.text = "SAVED PLACES"
            }
            return
        }

        val placeViewHolder = holder as PlaceViewHolder
        val isOffline = getItemViewType(position) == TYPE_OFFLINE_ITEM
        val place = if (isOffline) {
            offlinePlaces[position - 1]
        } else {
            val offset = if (offlinePlaces.isNotEmpty()) offlinePlaces.size + 2 else 0
            places[position - offset]
        }
        holder.nameText.text = place.properties.name ?: "Unnamed Place"
        holder.nameText.setTextIsSelectable(true)
        
        // Format date (assuming YYYY-MM-DDTHH:MM:SSZ or similar)
        val rawDate = place.properties.created_at ?: ""
        val formattedDate = if (rawDate.length >= 10) rawDate.substring(0, 10) else rawDate
        
        if (isOffline) {
            holder.dateText.text = "$formattedDate (offline)"
            holder.dateText.setTextColor(holder.itemView.context.getColor(R.color.warning_yellow))
        } else {
            holder.dateText.text = formattedDate
            holder.dateText.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
        }
        holder.dateText.setTextIsSelectable(true)
        
        // Format coordinates
        val coords = place.geometry.coordinates
        if (coords.size >= 2) {
            holder.coordinatesText.text = String.format("%.6f, %.6f", coords[1], coords[0])
        } else {
            holder.coordinatesText.text = ""
        }
        
        val description = place.properties.description ?: "No description"
        holder.descriptionText.text = description
        holder.descriptionText.setOnClickListener {
            onViewDescription(place.properties.name ?: "Unnamed Place", description)
        }

        // Highlight selection or offline status
        val hasPendingOfflineEdit = !isOffline && place.properties.database_id != null && 
            offlinePlaces.any { it.properties.database_id == place.properties.database_id }
        
        if (isOffline) {
            placeViewHolder.cardContent.setBackgroundResource(R.drawable.bg_offline_item)
            // Show navigate button for offline items that have a database_id (edits)
            if (place.properties.database_id != null) {
                placeViewHolder.navigateButton.visibility = View.VISIBLE
                placeViewHolder.deleteButton.visibility = View.VISIBLE
                placeViewHolder.deleteButton.text = "Revert"
                placeViewHolder.deleteButton.setTextColor(holder.itemView.context.getColor(R.color.warning_yellow))
            } else {
                placeViewHolder.navigateButton.visibility = View.GONE
                placeViewHolder.deleteButton.visibility = View.VISIBLE
                placeViewHolder.deleteButton.text = "Discard"
                placeViewHolder.deleteButton.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
            }
            placeViewHolder.editButton.visibility = View.VISIBLE
            placeViewHolder.editButton.isEnabled = true
            placeViewHolder.editButton.alpha = 1.0f
        } else if (selectedId != null && place.properties.database_id == selectedId) {
            placeViewHolder.cardContent.setBackgroundResource(R.drawable.bg_place_item_selected)
            placeViewHolder.navigateButton.visibility = View.VISIBLE
            placeViewHolder.editButton.visibility = View.VISIBLE
            placeViewHolder.deleteButton.visibility = View.VISIBLE
            placeViewHolder.deleteButton.text = "Delete"
            placeViewHolder.deleteButton.setTextColor(holder.itemView.context.getColor(android.R.color.holo_red_dark))
            placeViewHolder.editButton.isEnabled = true
            placeViewHolder.editButton.alpha = 1.0f
        } else {
            placeViewHolder.cardContent.background = null
            placeViewHolder.navigateButton.visibility = View.VISIBLE
            placeViewHolder.editButton.visibility = View.VISIBLE
            placeViewHolder.deleteButton.visibility = View.VISIBLE
            placeViewHolder.deleteButton.text = "Delete"
            placeViewHolder.deleteButton.setTextColor(holder.itemView.context.getColor(android.R.color.holo_red_dark))
            placeViewHolder.editButton.isEnabled = true
            placeViewHolder.editButton.alpha = 1.0f
        }
        
        holder.navigateButton.setOnClickListener {
            onNavigate(place)
        }
        
        holder.editButton.setOnClickListener {
            if (isOffline) {
                // Find the OfflineFeature wrapper
                val offlineFeature = offlineFeatures.getOrNull(position - 1)
                if (offlineFeature != null) {
                    onEditOffline(offlineFeature)
                }
            } else {
                onEdit(place)
            }
        }
        
        holder.deleteButton.setOnClickListener {
            if (isOffline) {
                val offlineFeature = offlineFeatures.getOrNull(position - 1)
                if (offlineFeature != null) {
                    val context = holder.itemView.context
                    val action = if (offlineFeature.feature.properties.database_id != null) {
                        "revert your changes to"
                    } else {
                        "discard"
                    }
                    val buttonText = if (offlineFeature.feature.properties.database_id != null) "Revert" else "Discard"
                    
                    android.app.AlertDialog.Builder(context)
                        .setTitle("${buttonText} Changes")
                        .setMessage("Are you sure you want to $action '${place.properties.name ?: "this place"}'?")
                        .setPositiveButton(buttonText) { _, _ ->
                            onRevertOffline(offlineFeature)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else {
                val context = holder.itemView.context
                android.app.AlertDialog.Builder(context)
                    .setTitle("Delete Place")
                    .setMessage("Are you sure you want to delete '${place.properties.name ?: "this place"}'? This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        onDelete(place)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        
        holder.coordinatesText.setOnClickListener {
            val coordsText = holder.coordinatesText.text.toString()
            if (coordsText.isNotEmpty()) {
                val context = holder.itemView.context
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Coordinates", coordsText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Coordinates copied: $coordsText", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount(): Int {
        var count = places.size
        if (offlinePlaces.isNotEmpty()) {
            count += offlinePlaces.size + 2 // 2 headers + offline items
        }
        return count
    }
}
