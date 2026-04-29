package com.geovault.tracker.data

import com.geovault.tracker.Tracker

/**
 * Merges a geometry-focused tracker payload into an existing tracker snapshot without dropping
 * previously known metadata fields when the geometry endpoint omits them.
 */
object TrackerGeometryMergePolicy {
    fun merged(existing: Tracker?, incoming: Tracker): Tracker {
        if (existing == null) return incoming
        return Tracker(
            id = incoming.id,
            name = incoming.name.ifBlank { existing.name },
            color = incoming.color ?: existing.color,
            settings = incoming.settings ?: existing.settings,
            geometry = incoming.geometry ?: existing.geometry,
            point_params = incoming.point_params ?: existing.point_params,
            last_point = incoming.last_point ?: existing.last_point,
            bbox = incoming.bbox ?: existing.bbox,
            tracker_secret = incoming.tracker_secret ?: existing.tracker_secret,
            created_at = incoming.created_at ?: existing.created_at,
            subscribed_at = incoming.subscribed_at ?: existing.subscribed_at,
            updated_at = incoming.updated_at ?: existing.updated_at,
            is_owner = incoming.is_owner ?: existing.is_owner,
            visibility = incoming.visibility ?: existing.visibility,
            share_params_with_recipients = incoming.share_params_with_recipients
                ?: existing.share_params_with_recipients,
            share_params_with_world = incoming.share_params_with_world ?: existing.share_params_with_world,
            owner_email = incoming.owner_email ?: existing.owner_email,
            subscriber_count = incoming.subscriber_count ?: existing.subscriber_count,
            internal_share_id = incoming.internal_share_id ?: existing.internal_share_id,
            internal_share_url = incoming.internal_share_url ?: existing.internal_share_url,
            world_share_id = incoming.world_share_id ?: existing.world_share_id,
            world_share_url = incoming.world_share_url ?: existing.world_share_url,
            shared_with_emails = incoming.shared_with_emails ?: existing.shared_with_emails,
        )
    }
}
