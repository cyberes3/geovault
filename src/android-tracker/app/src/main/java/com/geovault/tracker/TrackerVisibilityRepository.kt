package com.geovault.tracker

import android.content.Context

object TrackerVisibilityRepository {
    fun getMapVisibility(context: Context, callback: (MapVisibilityResponse?) -> Unit) {
        TrackerRepository.getMapVisibility(context, callback)
    }

    fun patchMapVisibility(
        context: Context,
        request: MapVisibilityRequest,
        callback: (MapVisibilityResponse?) -> Unit
    ) {
        TrackerRepository.patchMapVisibility(context, request, callback)
    }
}

