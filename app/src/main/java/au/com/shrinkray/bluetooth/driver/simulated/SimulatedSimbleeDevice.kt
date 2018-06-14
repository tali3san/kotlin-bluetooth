package au.com.shrinkray.bluetooth.driver.simulated

import au.com.shrinkray.bluetooth.driver.DeviceIdentifier
import au.com.shrinkray.bluetooth.driver.DeviceStatus
import au.com.shrinkray.bluetooth.driver.protocol.COMMAND_READ_COLOR
import au.com.shrinkray.bluetooth.driver.protocol.COMMAND_SET_COLOR
import au.com.shrinkray.bluetooth.driver.protocol.NOTIFICATION_COLOR_CHANGE
import au.com.shrinkray.bluetooth.driver.protocol.NOTIFICATION_READ_COLOR_RESULT
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.schedulers.Schedulers
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SimulatedSimbleeDevice(identifier: SimulatedDeviceIdentifier) : SimulatedDevice(identifier) {

    private val simulatedDeviceChannel = SimulatedSimbleeDeviceChannel()

    override val defaultDeviceChannel: SimulatedDeviceChannel
        get() = simulatedDeviceChannel

    override fun connect(): Completable = Completable.fromAction {
        if (statusSubject.value == DeviceStatus.DISCONNECTED) {
            statusSubject.onNext(DeviceStatus.CONNECTED)
            statusSubject.onNext(DeviceStatus.SERVICES_DISCOVERED)
            statusSubject.onNext(DeviceStatus.READY)
        }
    }

    override fun disconnect(): Completable = Completable.fromAction {
        if (statusSubject.value != DeviceStatus.DISCONNECTED) {
            statusSubject.onNext(DeviceStatus.DISCONNECTED)
        }
    }

    override fun cleanUp(): Completable = Completable.fromAction {

    }
}

class SimulatedSimbleeDeviceFactory : SimulatedDeviceFactory() {

    override fun matchesDeviceIdentifier(deviceIdentifier: DeviceIdentifier): Boolean {
        return deviceIdentifier is SimulatedSimbleeDeviceIdentifier
    }

    override fun createDevice(deviceIdentifier: DeviceIdentifier): SimulatedDevice {
        return SimulatedSimbleeDevice(deviceIdentifier as SimulatedDeviceIdentifier)
    }
}

data class SimulatedSimbleeDeviceIdentifier(override val driver: String, val uuid: UUID) : SimulatedDeviceIdentifier()

class SimulatedSimbleeDeviceChannel : SimulatedDeviceChannel() {

    override val scheduler = Schedulers.from(Executors.newSingleThreadExecutor())
    override val notificationScheduler = Schedulers.from(Executors.newSingleThreadExecutor())
    override val maximumPacketSize= 20

    var red: Byte = 0x00
    var green: Byte = 0x00
    var blue: Byte = 0x00

    override fun writeWithReturnValue(byteArray: ByteArray): Maybe<ByteArray> =
            Maybe.fromCallable<ByteArray> {
                when {
                    byteArray.size == 1 && byteArray[0] == COMMAND_READ_COLOR -> byteArrayOf(NOTIFICATION_READ_COLOR_RESULT, red, green, blue)
                    byteArray.size == 4 && byteArray[0] == COMMAND_SET_COLOR -> {
                        notificationSubject.onNext(byteArrayOf(NOTIFICATION_COLOR_CHANGE, red, green, blue))
                        null
                    }
                    else -> null
                }
            }.delay(100, TimeUnit.MILLISECONDS)

    override fun write(byteArray: ByteArray): Completable {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun read(): Maybe<ByteArray> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}