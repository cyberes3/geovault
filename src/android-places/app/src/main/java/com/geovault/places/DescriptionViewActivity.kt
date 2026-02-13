package com.geovault.places

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class DescriptionViewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_description_view)

        val placeName = intent.getStringExtra(EXTRA_PLACE_NAME) ?: ""
        val description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: getString(R.string.no_description)

        findViewById<TextView>(R.id.placeNameTitle).text = placeName.ifEmpty { getString(R.string.description) }
        findViewById<TextView>(R.id.fullDescription).text = description.ifEmpty { getString(R.string.no_description) }
        findViewById<TextView>(R.id.fullDescription).setTextIsSelectable(true)

        findViewById<ImageView>(R.id.closeButton).setOnClickListener {
            finish()
        }

        setupWindowInsets()
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.description_view_root)
        val headerView = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerView.updatePadding(top = systemBars.top + 20)
            windowInsets
        }
    }

    companion object {
        const val EXTRA_PLACE_NAME = "place_name"
        const val EXTRA_DESCRIPTION = "description"

        fun intent(context: android.content.Context, placeName: String, description: String): Intent {
            return Intent(context, DescriptionViewActivity::class.java).apply {
                putExtra(EXTRA_PLACE_NAME, placeName)
                putExtra(EXTRA_DESCRIPTION, description)
            }
        }
    }
}
