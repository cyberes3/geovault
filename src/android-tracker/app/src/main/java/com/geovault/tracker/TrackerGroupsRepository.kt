package com.geovault.tracker

import android.content.Context

object TrackerGroupsRepository {
    fun getGroups(
        context: Context,
        forceRefresh: Boolean = false,
        callback: (List<Group>?) -> Unit
    ) {
        TrackerRepository.getGroups(context, forceRefresh, callback)
    }
}

