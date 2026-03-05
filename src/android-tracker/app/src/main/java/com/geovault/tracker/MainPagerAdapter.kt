package com.geovault.tracker

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.geovault.tracker.fragments.HomeFragment
import com.geovault.tracker.fragments.MapFragment
import com.geovault.tracker.fragments.SettingsFragment

class MainPagerAdapter(private val activity: FragmentActivity) : FragmentStateAdapter(activity) {
    
    override fun getItemCount(): Int = 3
    
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> HomeFragment()
            1 -> MapFragment()
            2 -> SettingsFragment()
            else -> throw IllegalStateException("Invalid position $position")
        }
    }
    
    fun getFragment(position: Int): Fragment? {
        return activity.supportFragmentManager.findFragmentByTag("f$position")
    }
}
