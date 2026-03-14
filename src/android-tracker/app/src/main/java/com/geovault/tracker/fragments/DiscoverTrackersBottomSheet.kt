package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackerRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DiscoverTrackersBottomSheet : BottomSheetDialogFragment() {

    private lateinit var scrollContent: View
    private lateinit var publicHeader: TextView
    private lateinit var publicList: LinearLayout
    private lateinit var sharedHeader: TextView
    private lateinit var sharedList: LinearLayout
    private lateinit var publicGroupsHeader: TextView
    private lateinit var publicGroupsList: LinearLayout
    private lateinit var sharedGroupsHeader: TextView
    private lateinit var sharedGroupsList: LinearLayout
    private lateinit var loadingView: View
    private lateinit var spinner: LoadingSpinner
    private lateinit var emptyView: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_discover_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scrollContent = view.findViewById(R.id.discoverScrollContent)
        publicHeader = view.findViewById(R.id.discoverPublicHeader)
        publicList = view.findViewById(R.id.discoverPublicList)
        sharedHeader = view.findViewById(R.id.discoverSharedHeader)
        sharedList = view.findViewById(R.id.discoverSharedList)
        publicGroupsHeader = view.findViewById(R.id.discoverPublicGroupsHeader)
        publicGroupsList = view.findViewById(R.id.discoverPublicGroupsList)
        sharedGroupsHeader = view.findViewById(R.id.discoverSharedGroupsHeader)
        sharedGroupsList = view.findViewById(R.id.discoverSharedGroupsList)
        loadingView = view.findViewById(R.id.discoverLoading)
        spinner = view.findViewById(R.id.discoverSpinner)
        emptyView = view.findViewById(R.id.discoverEmpty)

        spinner.start()
        loadAvailable()
    }

    private fun loadAvailable() {
        TrackerRepository.getAvailableToAdd(requireContext()) { response ->
            if (!isAdded) return@getAvailableToAdd
            requireActivity().runOnUiThread {
                spinner.stop(hide = true)
                loadingView.visibility = View.GONE
                if (response == null) {
                    emptyView.visibility = View.VISIBLE
                    emptyView.text = getString(R.string.failed_to_load_tracker)
                    return@runOnUiThread
                }
                val public = response.public
                val shared = response.shared_with_me
                val publicGroups = response.public_groups
                val sharedGroups = response.shared_with_me_groups
                if (public.isEmpty() && shared.isEmpty() && publicGroups.isEmpty() && sharedGroups.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    scrollContent.visibility = View.GONE
                    return@runOnUiThread
                }
                emptyView.visibility = View.GONE
                scrollContent.visibility = View.VISIBLE
                publicList.removeAllViews()
                sharedList.removeAllViews()
                publicGroupsList.removeAllViews()
                sharedGroupsList.removeAllViews()
                if (public.isNotEmpty()) {
                    publicHeader.visibility = View.VISIBLE
                    publicList.visibility = View.VISIBLE
                    for (item in public) {
                        addItemRow(publicList, item)
                    }
                } else {
                    publicHeader.visibility = View.GONE
                    publicList.visibility = View.GONE
                }
                if (shared.isNotEmpty()) {
                    sharedHeader.visibility = View.VISIBLE
                    sharedList.visibility = View.VISIBLE
                    for (item in shared) {
                        addItemRow(sharedList, item)
                    }
                } else {
                    sharedHeader.visibility = View.GONE
                    sharedList.visibility = View.GONE
                }
                if (publicGroups.isNotEmpty()) {
                    publicGroupsHeader.visibility = View.VISIBLE
                    publicGroupsList.visibility = View.VISIBLE
                    for (group in publicGroups) {
                        addGroupRow(publicGroupsList, group)
                    }
                } else {
                    publicGroupsHeader.visibility = View.GONE
                    publicGroupsList.visibility = View.GONE
                }
                if (sharedGroups.isNotEmpty()) {
                    sharedGroupsHeader.visibility = View.VISIBLE
                    sharedGroupsList.visibility = View.VISIBLE
                    for (group in sharedGroups) {
                        addGroupRow(sharedGroupsList, group)
                    }
                } else {
                    sharedGroupsHeader.visibility = View.GONE
                    sharedGroupsList.visibility = View.GONE
                }
            }
        }
    }

    private fun addGroupRow(parent: LinearLayout, group: AvailableToAddGroup) {
        val row = layoutInflater.inflate(R.layout.item_available_tracker, parent, false)
        row.findViewById<TextView>(R.id.availableTrackerName).text = group.name
        row.findViewById<TextView>(R.id.availableTrackerOwner).text = (group.owner_email?.takeIf { it.isNotBlank() } ?: "") + " (group)"
        val addBtn = row.findViewById<com.google.android.material.button.MaterialButton>(R.id.availableTrackerAdd)
        addBtn.setOnClickListener {
            addBtn.isEnabled = false
            var failed = false
            var done = 0
            val trackIds = group.track_ids
            if (trackIds.isEmpty()) {
                parent.removeView(row)
                parentFragmentManager.setFragmentResult(TrackersFragment.REQUEST_REFRESH_LIST, Bundle())
                (activity as? MainActivity)?.showSnackbar(getString(R.string.saved_tracker))
                return@setOnClickListener
            }
            for (trackId in trackIds) {
                TrackerRepository.subscribeTracker(requireContext(), trackId) { tracker ->
                    if (!isAdded) return@subscribeTracker
                    requireActivity().runOnUiThread {
                        done++
                        if (tracker == null) failed = true
                        if (done == trackIds.size) {
                            if (!failed) {
                                parent.removeView(row)
                                if (parent.childCount == 0) {
                                    if (parent == publicGroupsList) {
                                        publicGroupsHeader.visibility = View.GONE
                                        publicGroupsList.visibility = View.GONE
                                    } else {
                                        sharedGroupsHeader.visibility = View.GONE
                                        sharedGroupsList.visibility = View.GONE
                                    }
                                }
                                parentFragmentManager.setFragmentResult(TrackersFragment.REQUEST_REFRESH_LIST, Bundle())
                                (activity as? MainActivity)?.showSnackbar(getString(R.string.saved_tracker))
                            } else {
                                addBtn.isEnabled = true
                                (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                            }
                        }
                    }
                }
            }
        }
        parent.addView(row)
    }

    private fun addItemRow(parent: LinearLayout, item: AvailableToAddItem) {
        val row = layoutInflater.inflate(R.layout.item_available_tracker, parent, false)
        row.findViewById<TextView>(R.id.availableTrackerName).text = item.name
        row.findViewById<TextView>(R.id.availableTrackerOwner).text = item.owner_email?.takeIf { it.isNotBlank() } ?: ""
        row.findViewById<com.google.android.material.button.MaterialButton>(R.id.availableTrackerAdd).setOnClickListener {
            row.findViewById<com.google.android.material.button.MaterialButton>(R.id.availableTrackerAdd).isEnabled = false
            TrackerRepository.subscribeTracker(requireContext(), item.id) { tracker ->
                if (!isAdded) return@subscribeTracker
                requireActivity().runOnUiThread {
                    if (tracker != null) {
                        parent.removeView(row)
                        if (parent.childCount == 0) {
                            if (parent == publicList) {
                                publicHeader.visibility = View.GONE
                                publicList.visibility = View.GONE
                            } else {
                                sharedHeader.visibility = View.GONE
                                sharedList.visibility = View.GONE
                            }
                        }
                        parentFragmentManager.setFragmentResult(TrackersFragment.REQUEST_REFRESH_LIST, Bundle())
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.saved_tracker))
                    } else {
                        row.findViewById<com.google.android.material.button.MaterialButton>(R.id.availableTrackerAdd).isEnabled = true
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    }
                }
            }
        }
        parent.addView(row)
    }
}
