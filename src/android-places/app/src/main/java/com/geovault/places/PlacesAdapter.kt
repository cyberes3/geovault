package com.geovault.places

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlacesAdapter(
    private val places: List<Feature>,
    private val onNavigate: (Feature) -> Unit
) : RecyclerView.Adapter<PlacesAdapter.PlaceViewHolder>() {

    var selectedId: Int? = null

    class PlaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardContent: View = itemView.findViewById(R.id.cardContent)
        val nameText: TextView = itemView.findViewById(R.id.placeName)
        val descriptionText: TextView = itemView.findViewById(R.id.placeDescription)
        val navigateButton: Button = itemView.findViewById(R.id.navigateButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_place, parent, false)
        return PlaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        val place = places[position]
        holder.nameText.text = place.properties.name ?: "Unnamed Place"
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
    }

    override fun getItemCount() = places.size
}
