package au.com.shrinkray.bluetooth.driver

import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler

interface Device {
    val identifier: DeviceIdentifier
    val statusObservable: Observable<DeviceStatus>
    fun getChannel(channelId: String? = null): DeviceChannel?
    fun connect(): Completable
    fun disconnect(): Completable
    fun cleanUp(): Completable
}

interface DeviceChannel {
    val scheduler: Scheduler
    val notificationScheduler: Scheduler
    val maximumPacketSize: Int
    fun writeWithReturnValue(byteArray: ByteArray): Maybe<ByteArray>
    fun write(byteArray: ByteArray): Completable
    fun read(): Maybe<ByteArray>
    fun notifications(): Observable<ByteArray>
}

interface DeviceIdentifier {
    val driver: String
}

enum class DeviceStatus {
    DISCONNECTED,
    CONNECTED,
    SERVICES_DISCOVERED,
    READY
}

class DeviceException @JvmOverloads constructor(message: String? = null, cause: Throwable? = null) : RuntimeException()
