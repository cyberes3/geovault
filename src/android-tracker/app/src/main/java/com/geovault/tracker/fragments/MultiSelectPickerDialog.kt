package com.geovault.tracker.fragments

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.geovault.tracker.R
import com.google.android.material.card.MaterialCardView

/**
 * Generic multiselect dialog for choosing items by id. Used for "share with users" and "add tracker to group".
 *
 * @param items List of (id, displayLabel)
 * @param initialSelectedIds Pre-selected ids (e.g. current shared-with emails or empty for add-tracker)
 * @param onApply Called with the set of selected ids when user taps OK
 */
object MultiSelectPickerDialog {

    fun show(
        fragment: Fragment,
        title: String,
        items: List<Pair<String, String>>,
        initialSelectedIds: Set<String>,
        hintText: String,
        emptyText: String,
        onApply: (Set<String>) -> Unit
    ) {
        val context = fragment.requireContext()
        val selectedIds = initialSelectedIds.toMutableSet()
        val sortedItems = items.distinctBy { it.first }.sortedBy { it.second.lowercase() }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_shared_user_picker, null, false)
        val searchInput = view.findViewById<EditText>(R.id.sharedUserPickerSearch)
        val hintView = view.findViewById<TextView>(R.id.sharedUserPickerHint)
        val recyclerView = view.findViewById<RecyclerView>(R.id.sharedUserPickerRecycler)
        val emptyView = view.findViewById<TextView>(R.id.sharedUserPickerEmpty)

        hintView.text = hintText
        emptyView.text = emptyText
        recyclerView.layoutManager = LinearLayoutManager(context)
        val adapter = PickerAdapter(sortedItems, selectedIds)
        recyclerView.adapter = adapter

        fun updateVisibility() {
            val hasItems = adapter.filteredItemCount > 0
            emptyView.visibility = if (hasItems) View.GONE else View.VISIBLE
            recyclerView.visibility = if (hasItems) View.VISIBLE else View.GONE
        }
        updateVisibility()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.setFilter(s?.toString() ?: "")
                updateVisibility()
            }
        })

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.cancel_button, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onApply(selectedIds)
            }
            .show()

        dialog.window?.let { window ->
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.75).toInt()
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, maxHeight)
        }
    }

    private class PickerAdapter(
        private val items: List<Pair<String, String>>,
        private val selectedIds: MutableSet<String>
    ) : RecyclerView.Adapter<PickerAdapter.ViewHolder>() {

        private var filterQuery: String = ""

        private val filteredItems: List<Pair<String, String>>
            get() = if (filterQuery.isBlank()) items
            else items.filter { it.second.contains(filterQuery, ignoreCase = true) }

        val filteredItemCount: Int get() = filteredItems.size

        fun setFilter(query: String) {
            if (filterQuery == query) return
            filterQuery = query
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_shared_user_picker_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (id, label) = filteredItems[position]
            val selected = id in selectedIds
            holder.bind(label, selected)
            holder.itemView.setOnClickListener {
                if (selected) selectedIds.remove(id) else selectedIds.add(id)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount(): Int = filteredItems.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val card: MaterialCardView = itemView.findViewById(R.id.sharedUserPickerRowCard)
            private val label: TextView = itemView.findViewById(R.id.sharedUserPickerRowEmail)
            private val check: ImageView = itemView.findViewById(R.id.sharedUserPickerRowCheck)

            fun bind(displayLabel: String, selected: Boolean) {
                val context = itemView.context
                label.text = displayLabel
                check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                card.setCardBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.blue_extra_light else R.color.surface
                    )
                )
                label.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.primary_blue else R.color.text_primary
                    )
                )
            }
        }
    }
}
