package com.lagradost.cloudstream3.utils

/** CloudStream's tiny observer list (compat; see CloudStreamApp.kt). */
class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()

    val size: Int get() = observers.size

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.add(observer) }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.remove(observer) }
    }

    operator fun invoke(value: T) {
        // A copy: an observer may unsubscribe itself while being told.
        val now = synchronized(observers) { observers.toList() }
        for (observer in now) runCatching { observer(value) }
    }
}
