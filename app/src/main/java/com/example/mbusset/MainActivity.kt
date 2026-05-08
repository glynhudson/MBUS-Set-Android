package com.example.mbusset // REPLACE WITH YOUR ACTUAL PACKAGE NAME

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.example.mbusconfig.USB_PERMISSION"
    private lateinit var tvStatus: TextView
    private var usbPort: UsbSerialPort? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        val etOldAddress = findViewById<EditText>(R.id.etOldAddress)
        val etNewAddress = findViewById<EditText>(R.id.etNewAddress)
        val btnSetAddress = findViewById<Button>(R.id.btnSetAddress)

        btnSetAddress.setOnClickListener {
            // If the text is empty, it uses the "or" value (254 or 1)
            val oldAddr = etOldAddress.text.toString().toIntOrNull() ?: 254
            val newAddr = etNewAddress.text.toString().toIntOrNull() ?: 1

            startMbusSequence(oldAddr, newAddr)
        }

        // Register receiver for USB permission callbacks
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        btnSetAddress.setOnClickListener {
            val oldAddr = etOldAddress.text.toString().toIntOrNull()
            val newAddr = etNewAddress.text.toString().toIntOrNull()

            if (oldAddr != null && newAddr != null) {
                startMbusSequence(oldAddr, newAddr)
            } else {
                log("Please enter valid numeric addresses.")
            }
        }
    }

    private fun startMbusSequence(oldAddr: Int, newAddr: Int) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            log("No USB Serial device found. Check OTG connection.")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            log("Requesting USB Permission...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, permissionIntent)
            return
        }

        // Run the communication on a background thread so we don't freeze the UI
        thread {
            executeMbusCommands(manager, driver.ports[0], oldAddr, newAddr)
        }
    }

    private fun executeMbusCommands(manager: UsbManager, port: UsbSerialPort, oldAddr: Int, newAddr: Int) {
        try {
            val connection = manager.openDevice(port.device) ?: throw Exception("Failed to open connection")

            port.open(connection)
            // M-Bus defaults: 2400 baud, 8 data bits, Even parity, 1 stop bit
            port.setParameters(2400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)

            // 1. Check old address
            logOnUi("Pinging meter at address $oldAddr...")
            sendShortFrame(port, oldAddr, 0x40)
            if (!waitForAck(port)) {
                logOnUi("ERROR: No ACK from address $oldAddr. Aborting.")
                port.close()
                return
            }

            // 2. Set new address
            logOnUi("Sending address change command ($oldAddr -> $newAddr)...")
            sendSetAddressFrame(port, oldAddr, newAddr)
            if (!waitForAck(port)) {
                logOnUi("ERROR: Address change failed (No ACK).")
                port.close()
                return
            }

            // 3. Verify new address
            Thread.sleep(500)
            logOnUi("Verifying new address $newAddr...")
            sendShortFrame(port, newAddr, 0x40)
            if (waitForAck(port)) {
                logOnUi("SUCCESS! Meter is now at address $newAddr.")
            } else {
                logOnUi("WARNING: No reply on new address.")
            }

            port.close()
        } catch (e: Exception) {
            logOnUi("Exception: ${e.message}")
        }
    }

    // --- M-Bus Protocol Functions ---

    private fun calculateChecksum(data: IntArray): Byte {
        return (data.sum() % 256).toByte()
    }

    private fun sendShortFrame(port: UsbSerialPort, address: Int, controlByte: Int) {
        val checksum = calculateChecksum(intArrayOf(controlByte, address))
        val frame = byteArrayOf(0x10, controlByte.toByte(), address.toByte(), checksum, 0x16)
        port.write(frame, 1000)
    }

    private fun sendSetAddressFrame(port: UsbSerialPort, oldAddr: Int, newAddr: Int) {
        val cField = 0x53
        val ciField = 0x51
        val body = intArrayOf(cField, oldAddr, ciField, newAddr)
        val checksum = calculateChecksum(body)

        val length = body.size.toByte()
        val frame = byteArrayOf(
            0x68, length, length, 0x68,
            cField.toByte(), oldAddr.toByte(), ciField.toByte(), newAddr.toByte(),
            checksum, 0x16
        )
        port.write(frame, 1000)
    }

    private fun waitForAck(port: UsbSerialPort, timeoutMs: Long = 2000L): Boolean {
        val buffer = ByteArray(16)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val numBytesRead = port.read(buffer, 200)
            for (i in 0 until numBytesRead) {
                // In Kotlin, Bytes are signed (-128 to 127). 0xE5 (229) becomes -27.
                // We convert it to an unsigned Int by bitwise ANDing with 0xFF.
                if ((buffer[i].toInt() and 0xFF) == 0xE5) {
                    logOnUi("ACK Received!")
                    return true
                }
            }
        }
        return false
    }

    // --- UI Helpers ---

    private fun log(message: String) {
        tvStatus.append("$message\n")
    }

    private fun logOnUi(message: String) {
        runOnUiThread { log(message) }
    }

    // --- USB Permission Receiver ---

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            log("Permission granted. Press Set Address again.")
                        }
                    } else {
                        log("Permission denied for device.")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}