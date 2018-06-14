package au.com.shrinkray.bluetooth.driver.simulated

import au.com.shrinkray.bluetooth.driver.Device
import au.com.shrinkray.bluetooth.driver.DeviceIdentifier
import au.com.shrinkray.bluetooth.driver.Driver
import au.com.shrinkray.bluetooth.driver.DriverStatus
import io.reactivex.Observable
import io.reactivex.disposables.Disposables
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject

open class SimulatedDriver : Driver {

    private val driverStatusSubject: BehaviorSubject<DriverStatus> = BehaviorSubject.create()

    private val simulatedDevicesByIdentifier = mutableMapOf<DeviceIdentifier, SimulatedDevice>()
    private val simulatedDevicesSubject: PublishSubject<SimulatedDevice> = PublishSubject.create()

    private val simulatedDeviceFactories = mutableSetOf<SimulatedDeviceFactory>()

    override fun search(includePreviouslyFound: Boolean): Observable<Device> =
            Observable.create<Device> { emitter ->
                if (includePreviouslyFound) {
                    simulatedDevicesByIdentifier.values.forEach { emitter.onNext(it) }
                }
                val disposable = simulatedDevicesSubject.subscribe { emitter.onNext(it) }
                emitter.setDisposable(Disposables.fromAction {
                    disposable.dispose()
                })
            }

    override fun fetchDevice(deviceIdentifier: DeviceIdentifier): Device? {
        var device = simulatedDevicesByIdentifier[deviceIdentifier]
        if (device == null) {
            factoryDevice(deviceIdentifier)?.let {
                device = it
                simulatedDevicesByIdentifier[deviceIdentifier] = it
            }
        }
        return device
    }

    override fun statusObservable(): Observable<DriverStatus> = driverStatusSubject

    private fun factoryDevice(deviceIdentifier: DeviceIdentifier): SimulatedDevice? {
        return simulatedDeviceFactories.firstOrNull { it.matchesDeviceIdentifier(deviceIdentifier) }?.createDevice(deviceIdentifier)
    }
}

// BLE device factory... implement this to create a device factory individual types of devices based on
abstract class SimulatedDeviceFactory {
    abstract fun matchesDeviceIdentifier(deviceIdentifier: DeviceIdentifier): Boolean
    abstract fun createDevice(deviceIdentifier: DeviceIdentifier): SimulatedDevice
}