package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class TrackersPagerFragment : Fragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var fab: FloatingActionButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_trackers_pager, container, false)
        tabLayout = view.findViewById(R.id.trackersPagerTabLayout)
        viewPager = view.findViewById(R.id.trackersPagerViewPager)
        fab = view.findViewById(R.id.trackersPagerFab)
        setupViewPager()
        setupFab()
        return view
    }

    private fun setupViewPager() {
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> TrackersListFragment()
                    1 -> GroupsListFragment()
                    else -> throw IllegalStateException("Invalid position $position")
                }
            }
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.trackers_tab) else getString(R.string.groups_title)
        }.attach()
    }

    private fun setupFab() {
        fab.setOnClickListener {
            if (viewPager.currentItem == 0) {
                (activity as? MainActivity)?.showNewTrackerFragment()
            } else {
                (childFragmentManager.findFragmentByTag("f1") as? GroupsListFragment)?.showCreateGroupDialog()
            }
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                fab.contentDescription = if (position == 0) getString(R.string.create_new_tracker) else getString(R.string.create_group)
            }
        })
    }

    fun selectTrackersTab() {
        viewPager.setCurrentItem(0, false)
    }

    fun selectGroupsTab() {
        viewPager.setCurrentItem(1, false)
    }

    fun getTrackersListFragment(): TrackersListFragment? {
        return childFragmentManager.findFragmentByTag("f0") as? TrackersListFragment
    }
}
