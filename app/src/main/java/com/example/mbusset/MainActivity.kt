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

//        btnSetAddress.setOnClickListener {
//            // If the text is empty, it uses the "or" value (254 or 1)
//            val oldAddr = etOldAddress.text.toString().toIntOrNull() ?: 254
//            val newAddr = etNewAddress.text.toString().toIntOrNull() ?: 1
//
//            startMbusSequence(oldAddr, newAddr)
//        }

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
            port.setParameters(2400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)

            // Clear any startup noise
            purgeBuffer(port)

            // 1. Ping old address
            logOnUi("Pinging meter at address $oldAddr...")
            sendShortFrame(port, oldAddr, 0x40)
            if (!waitForAck(port)) {
                logOnUi("ERROR: No ACK from address $oldAddr.")
                port.close()
                return
            }

            // --- CRITICAL: Quiet Time ---
            // Some meters ignore writes if they happen too fast after a ping.
            logOnUi("Ping successful. Waiting for quiet bus...")
            Thread.sleep(1000)
            purgeBuffer(port) // Clear any echoes before the big command

            // 2. Set new address (Try sending to Broadcast 254)
            logOnUi("Sending address change ($oldAddr -> $newAddr)...")
            // We use 254 here as the A-Field; it's more reliable for writes
            sendSetAddressFrame(port, 254, newAddr)

            if (!waitForAck(port)) {
                logOnUi("ERROR: Address change failed (No ACK).")
                port.close()
                return
            }

            logOnUi("Meter ACKnowledged change. Writing to memory...")
            Thread.sleep(2000) // Meters need time to "burn" the EEPROM
            purgeBuffer(port)

            // 3. Verify new address
            logOnUi("Verifying new address $newAddr...")
            sendShortFrame(port, newAddr, 0x40)
            if (waitForAck(port)) {
                logOnUi("SUCCESS! Meter is now at address $newAddr.")
            } else {
                logOnUi("WARNING: No reply on $newAddr. Checking $oldAddr...")
                Thread.sleep(500)
                sendShortFrame(port, oldAddr, 0x40)
                if (waitForAck(port)) {
                    logOnUi("FAILURE: Meter stayed at address $oldAddr.")
                } else {
                    logOnUi("MYSTERY: Meter is silent on both addresses.")
                }
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

    private fun purgeBuffer(port: UsbSerialPort) {
        val buf = ByteArray(64)
        // Read everything currently in the buffer and toss it
        while (port.read(buf, 50) > 0) { /* Consuming data */ }
    }
    private fun sendShortFrame(port: UsbSerialPort, address: Int, controlByte: Int) {
        val checksum = calculateChecksum(intArrayOf(controlByte, address))
        val frame = byteArrayOf(0x10, controlByte.toByte(), address.toByte(), checksum, 0x16)
        purgeBuffer(port)
        port.write(frame, 1000)
    }

    private fun sendSetAddressFrame(port: UsbSerialPort, oldAddr: Int, newAddr: Int) {
        val cField = 0x53  // SND_UD (Send User Data)
        val ciField = 0x51 // Data Send
        val dif = 0x01     // 8-bit Integer
        val vif = 0x7A     // Primary Address Change code

        // The standard payload is 6 bytes: [C, A, CI, DIF, VIF, Data]
        val body = intArrayOf(cField, oldAddr, ciField, dif, vif, newAddr)
        val checksum = calculateChecksum(body)

        val frame = byteArrayOf(
            0x68,
            0x06.toByte(), // Length is now 6
            0x06.toByte(),
            0x68,
            cField.toByte(),
            oldAddr.toByte(),
            ciField.toByte(),
            dif.toByte(),
            vif.toByte(),
            newAddr.toByte(),
            checksum,
            0x16
        )

        purgeBuffer(port)
        port.write(frame, 1000)
    }

    private fun waitForAck(port: UsbSerialPort, timeoutMs: Long = 2000L): Boolean {
        val buffer = ByteArray(64)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val numRead = port.read(buffer, 100)
            if (numRead > 0) {
                // Scan the whole buffer for the ACK byte (0xE5)
                for (i in 0 until numRead) {
                    if ((buffer[i].toInt() and 0xFF) == 0xE5) {
                        logOnUi("ACK Received!")
                        return true
                    }
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