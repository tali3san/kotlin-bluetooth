package au.com.shrinkray.bluetooth.driver.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build
import au.com.shrinkray.bluetooth.driver.Device
import au.com.shrinkray.bluetooth.driver.DeviceIdentifier
import au.com.shrinkray.bluetooth.driver.DeviceStatus
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque

abstract class BleDevice(private val context: Context, val deviceIdentifier: DeviceIdentifier, private val device: BluetoothDevice) : Device {

    override val identifier: DeviceIdentifier
        get() = deviceIdentifier

    var bluetoothGatt: BluetoothGatt? = null

    var scheduler = Schedulers.from(Executors.newSingleThreadExecutor())
    var notifyScheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    val statusSubject: BehaviorSubject<DeviceStatus> = BehaviorSubject.createDefault<DeviceStatus>(DeviceStatus.DISCONNECTED)
    val callbackQueue = LinkedBlockingDeque<OnCallback>()

    override val statusObservable: Observable<DeviceStatus>
        get() = statusSubject

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    bluetoothGatt?.discoverServices()
                    statusSubject.onNext(DeviceStatus.CONNECTED)
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    bluetoothGatt = null
                    statusSubject.onNext(DeviceStatus.DISCONNECTED)
                    // Cleanup, we have to do this otherwise we leak resources.
                    gatt?.close()
                }
                else -> {

                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            // If we request an MTU change consume it here.
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            statusSubject.onNext(DeviceStatus.SERVICES_DISCOVERED)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            callbackQueue.add(OnReadRemoteRssiCallback(gatt, rssi, status))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            callbackQueue.add(OnCharacteristicReadCallback(gatt, characteristic, status, characteristic?.value))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            callbackQueue.add(OnCharacteristicWriteCallback(gatt, characteristic, status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            callbackQueue.add(OnDescriptorWriteCallback(gatt, descriptor, status))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            processChangeCallback(OnCharacteristicChangeCallback(gatt, characteristic, characteristic?.value))?.run { callbackQueue.add(this) }
        }
    }

    override fun connect(): Completable = Completable.fromAction {
        if (bluetoothGatt == null) {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
        }
    }

    override fun disconnect(): Completable = Completable.fromAction {
        bluetoothGatt?.run { disconnect() }
    }

    override fun cleanUp(): Completable = Completable.fromAction {
        bluetoothGatt?.run {
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
    }

    abstract fun processChangeCallback(callback: OnCharacteristicChangeCallback): OnCallback?
}

open class BleDeviceIdentifier(val address: String) : DeviceIdentifier {
    override val driver: String
        get() = "BLE"
}

open class OnCallback
data class OnReadRemoteRssiCallback(val gatt: BluetoothGatt?, val rssi: Int, val status: Int) : OnCallback()
data class OnCharacteristicReadCallback(val gatt: BluetoothGatt?, val characteristic: BluetoothGattCharacteristic?, val status: Int, val value: ByteArray?) : OnCallback()
data class OnCharacteristicWriteCallback(val gatt: BluetoothGatt?, val characteristic: BluetoothGattCharacteristic?, val status: Int) : OnCallback()
data class OnDescriptorWriteCallback(val gatt: BluetoothGatt?, val descriptor: BluetoothGattDescriptor?, val status: Int) : OnCallback()
data class OnCharacteristicChangeCallback(val gatt: BluetoothGatt?, val characteristic: BluetoothGattCharacteristic?, val value: ByteArray?) : OnCallback()
