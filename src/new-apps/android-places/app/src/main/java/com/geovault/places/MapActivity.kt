package com.geovault.places

import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import com.geovault.common.maps.core.GeoVaultMapFragment

class MapActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = androidx.fragment.app.FragmentContainerView(this).apply {
            id = android.R.id.content
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, system.top, 0, system.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(android.R.id.content, GeoVaultMapFragment())
            }
        }
    }
}
