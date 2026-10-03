package org.hopeturtles.wrangler.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A turtle heard advertising. [name] is its device_name (turtles_tb.name). */
data class FoundTurtle(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
    val lastSeenMs: Long,
    val bonded: Boolean,
) {
    val address: String get() = device.address
}

/**
 * Scans for turtles: only devices advertising the Turtle service UUID, so no
 * name matching is needed and other BLE gadgets never appear. A turtle only
 * advertises while its wrangle window is open (or when ble_window_min = 0).
 */
@SuppressLint("MissingPermission")   // the UI only scans after BLUETOOTH_SCAN is granted
class TurtleScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _found = MutableStateFlow<List<FoundTurtle>>(emptyList())
    val found: StateFlow<List<FoundTurtle>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val now = System.currentTimeMillis()
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: dev.name ?: "Turtle ${dev.address.takeLast(5)}"
            val entry = FoundTurtle(dev, name, result.rssi, now, dev.bondState == BluetoothDevice.BOND_BONDED)
            _found.value = (_found.value.filter { it.address != dev.address } + entry)
                .filter { now - it.lastSeenMs < STALE_MS }
                .sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        _found.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(TurtleUuids.TURTLE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        _scanning.value = true
    }

    fun stop() {
        if (!_scanning.value) return
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
        _scanning.value = false
    }

    companion object {
        const val STALE_MS = 10_000L
    }
}
