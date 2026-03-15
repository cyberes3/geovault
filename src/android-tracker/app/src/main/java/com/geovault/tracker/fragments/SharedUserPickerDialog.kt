package com.geovault.tracker.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.geovault.tracker.R
import com.geovault.tracker.UserItem
import com.google.android.material.card.MaterialCardView
import java.util.Locale

object SharedUserPickerDialog {

    private data class PickerUser(val email: String)

    fun show(
        fragment: Fragment,
        title: String,
        users: List<UserItem>,
        selectedEmails: Set<String>,
        onApply: (Set<String>) -> Unit
    ) {
        val context = fragment.requireContext()
        val normalizedSelected = selectedEmails
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotBlank() }
            .toMutableSet()
        val items = users
            .map { PickerUser(it.email.trim()) }
            .filter { it.email.isNotBlank() }
            .distinctBy { it.email.lowercase(Locale.getDefault()) }
            .sortedBy { it.email.lowercase(Locale.getDefault()) }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_shared_user_picker, null, false)
        val recyclerView = view.findViewById<RecyclerView>(R.id.sharedUserPickerRecycler)
        val emptyText = view.findViewById<TextView>(R.id.sharedUserPickerEmpty)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = SharedUserPickerAdapter(
            users = items,
            selectedEmails = normalizedSelected
        )
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.cancel_button, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onApply(normalizedSelected)
            }
            .show()
    }

    private class SharedUserPickerAdapter(
        private val users: List<PickerUser>,
        private val selectedEmails: MutableSet<String>
    ) : RecyclerView.Adapter<SharedUserPickerAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_shared_user_picker_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = users[position]
            val normalizedEmail = user.email.lowercase(Locale.getDefault())
            val selected = normalizedEmail in selectedEmails
            holder.bind(user.email, selected)
            holder.itemView.setOnClickListener {
                if (selected) selectedEmails.remove(normalizedEmail) else selectedEmails.add(normalizedEmail)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount(): Int = users.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val card: MaterialCardView = itemView.findViewById(R.id.sharedUserPickerRowCard)
            private val email: TextView = itemView.findViewById(R.id.sharedUserPickerRowEmail)
            private val check: ImageView = itemView.findViewById(R.id.sharedUserPickerRowCheck)

            fun bind(value: String, selected: Boolean) {
                val context = itemView.context
                email.text = value
                check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                card.setCardBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.blue_extra_light else R.color.surface
                    )
                )
                email.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.primary_blue else R.color.text_primary
                    )
                )
            }
        }
    }
}
