package com.geovault.common.concurrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Coalesces concurrent requests for the same key into one in-flight coroutine: if [run] is
 * called again for a key that already has an operation in progress, the caller awaits that same
 * operation instead of starting a duplicate one. Useful for de-duplicating bursts of identical
 * network/database calls that land close together (e.g. several UI collectors independently
 * requesting the same resource on screen entry).
 */
class SingleFlightGate<K, V>(
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
}
