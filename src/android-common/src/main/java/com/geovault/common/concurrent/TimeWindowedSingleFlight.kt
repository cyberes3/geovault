package com.geovault.common.concurrent

import kotlinx.coroutines.CoroutineScope

/**
 * Combines [SingleFlightGate] with [TimeWindowedCache]: concurrent callers share one in-flight
 * operation, and a successful result is reused for [windowMs]. The cache is checked again inside
 * the gate (double-check) so a waiter that lost the race does not re-run the operation.
 */
class TimeWindowedSingleFlight<K, V>(
    scope: CoroutineScope,
    windowMs: Long,
    nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val cache = TimeWindowedCache<K, V>(windowMs = windowMs, nowMsProvider = nowMsProvider)
    private val gate = SingleFlightGate<K, V>(scope)

    suspend fun run(key: K, operation: suspend () -> V): V {
        cache.get(key)?.let { return it }
        return gate.run(key) {
            cache.get(key)?.let { return@run it }
            operation().also { value -> cache.put(key, value) }
        }
    }

    fun get(key: K): V? = cache.get(key)

    fun put(key: K, value: V) = cache.put(key, value)

    fun invalidate(key: K) = cache.invalidate(key)

    fun invalidateWhere(predicate: (K) -> Boolean) = cache.invalidateWhere(predicate)

    fun clear() {
        cache.clear()
        gate.clear()
    }
}
