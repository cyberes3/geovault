package com.geovault.common.maps.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow

class OverlappingPointsPopup(
    context: Context,
    anchor: View,
    names: List<String>,
    x: Int,
    y: Int,
    onSelected: (index: Int) -> Unit,
) {
    private val popup: PopupWindow

    init {
        val list = ListView(context)
        list.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            names.map { if (it.isBlank()) "(unnamed)" else it },
        )
        list.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onSelected(position)
        }
        popup = PopupWindow(list, anchor.width.coerceAtLeast(320), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 8f
        popup.showAsDropDown(anchor, x, y)
    }

    fun show() = Unit
}
