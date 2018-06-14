package au.com.shrinkray.bluetooth.driver.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanRecord
import android.content.Context
import au.com.shrinkray.bluetooth.driver.DeviceChannel
import au.com.shrinkray.bluetooth.driver.DeviceException
import au.com.shrinkray.bluetooth.driver.DeviceIdentifier
import au.com.shrinkray.bluetooth.driver.DeviceStatus
import au.com.shrinkray.bluetooth.driver.ble.SimbleeDevice.Companion.READ_NOTIFY_UUID
import au.com.shrinkray.bluetooth.driver.ble.SimbleeDevice.Companion.SERVICE_UUID
import au.com.shrinkray.bluetooth.driver.ble.SimbleeDevice.Companion.WRITE_UUID
import au.com.shrinkray.bluetooth.driver.protocol.NOTIFICATION_COLOR_CHANGE
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

class SimbleeDeviceFactory : BleDeviceFactory() {
    override fun matchesScanRecord(scanRecord: ScanRecord): Boolean =
            scanRecord.deviceName == "SIMBLEE"

    override fun createDeviceIdentifier(scanRecord: ScanRecord): SimbleeDeviceIdentifier =
            SimbleeDeviceIdentifier(scanRecord.deviceName)

    override fun createDevice(context: Context, device: BluetoothDevice, deviceIdentifier: BleDeviceIdentifier): SimbleeDevice =
            SimbleeDevice(context, deviceIdentifier as SimbleeDeviceIdentifier, device)

    override fun matchesDeviceIdentifier(deviceIdentifier: DeviceIdentifier): Boolean =
            deviceIdentifier is SimbleeDeviceIdentifier
}


class SimbleeDevice(context: Context, simbleeDeviceIdentifier: SimbleeDeviceIdentifier, device: BluetoothDevice) : BleDevice(context, simbleeDeviceIdentifier, device) {

    companion object {
        // Simblee Service UUID
        val SERVICE_UUID: UUID = UUID.fromString("fe84-0000-1000-8000-00805f9b34fb")

        // Characteristic for reading and getting notifications from "SimbleeBLE.send(char *data, int len)" calls.
        val READ_NOTIFY_UUID: UUID = UUID.fromString("2d30c082-f39f-4ce6-923f-3484ea480596")

        // Characteristic to write to received in "SimbleeBLE_onReceive(char *data, int len)"
        val WRITE_UUID: UUID = UUID.fromString("2d30c083-f39f-4ce6-923f-3484ea480596")

        // BLE standard UUID.
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    }

    init {
        statusObservable.observeOn(Schedulers.io()).subscribe {
            when (it) {
                DeviceStatus.DISCONNECTED -> {
                    defaultChannel = null
                }
                DeviceStatus.CONNECTED -> {

                }
                DeviceStatus.SERVICES_DISCOVERED -> {
                    // TODO: Hacky way to enable notifications for a characteristic... we should create a method...
                    bluetoothGatt?.getService(SERVICE_UUID)
                            ?.getCharacteristic(READ_NOTIFY_UUID)
                            ?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)?.apply {
                                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                bluetoothGatt?.writeDescriptor(this)
                            }
                    callbackQueue.poll(3, TimeUnit.SECONDS)
                    defaultChannel = SimbleeChannel(bluetoothGatt!!, scheduler, notifyScheduler, callbackQueue)
                    statusSubject.onNext(DeviceStatus.READY)
                }
                DeviceStatus.READY -> {
                    // Do something when the device is ready?
                }
            }
        }
    }

    private var defaultChannel: SimbleeChannel? = null

    override fun processChangeCallback(callback: OnCharacteristicChangeCallback): OnCallback? = when {
        callback.value?.isEmpty() == false && callback.value[0] == NOTIFICATION_COLOR_CHANGE -> {
            defaultChannel?.notificationSubject?.onNext(callback.value)
            null
        }
        else -> callback
    }

    override fun getChannel(channelId: String?): SimbleeChannel? = defaultChannel
}


