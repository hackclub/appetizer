package com.fantamomo.slack.approver.utils

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A small thread-safe in-memory cache with a time-to-live per entry and an upper bound on the number of entries.
 *
 * Before this class existed, most caches in the code base were plain maps which only dropped an expired entry
 * when the same key was requested again, so every key that was ever used stayed in memory forever.
 * This cache drops expired entries once it is full and then evicts the least recently used entries
 * until it fits into [maxSize] again, so its memory usage is always bounded.
 *
 * @param maxSize the maximum number of entries kept in memory.
 * @param defaultTtl the time-to-live used when no explicit ttl is passed to [put].
 */
class ExpiringCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val defaultTtl: Duration,
) {
    private class Entry<V>(val value: V, val expiresAt: Instant)

    // access ordered, so the first entry is always the least recently used one
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    operator fun get(key: K): V? = synchronized(entries) {
        val entry = entries[key] ?: return null
        if (Clock.System.now() >= entry.expiresAt) {
            entries.remove(key)
            return null
        }
        entry.value
    }

    fun put(key: K, value: V, ttl: Duration = defaultTtl) {
        val now = Clock.System.now()
        synchronized(entries) {
            entries[key] = Entry(value, now + ttl)
            if (entries.size > maxSize) {
                removeExpired(now)
                val iterator = entries.entries.iterator()
                while (entries.size > maxSize && iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    operator fun set(key: K, value: V) = put(key, value)

    operator fun contains(key: K): Boolean = get(key) != null

    fun remove(key: K): V? = synchronized(entries) { entries.remove(key)?.value }

    fun clear() = synchronized(entries) { entries.clear() }

    val size: Int get() = synchronized(entries) { entries.size }

    private fun removeExpired(now: Instant) {
        entries.entries.removeIf { now >= it.value.expiresAt }
    }
}
