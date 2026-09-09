package com.geovault.common.concurrent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cancel/replace tokens for sequenced commands (stop then start, switch-while-recording).
 * [invalidate] makes every earlier [Token] for that key stale so in-flight work can drop itself.
 */
class CommandCorrelation {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    fun next(key: String = DEFAULT_KEY): Token {
        val generation = generationCounter(key).incrementAndGet()
        return Token(key = key, generation = generation)
    }

    fun current(key: String = DEFAULT_KEY): Token {
        return Token(key = key, generation = generationCounter(key).get())
    }

    fun invalidate(key: String = DEFAULT_KEY): Token = next(key)

    fun isCurrent(token: Token): Boolean {
        return generationCounter(token.key).get() == token.generation
    }

    private fun generationCounter(key: String): AtomicLong {
        return generations.getOrPut(key) { AtomicLong(0L) }
    }

    data class Token(
        val key: String,
        val generation: Long,
    )

    companion object {
        const val DEFAULT_KEY = "default"
    }
}
