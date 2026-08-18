package io.linuxdroid.app.vnc

import android.graphics.Bitmap
import android.graphics.Rect
import io.linuxdroid.app.data.VncProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Small internal RFB 3.3/3.7/3.8 client for LinuxDroid's loopback VNC server.
 * It deliberately accepts only None and classic VNC password authentication.
 */
class RfbClient(private val profile: VncProfile, private val listener: Listener) {
    interface Listener {
        fun onConnected(desktopName: String, width: Int, height: Int)
        fun onFramebuffer(bitmap: Bitmap)
        fun onClipboard(text: String)
        fun onBell()
        fun onDisconnected(error: Throwable?)
    }

    private val writeLock = Any()
    private val outboundPackets = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var terminalError: Throwable? = null
    private var framebuffer: Bitmap? = null
    private var width = 0
    private var height = 0

    fun connect() {
        check(!running) { "VNC connection is already active." }
        outboundPackets.clear()
        terminalError = null
        running = true
        thread(name = "LinuxDroid-RFB", isDaemon = true) {
            var failure: Throwable? = null
            try {
                Socket().also { candidate ->
                    socket = candidate
                    candidate.tcpNoDelay = true
                    candidate.connect(InetSocketAddress(profile.host, profile.port), 10_000)
                    val input = DataInputStream(BufferedInputStream(candidate.getInputStream()))
                    val dataOutput = DataOutputStream(BufferedOutputStream(candidate.getOutputStream()))
                    output = dataOutput
                    handshake(input, dataOutput)
                    eventLoop(input, dataOutput)
                }
            } catch (error: Throwable) {
                if (running) failure = error
            } finally {
                running = false
                output = null
                outboundPackets.clear()
                runCatching { socket?.close() }
                socket = null
                listener.onDisconnected(terminalError ?: failure)
            }
        }
    }

    fun disconnect() {
        running = false
        outboundPackets.clear()
        runCatching { socket?.close() }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        writePacket {
            writeByte(4)
            writeByte(if (down) 1 else 0)
            writeShort(0)
            writeInt(keysym)
        }
    }

    fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        writePacket {
            writeByte(5)
            writeByte(buttonMask)
            writeShort(x.coerceIn(0, width.coerceAtLeast(1) - 1))
            writeShort(y.coerceIn(0, height.coerceAtLeast(1) - 1))
        }
    }

    fun sendClipboard(text: String) {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        writePacket {
            writeByte(6)
            writeByte(0); writeByte(0); writeByte(0)
            writeInt(utf8.size)
            write(utf8)
        }
    }

    private fun handshake(input: DataInputStream, dataOutput: DataOutputStream) {
        val serverVersion = ByteArray(12).also(input::readFully).toString(StandardCharsets.US_ASCII)
        require(serverVersion.startsWith("RFB ")) { "Server is not an RFB/VNC server." }
        val minor = serverVersion.substring(8, 11).toIntOrNull() ?: 3
        val selectedMinor = when {
            minor >= 8 -> 8
            minor >= 7 -> 7
            else -> 3
        }
        dataOutput.write("RFB 003.${selectedMinor.toString().padStart(3, '0')}\n".toByteArray(StandardCharsets.US_ASCII))
        dataOutput.flush()

        val security = if (selectedMinor == 3) input.readInt() else chooseSecurityType(input, dataOutput)
        when (security) {
            1 -> Unit
            2 -> authenticateVnc(input, dataOutput)
            0 -> error(readFailureReason(input))
            else -> error("Unsupported VNC security type: $security")
        }
        if (selectedMinor >= 7 || security == 2) require(input.readInt() == 0) { readFailureReason(input) }

        dataOutput.writeByte(1) // shared desktop
        dataOutput.flush()
        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        input.skipBytes(16) // server pixel format; request our own below
        val nameLength = input.readInt()
        require(nameLength in 0..1_048_576) { "Invalid VNC desktop name." }
        val desktopName = ByteArray(nameLength).also(input::readFully).toString(StandardCharsets.UTF_8)
        framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        startWriter(dataOutput)
        setPixelFormat(dataOutput)
        setEncodings(dataOutput)
        framebufferRequest(dataOutput, false)
        listener.onConnected(desktopName, width, height)
    }

    private fun chooseSecurityType(input: DataInputStream, dataOutput: DataOutputStream): Int {
        val count = input.readUnsignedByte()
        if (count == 0) error(readFailureReason(input))
        val offered = ByteArray(count).also(input::readFully).map { it.toInt() and 0xFF }
        val chosen = when {
            profile.password.isNotEmpty() && 2 in offered -> 2
            1 in offered -> 1
            else -> error("VNC server does not offer a supported authentication method.")
        }
        dataOutput.writeByte(chosen)
        dataOutput.flush()
        return chosen
    }

    private fun authenticateVnc(input: DataInputStream, dataOutput: DataOutputStream) {
        require(profile.password.isNotEmpty()) { "This VNC server requires a password." }
        val challenge = ByteArray(16).also(input::readFully)
        dataOutput.write(vncEncryptChallenge(challenge, profile.password))
        dataOutput.flush()
    }

    private fun eventLoop(input: DataInputStream, dataOutput: DataOutputStream) {
        while (running) {
            when (input.readUnsignedByte()) {
                0 -> readFramebufferUpdate(input, dataOutput)
                2 -> listener.onBell()
                3 -> {
                    input.skipBytes(3)
                    val length = input.readInt()
                    require(length in 0..10_485_760) { "Invalid VNC clipboard payload." }
                    listener.onClipboard(ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8))
                }
                else -> error("Unsupported VNC server message.")
            }
        }
    }

    private fun readFramebufferUpdate(input: DataInputStream, dataOutput: DataOutputStream) {
        input.readUnsignedByte()
        val rectangleCount = input.readUnsignedShort()
        repeat(rectangleCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val rectWidth = input.readUnsignedShort()
            val rectHeight = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                0 -> readRawRectangle(input, x, y, rectWidth, rectHeight)
                1 -> copyRectangle(input, x, y, rectWidth, rectHeight)
                -223 -> resizeFramebuffer(rectWidth, rectHeight)
                else -> error("Unsupported VNC encoding $encoding")
            }
        }
        framebuffer?.let(listener::onFramebuffer)
        framebufferRequest(dataOutput, true)
    }

    private fun readRawRectangle(input: DataInputStream, x: Int, y: Int, rectWidth: Int, rectHeight: Int) {
        require(x + rectWidth <= width && y + rectHeight <= height) { "VNC rectangle is outside framebuffer." }
        val pixelCount = rectWidth * rectHeight
        require(pixelCount in 0..8_388_608) { "VNC rectangle is too large." }
        val raw = ByteArray(pixelCount * 4).also(input::readFully)
        val pixels = IntArray(pixelCount)
        var rawIndex = 0
        for (index in pixels.indices) {
            val blue = raw[rawIndex++].toInt() and 0xFF
            val green = raw[rawIndex++].toInt() and 0xFF
            val red = raw[rawIndex++].toInt() and 0xFF
            rawIndex++
            pixels[index] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        framebuffer?.setPixels(pixels, 0, rectWidth, x, y, rectWidth, rectHeight)
    }

    private fun copyRectangle(input: DataInputStream, x: Int, y: Int, rectWidth: Int, rectHeight: Int) {
        val sourceX = input.readUnsignedShort()
        val sourceY = input.readUnsignedShort()
        val image = framebuffer ?: return
        val snapshot = Bitmap.createBitmap(image, sourceX, sourceY, rectWidth, rectHeight)
        val canvas = android.graphics.Canvas(image)
        canvas.drawBitmap(snapshot, x.toFloat(), y.toFloat(), null)
        snapshot.recycle()
    }

    private fun resizeFramebuffer(newWidth: Int, newHeight: Int) {
        require(newWidth > 0 && newHeight > 0 && newWidth * newHeight <= 16_777_216) { "Unsafe VNC desktop dimensions." }
        width = newWidth
        height = newHeight
        framebuffer?.recycle()
        framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun setPixelFormat(output: DataOutputStream) = writePacket {
        writeByte(0); writeByte(0); writeByte(0); writeByte(0)
        writeByte(32); writeByte(24); writeByte(0); writeByte(1)
        writeShort(255); writeShort(255); writeShort(255)
        writeByte(16); writeByte(8); writeByte(0)
        writeByte(0); writeByte(0); writeByte(0)
    }

    private fun setEncodings(output: DataOutputStream) = writePacket {
        writeByte(2); writeByte(0); writeShort(3)
        writeInt(0) // Raw
        writeInt(1) // CopyRect
        writeInt(-223) // DesktopSize
    }

    private fun framebufferRequest(output: DataOutputStream, incremental: Boolean) = writePacket {
        writeByte(3); writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
    }

    /**
     * Serializes a packet without performing socket I/O on the caller thread.
     * Android invokes touch callbacks on the main thread, where direct socket writes
     * raise NetworkOnMainThreadException and used to close VNC on the first touch.
     */
    private fun writePacket(block: DataOutputStream.() -> Unit) {
        if (!running || output == null) return
        val packet = runCatching {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { stream -> stream.block() }
            buffer.toByteArray()
        }.getOrElse { error ->
            abort(error)
            return
        }
        outboundPackets.offer(packet)
    }

    private fun startWriter(destination: DataOutputStream) {
        thread(name = "LinuxDroid-RFB-writer", isDaemon = true) {
            try {
                while (running) {
                    val packet = outboundPackets.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    synchronized(writeLock) {
                        destination.write(packet)
                        destination.flush()
                    }
                }
            } catch (error: Throwable) {
                if (running) abort(error)
            }
        }
    }

    private fun abort(error: Throwable) {
        terminalError = error
        running = false
        outboundPackets.clear()
        output = null
        runCatching { socket?.close() }
    }

    private fun readFailureReason(input: DataInputStream): String {
        val length = input.readInt()
        return if (length in 0..1_048_576) ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8) else "VNC authentication failed."
    }

    private fun vncEncryptChallenge(challenge: ByteArray, password: String): ByteArray {
        return BvncCompatibility.encryptClassicVncChallenge(challenge, password)
    }
}
