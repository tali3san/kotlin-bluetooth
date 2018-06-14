package au.com.shrinkray.bluetooth.driver.protocol

import android.graphics.Color
import android.support.annotation.ColorInt
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import au.com.shrinkray.bluetooth.driver.DeviceChannel
import io.reactivex.Observable
import kotlinx.coroutines.experimental.rx2.await
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

// Placeholder file for Simblee related protocol stuff. This is the protocol based on the channel implementation, sending/receiving bytes.

// On response to the read color command.
const val NOTIFICATION_READ_COLOR_RESULT = 0x02.toByte()

// OOB response to any color change.
const val NOTIFICATION_COLOR_CHANGE = 0xFF.toByte()

const val ARG_INDEX_COMMAND = 0

// Set the color.  Will cause a notification color change.
const val COMMAND_SET_COLOR = 0x01.toByte()
const val LENGTH_COMMAND_SET_COLOR = 4
const val ARG_INDEX_RED = 1
const val ARG_INDEX_BLUE = 2
const val ARG_INDEX_GREEN = 3

// Read the current color. Will return the current color the
const val COMMAND_READ_COLOR = 0x02.toByte()
const val LENGTH_COMMAND_READ_COLOR = 1

// Flash colors...
const val COMMAND_FLASH_COLOR_HEADER = 0x03.toByte()
const val LENGTH_COMMAND_FLASH_COLOR_HEADER = 5
const val ARG_INDEX_DATA_LENGTH = 1

const val COMMAND_FLASH_COLOR_WRITE_DATA = 0x04.toByte()
const val ARG_INDEX_BLOCK_INDEX = 1
const val ARG_INDEX_BLOCK_LENGTH = 2
const val ARG_INDEX_BLOCK_DATA = 3

suspend fun DeviceChannel.setColor(red: Byte, green: Byte, blue: Byte) {
    val command = ByteArray(LENGTH_COMMAND_SET_COLOR)
    command[ARG_INDEX_COMMAND] = COMMAND_SET_COLOR
    command[ARG_INDEX_RED] = red
    command[ARG_INDEX_GREEN] = green
    command[ARG_INDEX_BLUE] = blue
    write(command).subscribeOn(scheduler).await()
}

suspend fun DeviceChannel.setColor(@ColorInt color: Int) {
    setColor(color.red.toByte(), color.green.toByte(), color.blue.toByte())
}

suspend fun DeviceChannel.readColor() {
    val command = ByteArray(LENGTH_COMMAND_READ_COLOR)
    command[ARG_INDEX_COMMAND] = COMMAND_READ_COLOR
    writeWithReturnValue(command).subscribeOn(scheduler).await()
}

suspend fun DeviceChannel.flashColors(colorFlashes: Array<ColorFlash>) {
    // Each color flash is 3 bytes for the color + 4 bytes for the time in Milliseconds to keep that color.
    // For simplicity, we are going to say each block is 7 bytes.
    val command = ByteArray(LENGTH_COMMAND_FLASH_COLOR_HEADER)
    command[ARG_INDEX_COMMAND] = COMMAND_FLASH_COLOR_HEADER
    val data = ByteArrayOutputStream().let { outputStream ->
        colorFlashes.forEach { it.writeToOutputStream(outputStream) }
        outputStream.close()
        outputStream.toByteArray()
    }
    command.writeToIndex(ARG_INDEX_DATA_LENGTH, data.size.toLittleEndianBytes())
    val maxBlockSize = maximumPacketSize - 3
    var readIndex = 0
    var blockIndex = 0
    while (readIndex < data.size) {
        val blockSize = min(maxBlockSize, data.size - readIndex)
        val writeDataCommand = ByteArray(3 + blockSize)
        writeDataCommand[ARG_INDEX_COMMAND] = COMMAND_FLASH_COLOR_WRITE_DATA
        writeDataCommand[ARG_INDEX_BLOCK_INDEX] = blockIndex.toByte()
        writeDataCommand[ARG_INDEX_BLOCK_LENGTH] = blockSize.toByte()
        data.writeFrom(readIndex, ARG_INDEX_BLOCK_DATA, data)
        blockIndex++
        readIndex += blockSize
        writeWithReturnValue(writeDataCommand).subscribeOn(scheduler).await()
    }
}

data class ColorFlash(@ColorInt val color: Int, val timeInMillis: Long) {
    fun writeToOutputStream(outputStream: OutputStream) {
        val buffer = ByteBuffer.wrap(ByteArray(7)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(color.red.toByte())
        buffer.put(color.green.toByte())
        buffer.put(color.blue.toByte())
        buffer.putLong(timeInMillis)
        outputStream.write(buffer.array())
    }
}

fun DeviceChannel.colorChangeNotifications(): Observable<Int> =
        this.notifications()
                .filter { it.size >= 4 && it[0] == NOTIFICATION_COLOR_CHANGE }
                .map {
                    Color.rgb(it[1].toUInt(), it[2].toUInt(), it[3].toUInt())
                }


fun Byte.toUInt() = this.toInt() and 0xFF

fun Int.toLittleEndianBytes(): ByteArray = ByteArray(java.lang.Integer.SIZE / java.lang.Byte.SIZE).apply {
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(this@toLittleEndianBytes)
}

fun Long.toLittleEndianBytes(): ByteArray = ByteArray(java.lang.Long.SIZE / java.lang.Byte.SIZE).apply {
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(this@toLittleEndianBytes)
}

fun ByteArray.writeToIndex(index: Int, bytes: ByteArray) {
    var readIndex = 0
    while (readIndex < bytes.size) this[index] = bytes[readIndex++]
}

fun ByteArray.writeFrom(fromIndex: Int, destinationIndex: Int, destination: ByteArray) {
    var fromIndex = fromIndex
    var destinationIndex = destinationIndex
    while (destinationIndex < destination.size) {
        destination[destinationIndex] = this[fromIndex]
        fromIndex++
        destinationIndex++
    }
}