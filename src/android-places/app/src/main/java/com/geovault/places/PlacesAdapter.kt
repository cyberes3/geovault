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
    private val places: List<Feature>,
    private val onNavigate: (Feature) -> Unit,
    private val onEdit: (Feature) -> Unit
) : RecyclerView.Adapter<PlacesAdapter.PlaceViewHolder>() {

    var selectedId: Int? = null

    class PlaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardContent: View = itemView.findViewById(R.id.cardContent)
        val nameText: TextView = itemView.findViewById(R.id.placeName)
        val dateText: TextView = itemView.findViewById(R.id.placeDate)
        val coordinatesText: TextView = itemView.findViewById(R.id.placeCoordinates)
        val descriptionText: TextView = itemView.findViewById(R.id.placeDescription)
        val navigateButton: Button = itemView.findViewById(R.id.navigateButton)
        val editButton: Button = itemView.findViewById(R.id.editButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_place, parent, false)
        return PlaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        val place = places[position]
        holder.nameText.text = place.properties.name ?: "Unnamed Place"
        
        // Format date (assuming YYYY-MM-DDTHH:MM:SSZ or similar)
        val rawDate = place.properties.created_at ?: ""
        holder.dateText.text = if (rawDate.length >= 10) rawDate.substring(0, 10) else rawDate
        
        // Format coordinates
        val coords = place.geometry.coordinates
        if (coords.size >= 2) {
            holder.coordinatesText.text = String.format("%.6f, %.6f", coords[1], coords[0])
        } else {
            holder.coordinatesText.text = ""
        }
        
        holder.descriptionText.text = place.properties.description ?: "No description"
        
        // Highlight selection
        if (selectedId != null && place.properties.database_id == selectedId) {
            holder.cardContent.setBackgroundResource(R.drawable.bg_place_item_selected)
        } else {
            holder.cardContent.background = null
        }
        
        holder.navigateButton.setOnClickListener {
            onNavigate(place)
        }
        
        holder.editButton.setOnClickListener {
            onEdit(place)
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

    override fun getItemCount() = places.size
}
