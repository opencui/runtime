package io.opencui.core

import java.util.concurrent.TimeUnit

interface Cache<Key, Value> {
    val size: Int
    operator fun set(key: Key, value: Value)
    operator fun get(key: Key): Value?
    fun remove(key: Key): Value?
    fun clear()
}

class PerpetualCache<Key, Value> : Cache<Key, Value> {
    private val cache = HashMap<Key, Value>()

    override val size: Int
        get() = cache.size

    override fun set(key: Key, value: Value) {
        this.cache[key] = value
    }

    override fun remove(key: Key) = this.cache.remove(key)

    override fun get(key: Key) = this.cache[key]

    override fun clear() = this.cache.clear()
    override fun toString(): String {
        return "PerpetualCache(cache=${cache.toList().joinToString { "${it.first}:${it.second}" }})"
    }


}

class LRUCache<Key, Value>(
    private val delegate: Cache<Key, Value>,
    private val minimalSize: Int = DEFAULT_SIZE
) : Cache<Key, Value> {

    private val keyMap = object : LinkedHashMap<Key, Boolean>(
            minimalSize, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Boolean>): Boolean {
            val tooManyCachedItems = size > minimalSize
             eldestKeyToRemove = if (tooManyCachedItems) eldest.key else null
            return tooManyCachedItems
        }
    }

    private var eldestKeyToRemove: Key? = null

    override val size: Int
        get() = delegate.size

    override fun set(key: Key, value: Value) {
        keyMap[key] = PRESENT
        delegate[key] = value
        cycleKeyMap(key)
    }

    override fun remove(key: Key): Value? {
        keyMap.remove(key)
        return delegate.remove(key)
    }

    override fun get(key: Key): Value? {
        keyMap[key]
        return delegate[key]
    }

    override fun clear() {
        keyMap.clear()
        delegate.clear()
    }

    private fun cycleKeyMap(key: Key) {
        eldestKeyToRemove?.let {
            (delegate.remove(it) as? Recyclable)?.recycle()
        }
        eldestKeyToRemove = null
    }

    companion object {
        private const val DEFAULT_SIZE = 100
        private const val PRESENT = true
    }
}

class ExpirableCache<Key, Value>(
    private val delegate: Cache<Key, Pair<Value, Long>>,
    private val flushInterval: Long = TimeUnit.MINUTES.toNanos(1)
) : Cache<Key, Value> {

    override val size: Int
        get() = delegate.size

    override fun set(key: Key, value: Value) {
        delegate[key] = Pair(value, System.nanoTime())
    }

    override fun remove(key: Key): Value? {
        recycle(key)
        return delegate.remove(key)?.first
    }

    override fun get(key: Key): Value? {
        recycle(key)
        val value = delegate[key]
        return if (value == null) {
            null
        } else {
            set(key, value.first)
            value.first
        }
    }

    override fun clear() = delegate.clear()

    private fun recycle(key: Key) {
        val valuePair = delegate[key] ?: return
        val shouldRecycle = System.nanoTime() - valuePair.second >= flushInterval
        if (!shouldRecycle) return
        delegate.remove(key)?.first?.apply { if (this is Recyclable) this.recycle() }
    }
}

interface Recyclable {
    fun recycle()
}
