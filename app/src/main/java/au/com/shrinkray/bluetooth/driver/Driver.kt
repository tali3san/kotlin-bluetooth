package au.com.shrinkray.bluetooth.driver

import io.reactivex.Observable

interface Driver {
    fun search(includePreviouslyFound: Boolean): Observable<Device>
    fun fetchDevice(deviceIdentifier: DeviceIdentifier): Device?
    fun statusObservable(): Observable<DriverStatus>
}

enum class DriverStatus {
    AVAILABLE,
    UNAVAILABLE
}

