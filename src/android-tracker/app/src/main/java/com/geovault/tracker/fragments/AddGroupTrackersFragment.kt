package com.geovault.tracker.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geovault.common.LoadingSpinner
import com.geovault.common.NaturalSort
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AddGroupTrackersFragment : Fragment() {
    private val viewModel: GroupDetailViewModel by activityViewModels()

    private data class AddableTrack(val tracker: Tracker) {
        val id: String get() = tracker.id
        val name: String get() = tracker.name
        val ownerEmail: String get() = tracker.owner_email?.takeIf { it.isNotBlank() } ?: ""
    }

    private lateinit var loadingView: View
    private lateinit var spinner: LoadingSpinner
    private lateinit var emptyView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText

    private var allItems: List<AddableTrack> = emptyList()
    private var draftTrackIds: Set<String> = emptySet()
    private var query: String = ""
    private var isLoading: Boolean = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_add_group_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadingView = view.findViewById(R.id.addGroupTrackersLoading)
        spinner = view.findViewById(R.id.addGroupTrackersSpinner)
        emptyView = view.findViewById(R.id.addGroupTrackersEmpty)
        listContainer = view.findViewById(R.id.addGroupTrackersList)
        searchInput = view.findViewById(R.id.addGroupTrackersSearch)

        view.findViewById<View>(R.id.addGroupTrackersCloseButton).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                renderList()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    isLoading = state.phase == GroupDetailPhase.Loading
                    if (isLoading) {
                        loadingView.visibility = View.VISIBLE
                        spinner.start()
                    } else {
                        loadingView.visibility = View.GONE
                        spinner.stop(hide = true)
                    }
                    draftTrackIds = state.draftTrackIds
                    allItems = state.addableTrackers
                        .map { AddableTrack(it) }
                        .distinctBy { it.id }
                        .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
                    renderList()
                    state.errorMessage?.takeIf { it.isNotBlank() }?.let {
                        navHost()?.showSnackbar(it)
                        viewModel.consumeError()
                    }
                }
            }
        }
    }

    private fun renderList() {
        if (isLoading) {
            emptyView.visibility = View.GONE
            if (allItems.isEmpty()) {
                listContainer.visibility = View.GONE
            }
            return
        }

        val normalized = query.trim().lowercase()
        val filtered = allItems.filter { item ->
            normalized.isBlank() ||
                item.name.lowercase().contains(normalized) ||
                item.ownerEmail.lowercase().contains(normalized)
        }
        listContainer.removeAllViews()
        listContainer.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        for (item in filtered) {
            val row = layoutInflater.inflate(R.layout.item_add, listContainer, false)
            val typeIcon = row.findViewById<ImageView>(R.id.availableTrackerTypeIcon)
            typeIcon.setImageResource(R.drawable.ic_chevron_track)
            typeIcon.setColorFilter(requireContext().getColor(R.color.primary_blue))
            row.findViewById<TextView>(R.id.availableTrackerName).text = item.name
            val ownerView = row.findViewById<TextView>(R.id.availableTrackerOwner)
            if (item.tracker.isOwner()) {
                ownerView.visibility = View.GONE
            } else {
                ownerView.visibility = View.VISIBLE
                ownerView.text = item.ownerEmail
            }
            val addBtn = row.findViewById<ImageButton>(R.id.availableTrackerAdd)
            val spinnerView = row.findViewById<LoadingSpinner>(R.id.availableTrackerSpinner)
            val checkBtn = row.findViewById<ImageButton>(R.id.availableTrackerCheck)
            val deleteBtn = row.findViewById<ImageButton>(R.id.availableTrackerDelete)
            addBtn.tooltipText = getString(R.string.tooltip_add_group_row_add)
            checkBtn.tooltipText = getString(R.string.tooltip_add_group_row_check)
            checkBtn.visibility = View.GONE
            deleteBtn.visibility = View.GONE

            val isSelected = item.id in draftTrackIds
            addBtn.visibility = if (isSelected) View.GONE else View.VISIBLE
            checkBtn.visibility = if (isSelected) View.VISIBLE else View.GONE
            spinnerView.visibility = View.GONE
            spinnerView.stop(hide = true)
            if (isSelected) {
                checkBtn.setOnClickListener {
                    viewModel.removeDraftTracker(item.id)
                }
            } else {
                addBtn.setOnClickListener {
                    viewModel.addDraftTracker(item.id)
                }
            }
            listContainer.addView(row)
        }
    }

    companion object {
        fun newInstance(): AddGroupTrackersFragment {
            return AddGroupTrackersFragment()
        }
    }
}
