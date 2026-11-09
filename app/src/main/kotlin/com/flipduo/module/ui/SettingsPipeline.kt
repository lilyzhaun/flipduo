// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single serial path for settings: newest snapshot -> local commit -> the same snapshot to the framework.
 * Runs on [writer] (production: a one-thread IO view), never on the UI thread. Intermediate snapshots are
 * coalesced, so an older snapshot can never be written after a newer one. A framework write that finishes
 * after a detach/re-attach is ignored instead of being reported as delivered. Failures stop the loop; only a
 * new edit, [retry] or [attach] runs it again, so there is no unbounded retry.
 */
class SettingsPipeline<T : Any>(
    initial: T,
    initialSaved: Boolean,
    private val revisionOf: (T) -> Int,
    private val writeLocal: (T) -> Unit,
    writer: CoroutineDispatcher,
    private val onStatus: (Status) -> Unit = {},
) {
    /** latest: newest edit; localSaved: last revision whose local commit returned; delivered: current framework connection only. */
    data class Status(val latest: Int, val localSaved: Int, val localFailed: Boolean, val connected: Boolean,
                      val delivered: Int, val remoteFailed: Boolean, val busy: Boolean) {
        val localPending get() = !localFailed && localSaved != latest
        val fullyDelivered get() = connected && delivered == latest && localSaved == latest
    }

    private val scope = CoroutineScope(SupervisorJob() + writer)
    private val mutex = Mutex()
    private val lock = Any()
    private val queued = AtomicBoolean(false)
    private val active = AtomicInteger(0)
    @Volatile private var latest: T = initial
    private var saved: T? = if (initialSaved) initial else null // touched only inside the serial drain
    private var remote: ((T) -> Unit)? = null
    private var generation = 0
    private var current = Status(revisionOf(initial), if (initialSaved) revisionOf(initial) else -1, false, false, -1, false, false)

    val status: Status get() = synchronized(lock) { current }

    /** UI thread: the edit is visible immediately; persistence follows on the serial writer. */
    fun submit(value: T) {
        latest = value
        // A new edit is one fresh attempt for both steps; failures still stop the loop afterwards.
        change { it.copy(latest = revisionOf(value), localFailed = false, remoteFailed = false) }
        kick()
    }

    fun attach(write: (T) -> Unit) {
        synchronized(lock) { generation++; remote = write }
        change { it.copy(connected = true, delivered = -1, remoteFailed = false) }
        kick()
    }

    fun detach() {
        synchronized(lock) { generation++; remote = null }
        change { it.copy(connected = false, delivered = -1, remoteFailed = false) }
    }

    /** Re-run the same path for the current snapshot; never creates a new revision. */
    fun retry() {
        change { it.copy(localFailed = false, remoteFailed = false) }
        kick()
    }

    private fun kick() {
        if (!queued.compareAndSet(false, true)) return
        active.incrementAndGet(); change { it.copy(busy = true) }
        scope.launch {
            try {
                queued.set(false)
                mutex.withLock { drain() }
            } finally {
                if (active.decrementAndGet() == 0) change { it.copy(busy = active.get() != 0) }
            }
        }
    }

    private fun drain() {
        while (true) {
            val target = latest
            val rev = revisionOf(target)
            val stored = saved
            if (stored == null || revisionOf(stored) != rev) {
                if (status.localFailed && status.latest == rev) return
                try { writeLocal(target) }
                catch (_: Exception) { change { if (it.latest == rev) it.copy(localFailed = true) else it }; return }
                saved = target
                change { it.copy(localSaved = rev, localFailed = if (it.latest == rev) false else it.localFailed) }
                continue
            }
            val (write, gen) = synchronized(lock) { remote to generation }
            val snapshot = status
            if (write == null || snapshot.delivered == rev || snapshot.remoteFailed) return
            val ok = try { write(stored); true } catch (_: Exception) { false }
            // A result from an older framework connection is dropped, never reported as delivered.
            val stale = synchronized(lock) {
                if (gen != generation) true
                else { current = current.copy(delivered = if (ok) rev else current.delivered, remoteFailed = !ok); onStatus(current); false }
            }
            if (!ok && !stale) return
        }
    }

    /** Notified under the lock so observers never see an older status after a newer one; [onStatus] must be cheap. */
    private fun change(update: (Status) -> Status) {
        synchronized(lock) { current = update(current); onStatus(current) }
    }
}
