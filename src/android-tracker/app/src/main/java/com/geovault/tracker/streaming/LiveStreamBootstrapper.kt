package com.geovault.tracker.streaming

/**
 * Seeds [LiveStreamSubscriptionRepository] from persisted service state exactly once per
 * process. Kept as a separate one-shot object (rather than folding the call into the
 * repository's constructor) so construction stays side-effect-free and testable, and so it's
 * obvious from [TrackerAppServices][com.geovault.tracker.di.TrackerAppServices] *when* the
 * cold-start seed happens relative to the rest of app init.
 */
internal object LiveStreamBootstrapper {
    fun bootstrap(repository: LiveStreamSubscriptionRepository) {
        repository.seedFromPersistedState()
    }
}
