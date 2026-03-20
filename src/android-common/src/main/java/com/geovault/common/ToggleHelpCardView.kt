package com.geovault.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/**
 * Reusable settings row: title + toggle with a short help text in a bordered card.
 *
 * XML attrs:
 * - app:toggleTitleText
 * - app:toggleHelpText
 * - app:toggleChecked
 */
class ToggleHelpCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val helpView: TextView
    private val toggle: SwitchCompat

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.gv_common_view_toggle_help_card, this, true)
        titleView = root.findViewById(R.id.gv_common_toggle_help_title)
        helpView = root.findViewById(R.id.gv_common_toggle_help_text)
        toggle = root.findViewById(R.id.gv_common_toggle_help_switch)

        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.ToggleHelpCardView, defStyleAttr, 0)
        try {
            setTitleText(typedArray.getString(R.styleable.ToggleHelpCardView_toggleTitleText))
            setHelpText(typedArray.getString(R.styleable.ToggleHelpCardView_toggleHelpText))
            toggle.isChecked = typedArray.getBoolean(R.styleable.ToggleHelpCardView_toggleChecked, false)
        } finally {
            typedArray.recycle()
        }

        // Allow tapping the whole card to toggle.
        setOnClickListener { if (isEnabled) toggle.toggle() }
    }

    fun setTitleText(text: CharSequence?) {
        titleView.text = text
    }

    fun setHelpText(text: CharSequence?) {
        helpView.text = text
        helpView.visibility = if (text.isNullOrBlank()) GONE else VISIBLE
    }

    var isChecked: Boolean
        get() = toggle.isChecked
        set(value) {
            toggle.isChecked = value
        }

    fun setOnCheckedChangeListener(listener: CompoundButton.OnCheckedChangeListener?) {
        toggle.setOnCheckedChangeListener(listener)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        toggle.isEnabled = enabled
        titleView.isEnabled = enabled
        helpView.isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
    }
}
