package au.com.shrinkray.bluetooth.driver.simulated

import au.com.shrinkray.bluetooth.driver.Device
import au.com.shrinkray.bluetooth.driver.DeviceChannel
import au.com.shrinkray.bluetooth.driver.DeviceIdentifier
import au.com.shrinkray.bluetooth.driver.DeviceStatus
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject

abstract class SimulatedDevice(override val identifier: DeviceIdentifier) : Device {

    protected val statusSubject: BehaviorSubject<DeviceStatus> = BehaviorSubject.createDefault(DeviceStatus.DISCONNECTED)

    abstract val defaultDeviceChannel: SimulatedDeviceChannel

    override val statusObservable: Observable<DeviceStatus>
        get() = statusSubject

    override fun getChannel(channelId: String?): DeviceChannel? = defaultDeviceChannel
}

abstract class SimulatedDeviceChannel : DeviceChannel {

    protected val notificationSubject: PublishSubject<ByteArray> = PublishSubject.create<ByteArray>()

    override fun notifications(): Observable<ByteArray> = notificationSubject
}

abstract class SimulatedDeviceIdentifier : DeviceIdentifier

