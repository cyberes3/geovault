package com.geovault.common.concurrent

/**
 * A small memoization cache where a stored value stays valid for [windowMs] milliseconds after
 * it was [put], then is treated as expired by [get] without needing an explicit eviction pass.
 * Intended for short dedupe windows (e.g. "don't re-fetch the same resource within N seconds of
 * the last successful fetch") rather than as a general-purpose, size-bounded LRU cache -- entries
 * are only ever removed by [invalidate]/[invalidateWhere]/[clear] or by expiring in place, so a
 * long-lived instance with many distinct, never-explicitly-invalidated keys will grow unbounded.
 *
 * Backed by a plain synchronized map rather than a coroutine [kotlinx.coroutines.sync.Mutex]:
 * every operation here is an in-memory map read/write with no suspension point, so there is
 * nothing to gain from suspend-based locking, and staying non-suspend lets this be safely used
 * from both suspend call sites and plain synchronous ones (e.g. a session-reset path that must
 * not introduce a coroutine scope just to clear a cache).
 */
class TimeWindowedCache<K, V>(
    private val windowMs: Long,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry<V>(val storedAtMs: Long, val value: V)

    private val lock = Any()
    private val entries = mutableMapOf<K, Entry<V>>()

    /** Returns the cached value for [key] if it was stored within the last [windowMs], else null. */
    fun get(key: K): V? {
        val entry = synchronized(lock) { entries[key] } ?: return null
        val now = nowMsProvider()
        return if (now - entry.storedAtMs <= windowMs) entry.value else null
    }

    fun put(key: K, value: V) {
        synchronized(lock) { entries[key] = Entry(nowMsProvider(), value) }
    }

    fun invalidate(key: K) {
        synchronized(lock) { entries.remove(key) }
    }

    /** Removes every entry whose key satisfies [predicate]. */
    fun invalidateWhere(predicate: (K) -> Boolean) {
        synchronized(lock) {
            entries.keys.filter(predicate).forEach(entries::remove)
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