class SimbleeChannel(private val bluetoothGatt: BluetoothGatt,
                     override val scheduler: Scheduler,
                     override val notificationScheduler: Scheduler,
                     private val callbackQueue: BlockingQueue<OnCallback>) : DeviceChannel {
    override val maximumPacketSize: Int
        get() = 20

    private val service = bluetoothGatt.getService(SERVICE_UUID)

    private val readNotifyCharacteristic = service?.getCharacteristic(READ_NOTIFY_UUID)
    private val writeCharacteristic = service?.getCharacteristic(WRITE_UUID)

    val notificationSubject: PublishSubject<ByteArray> = PublishSubject.create<ByteArray>()

    override fun notifications(): Observable<ByteArray> = notificationSubject

    override fun writeWithReturnValue(byteArray: ByteArray): Maybe<ByteArray> = Maybe.fromCallable {
        doWriteWithReturnValue(byteArray)
    }

    private fun doWriteWithReturnValue(byteArray: ByteArray): ByteArray? {
        if (writeCharacteristic != null) {
            writeCharacteristic.value = byteArray
            if (bluetoothGatt.writeCharacteristic(writeCharacteristic)) {
                try {
                    val callback = callbackQueue.poll(10, TimeUnit.SECONDS)
                    if (callback is OnCharacteristicWriteCallback) {
                        when (callback.status) {
                            BluetoothGatt.GATT_SUCCESS -> {
                                val responseCallback = callbackQueue.poll(10, TimeUnit.SECONDS)
                                if (responseCallback is OnCharacteristicChangeCallback &&
                                        responseCallback.characteristic?.uuid == readNotifyCharacteristic?.uuid) {
                                    return responseCallback.value
                                }
                                throw DeviceException("Unexpected callback: $callback")
                            }
                        }
                    }
                } catch (ie: InterruptedException) {
                    throw DeviceException("Timeout waiting for write response or notification...")
                }
            }
        }
        throw DeviceException("Unexpected error in writeWithReturnValue...")
    }

    override fun write(byteArray: ByteArray): Completable = Completable.fromAction { doWrite(byteArray) }.subscribeOn(scheduler)

    private fun doWrite(byteArray: ByteArray) {
        writeCharacteristic?.also { characteristic ->
            characteristic.value = byteArray
            val success = bluetoothGatt.writeCharacteristic(characteristic)
            if (success) {
                try {
                    val callback = callbackQueue.poll(10, TimeUnit.SECONDS)
                    if (callback is OnCharacteristicWriteCallback &&
                            callback.characteristic?.uuid == writeCharacteristic.uuid) {
                        when (callback.status) {
                            BluetoothGatt.GATT_SUCCESS -> {
                                return
                            }
                            else -> throw DeviceException("Unexpected GATT status in callback: ${callback.status}")
                        }
                    }
                } catch (ie: InterruptedException) {
                    throw DeviceException("Timeout waiting for write response...")
                }
            }
        }
        throw DeviceException("Could not locate characteristic...")
    }

    override fun read(): Maybe<ByteArray> = Maybe.fromCallable { doRead() }

    private fun doRead(): ByteArray? {
        readNotifyCharacteristic?.also { characteristic ->
            val success = bluetoothGatt.readCharacteristic(characteristic)
            if (success) {
                try {
                    val callback = callbackQueue.poll(10, TimeUnit.SECONDS)
                    if (callback is OnCharacteristicReadCallback) {
                        when (callback.status) {
                            BluetoothGatt.GATT_SUCCESS -> {
                                return callback.value
                            }
                            else -> throw DeviceException("Unknown gatt status ${callback.status}!")
                        }
                    }
                } catch (ie: InterruptedException) {
                    throw DeviceException("Timeout waiting for read response...")
                }
            }
        }
        throw DeviceException("Could not locate characteristic...")
    }
}

class SimbleeDeviceIdentifier(address: String) : BleDeviceIdentifier(address)
