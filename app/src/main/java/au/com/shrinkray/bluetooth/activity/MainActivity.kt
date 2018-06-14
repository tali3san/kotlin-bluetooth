package au.com.shrinkray.bluetooth.activity

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import au.com.shrinkray.bluetooth.R
import au.com.shrinkray.bluetooth.driver.Device
import au.com.shrinkray.bluetooth.driver.DeviceStatus
import au.com.shrinkray.bluetooth.driver.ble.*
import au.com.shrinkray.bluetooth.driver.protocol.setColor
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.JobCancellationException
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.rx2.await
import java.util.concurrent.TimeUnit

const val PERMISSIONS_REQUEST_FINE_LOCATION = 1213

class MainActivity : AppCompatActivity() {

    // We'd normally inject this.
    private lateinit var bleDriver: BleDriver

    init {

    }

    var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = this.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager

        bleDriver = BleDriver(this, bluetoothManager.adapter).apply {
            // Add any device factories we're supporting...
            factories.add(SimbleeDeviceFactory())
        }

        btn_pill_button.isEnabled = false
        btn_pill_button.setOnClickListener { button ->
            job = launch(UI) {
                button.isEnabled = false
                flashLEDs()
                button.isEnabled = true
            }
        }

        onRequestLocationPermissions()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private suspend fun flashLEDs() {
        // Surround the whole thing with a try catch... for either an exception or Job cancellation.
        try {
            // Get the first device...
            val device = bleDriver.search(true)
                    .cast(SimbleeDevice::class.java)
                    .firstOrError()
                    .timeout(4, TimeUnit.SECONDS)
                    .await()
            try {
                // Connect it...
                device.connect().await()
                // Wait for it to be ready... (i.e. it has completed all the necessary service discovery and setup).
                device.awaitStatus(DeviceStatus.READY,4,TimeUnit.SECONDS)
                // Flash the LED...
                device.getChannel()?.apply {
                    for (v in 1..3) {
                        flashLED(numberOfTimes = 4, timeOnMs = v * 50L + 200L, timeOffMs = 200L)
                    }
                }
                // Disconnect...
                device.disconnect().await()
                // Wait for the device to actually register it has disconnected.
                device.awaitStatus(DeviceStatus.DISCONNECTED,4,TimeUnit.SECONDS)
            } finally {
                // If we have an exception, we want to try and cleanUp any resources.
                device?.cleanUp()?.await()
            }
        } catch (throwable: JobCancellationException) {
            // Do nothing, we were cancelled normally...
        } catch (throwable: Throwable) {
            Log.e("MainActivity", "Exception in flashLEDs()", throwable)
        }
    }

    private suspend fun Device.awaitStatus(statusToAwait: DeviceStatus, timeout: Long, timeUnit: TimeUnit) {
        this.statusObservable
                .filter { status ->
                    status == statusToAwait
                }
                .firstOrError()
                .timeout(timeout, timeUnit)
                .toCompletable()
                .await()
    }

    private suspend fun SimbleeChannel.flashLED(numberOfTimes: Int, timeOnMs: Long, timeOffMs: Long) {
        for (n in 1..numberOfTimes) {
            setColor(Color.RED)
            delay(timeOnMs)
            setColor(Color.BLACK)
            delay(timeOffMs)
        }
    }

    private fun onPermissionsGranted() {
        // Do nothing in particular...
        btn_pill_button.isEnabled = true
    }

    private fun onRequestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Async dialog here.
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSIONS_REQUEST_FINE_LOCATION)
            }
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_FINE_LOCATION -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    onPermissionsGranted()
                } else {
                    // Permission denied :-( we should deal with this as per Google's docs. in this case, we probably
                    // just want to disable the button and close the
                }
                return
            }
            else -> {
                // Ignore all other requests.
            }
        }
    }
}
