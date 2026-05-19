package com.geovault.tracker.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Coalesces concurrent requests for the same key into one in-flight coroutine.
 */
class SingleFlightRequestGate<K, V>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val lock = Any()
    private val inFlightByKey = mutableMapOf<K, Deferred<V>>()

    suspend fun run(key: K, operation: suspend () -> V): V {
        val deferred = synchronized(lock) {
            inFlightByKey[key] ?: scope.async(start = CoroutineStart.LAZY) {
                operation()
            }.also { created ->
                inFlightByKey[key] = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlightByKey[key] === created) {
                            inFlightByKey.remove(key)
                        }
                    }
                }
                created.start()
            }
        }
        return deferred.await()
    }

    fun clear() {
        synchronized(lock) {
            inFlightByKey.clear()
        }
    }

    fun clearMatching(predicate: (K) -> Boolean) {
        synchronized(lock) {
            inFlightByKey.keys.removeAll { key -> predicate(key) }
        }
    }
}
