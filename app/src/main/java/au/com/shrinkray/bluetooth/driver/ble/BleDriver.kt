package au.com.shrinkray.bluetooth.driver.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.content.Context
import au.com.shrinkray.bluetooth.driver.Device
import au.com.shrinkray.bluetooth.driver.DeviceIdentifier
import au.com.shrinkray.bluetooth.driver.Driver
import au.com.shrinkray.bluetooth.driver.DriverStatus
import io.reactivex.Emitter
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import java.util.*

class BleDriver(private val context: Context, private val bluetoothAdapter: BluetoothAdapter) : Driver {

    // Subject that publishes the current state of the driver and it's availability (e.g. BLE on/off).
    private val statusSubject: BehaviorSubject<DriverStatus> = BehaviorSubject.create()

    // Subject that publishes the raw advertising results to move them off the BLE driver thread.
    private val rawAdvertisingSubject: PublishSubject<AdvertisingPacket> = PublishSubject.create()

    // Subject publishing advertising result devices that match our profile.
    private val deviceSubject: PublishSubject<Device> = PublishSubject.create()

    // List of found devices.
    private val foundDevicesByDeviceIdentifier = mutableMapOf<DeviceIdentifier, Device>()

    // Lists to allow quick matching of devices.
    private val foundDevicesByAdvertisingKey = mutableMapOf<AdvertisingKey, Device>()
    private val rejectedDeviceAdvertisingKeys = mutableSetOf<AdvertisingKey>()

    // Disposable
    private var advertisingDisposable: Disposable? = null
    private var deviceDisposable: Disposable? = null

    // The emitter list.
    private var emitters = mutableSetOf<Emitter<Device>>()

    // Device factories.
    var factories = mutableSetOf<BleDeviceFactory>()

    init {
        // Listen to any advertising packets and convert them to 'devices'.
        advertisingDisposable = rawAdvertisingSubject.observeOn(Schedulers.computation()).subscribe { advertisingPacket ->
            val advertisingKey = AdvertisingKey(advertisingPacket.address, advertisingPacket.scanRecord.bytes)
            if (!rejectedDeviceAdvertisingKeys.contains(advertisingKey)) {
                // If not, check if it was already found.
                var device = foundDevicesByAdvertisingKey[advertisingKey]
                if (device == null) {
                    // If it wasn't, check if it's creatable from this scan record.
                    factories.firstOrNull {
                        it.matchesScanRecord(advertisingPacket.scanRecord)
                    }?.run {
                        val deviceIdentifier = createDeviceIdentifier(advertisingPacket.scanRecord)
                        device = foundDevicesByDeviceIdentifier[deviceIdentifier]?.also {
                            foundDevicesByAdvertisingKey[advertisingKey] = it
                        }
                        if (device == null) {
                            device = createDevice(context, advertisingPacket.device, deviceIdentifier).also {
                                foundDevicesByDeviceIdentifier[it.deviceIdentifier] = it
                                foundDevicesByAdvertisingKey[advertisingKey] = it
                            }
                        }
                    }
                }
                device.also {
                    if (it == null) {
                        rejectedDeviceAdvertisingKeys.add(advertisingKey)
                    } else {
                        deviceSubject.onNext(it)
                    }
                }
            }
        }

        deviceDisposable = deviceSubject.subscribe { device ->
            emitters.forEach { it.onNext(device) }
        }
    }

    // Search for devices. Include any already found.
    override fun search(includePreviouslyFound: Boolean): Observable<Device> {
        return Observable.create<Device> { e ->
            emitters.add(e)
            if (includePreviouslyFound) {
                foundDevicesByDeviceIdentifier.values.forEach {
                    e.onNext(it)
                }
            }
            e.setDisposable(Disposables.fromAction {
                emitters.remove(e)
                if (emitters.size == 0) {
                    stopScanning()
                }
            })
            if (emitters.size == 1) {
                startScanning()
            }
        }
    }

    // Fetch a device by device identifier... either pulling it from teh cache or creating it from scratch.
    override fun fetchDevice(deviceIdentifier: DeviceIdentifier): Device? {
        var device = foundDevicesByDeviceIdentifier[deviceIdentifier]
        if (device == null && deviceIdentifier is BleDeviceIdentifier) {
            device = factories.firstOrNull { it.matchesDeviceIdentifier(deviceIdentifier) }
                    ?.createDevice(context, bluetoothAdapter.getRemoteDevice(deviceIdentifier.address), deviceIdentifier)
        }
        return device
    }

    override fun statusObservable(): Observable<DriverStatus> = statusSubject

    // Scan callback... pushes results
    private val scanCallback = object : ScanCallback() {

        override fun onScanFailed(errorCode: Int) {

        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.run {
                rawAdvertisingSubject.onNext(AdvertisingPacket(device.address, device, scanRecord))
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach {
                rawAdvertisingSubject.onNext(AdvertisingPacket(it.device.address, it.device, it.scanRecord))
            }
        }
    }

    private fun startScanning() {
        // Build a scan filter here to filter things at the BLE layer...
        val scanFilter = ScanFilter.Builder().build()
        bluetoothAdapter.bluetoothLeScanner?.startScan(
                listOf(scanFilter),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback)
    }

    private fun stopScanning() {
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
    }
}

// BLE device factory... implement this to create a device factory individual types of devices based on
abstract class BleDeviceFactory {
    abstract fun matchesScanRecord(scanRecord: ScanRecord): Boolean
    abstract fun matchesDeviceIdentifier(deviceIdentifier: DeviceIdentifier): Boolean
    abstract fun createDeviceIdentifier(scanRecord: ScanRecord): BleDeviceIdentifier
    abstract fun createDevice(context: Context, device: BluetoothDevice, deviceIdentifier: BleDeviceIdentifier): BleDevice
}

data class AdvertisingPacket(val address: String, val device: BluetoothDevice, val scanRecord: ScanRecord)

data class AdvertisingKey(private val address: String, private val scanData: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AdvertisingKey

        if (address != other.address) return false
        if (!Arrays.equals(scanData, other.scanData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + Arrays.hashCode(scanData)
        return result
    }
}
