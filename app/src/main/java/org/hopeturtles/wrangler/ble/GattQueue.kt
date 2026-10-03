package org.hopeturtles.wrangler.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serial GATT operation queue.
 *
 * Android's BluetoothGatt allows exactly ONE outstanding operation: a second
 * read/write/descriptor-write issued before the previous callback fires fails
 * silently (returns false or is dropped). Every GATT operation in the app goes
 * through [run], which holds a mutex until the operation's callback completes
 * the deferred (or the timeout passes).
 *
 * Usage (inside TurtleConnection):
 *     val bytes = queue.run<ByteArray?> { done -> gatt.readCharacteristic(ch); pendingRead = done }
 * and the GATT callback completes `pendingRead`.
 */
class GattQueue(private val timeoutMs: Long = 5_000) {
    private val mutex = Mutex()

    /**
     * Run one GATT operation. [start] issues it and returns false if Android
     * refused to start it; the callback must later complete [done].
     * Returns the callback's value, or null on refusal / timeout.
     */
    suspend fun <T> run(start: (done: CompletableDeferred<T>) -> Boolean): T? = mutex.withLock {
        val done = CompletableDeferred<T>()
        if (!start(done)) return@withLock null
        withTimeoutOrNull(timeoutMs) { done.await() }
    }
}
