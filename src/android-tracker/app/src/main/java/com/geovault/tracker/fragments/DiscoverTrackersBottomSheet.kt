package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.geovault.common.LoadingSpinner
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
                if (public.isEmpty() && shared.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    scrollContent.visibility = View.GONE
                    return@runOnUiThread
                }
                emptyView.visibility = View.GONE
                scrollContent.visibility = View.VISIBLE
                publicList.removeAllViews()
                sharedList.removeAllViews()
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
            }
        }
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
