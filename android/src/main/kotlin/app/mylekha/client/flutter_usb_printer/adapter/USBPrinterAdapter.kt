package app.mylekha.client.flutter_usb_printer.adapter

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.nio.charset.Charset
import java.util.*

private enum class ConnectionResult {
    SUCCESS,
    DEVICE_NOT_INITIALIZED,
    USB_MANAGER_NOT_INITIALIZED,
    CONNECTION_ALREADY_OPEN,
    FAILED_TO_OPEN_CONNECTION,
    FAILED_TO_CLAIM_INTERFACE
}


class USBPrinterAdapter {

    private var mInstance: USBPrinterAdapter? = null


    private val LOG_TAG = "Flutter USB Printer"
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null
    private var mPendingDataToPrint: ByteArray? = null;

    private val ACTION_USB_PERMISSION = "app.mylekha.client.flutter_usb_printer.USB_PERMISSION"


    fun getInstance(): USBPrinterAdapter? {
        if (mInstance == null) {
            mInstance = this;
        }
        return mInstance
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val usbDevice =
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(
                            LOG_TAG,
                            "Success to grant permission for device " + usbDevice!!.deviceId + ", vendor_id: " + usbDevice.vendorId + " product_id: " + usbDevice.productId
                        )
                        mUsbDevice = usbDevice


                        Toast.makeText(
                            context,
                            "Success to grant permission for device " + usbDevice!!.deviceId + ", vendor_id: " + usbDevice.vendorId + " product_id: " + usbDevice.productId,
                            Toast.LENGTH_LONG
                        ).show()

                        if (mPendingDataToPrint != null) {
                            Toast.makeText(
                                context,
                                "Pending data send to printer.",
                                Toast.LENGTH_LONG
                            ).show();
                            if (write(mPendingDataToPrint!!)) {
                                mPendingDataToPrint = null
                            }
                        }

                    } else {
                        Toast.makeText(
                            context,
                            "User refused to give USB device permissions" + usbDevice!!.deviceName,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (usbDevice != null) {
                    reconnectedRemovedDevice(usbDevice)
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                if (mUsbDevice != null) {
                    Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG)
                        .show()
                    closeConnectionIfExists()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        mPermissionIndent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(
                mContext,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            PendingIntent.getBroadcast(
                mContext,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        mContext!!.registerReceiver(mUsbDeviceReceiver, filter)



        Log.v(LOG_TAG, "USB Printer initialized")
    }


    fun closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection!!.releaseInterface(mUsbInterface)
            mUsbDeviceConnection!!.close()
            mUsbInterface = null
            mEndPoint = null
            mUsbDeviceConnection = null
            mUsbDevice = null
        }
    }

    fun getDeviceList(): List<UsbDevice> {
        if (mUSBManager == null) {
            Toast.makeText(
                mContext,
                "USB Manager is not initialized while get device list",
                Toast.LENGTH_LONG
            ).show()
            return emptyList()
        }
        return ArrayList(mUSBManager!!.deviceList.values)
    }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        if (mUsbDevice == null || mUsbDevice!!.vendorId != vendorId || mUsbDevice!!.productId != productId) {
            closeConnectionIfExists()
            val usbDevices = getDeviceList()
            for (usbDevice in usbDevices) {
                if (usbDevice.vendorId == vendorId && usbDevice.productId == productId) {
                    Log.v(
                        LOG_TAG,
                        "Request for device: vendor_id: " + usbDevice.vendorId + ", product_id: " + usbDevice.productId
                    )
                    closeConnectionIfExists()
                    mUSBManager!!.requestPermission(usbDevice, mPermissionIndent)
                    return true
                }
            }
            return false
        }
        return true
    }


    fun reconnectedRemovedDevice(usbDevice: UsbDevice) {
        mUSBManager!!.requestPermission(usbDevice, mPermissionIndent)
    }


    private fun openConnection(onReconnectRequested: (() -> Unit)? = null): ConnectionResult {
        if (mUsbDevice == null) {
            Log.e(LOG_TAG, "USB Device is not initialized")
            return ConnectionResult.DEVICE_NOT_INITIALIZED
        }
        if (mUSBManager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized")
            return ConnectionResult.USB_MANAGER_NOT_INITIALIZED
        }
        if (mUsbDeviceConnection != null) {
            return ConnectionResult.CONNECTION_ALREADY_OPEN
        }

        val usbInterface = mUsbDevice!!.getInterface(0)
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    val usbDeviceConnection = mUSBManager!!.openDevice(mUsbDevice)
                    if (usbDeviceConnection == null) {
                        Log.e(LOG_TAG, "failed to open USB Connection, trying to reconnect")
                        onReconnectRequested?.invoke();
                        reconnectedRemovedDevice(mUsbDevice!!)
                        return ConnectionResult.FAILED_TO_OPEN_CONNECTION
                    }
                    Toast.makeText(mContext, "Connected to device", Toast.LENGTH_SHORT).show()
                    return if (usbDeviceConnection.claimInterface(usbInterface, true)) {
                        mEndPoint = ep
                        mUsbInterface = usbInterface
                        mUsbDeviceConnection = usbDeviceConnection
                        ConnectionResult.SUCCESS
                    } else {
                        usbDeviceConnection.close()
                        Log.e(LOG_TAG, "failed to claim USB connection")
                        Toast.makeText(mContext, "Failed to claim interface", Toast.LENGTH_SHORT)
                            .show()
                        return ConnectionResult.FAILED_TO_CLAIM_INTERFACE
                    }
                }
            }
        }
        return ConnectionResult.SUCCESS
    }

    fun printText(text: String): Boolean {
        Log.v(LOG_TAG, "start to print text")

        return when (openConnection()) {
            ConnectionResult.SUCCESS -> {
                Thread {
                    val bytes = text.toByteArray(Charset.forName("UTF-8"))
                    val b =
                        mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Return Status: b-->$b")
                }.start()
                return true
            }

            ConnectionResult.DEVICE_NOT_INITIALIZED -> {
                Log.v(LOG_TAG, "Device not initialized")
                false
            }

            ConnectionResult.USB_MANAGER_NOT_INITIALIZED -> {
                Log.v(LOG_TAG, "USB Manager not initialized")
                false
            }

            ConnectionResult.CONNECTION_ALREADY_OPEN -> {
                Thread {
                    val bytes = text.toByteArray(Charset.forName("UTF-8"))
                    val b =
                        mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Return Status: b-->$b")
                }.start()
                return true
            }

            ConnectionResult.FAILED_TO_OPEN_CONNECTION -> {
                Log.v(LOG_TAG, "Failed to open connection")
                false
            }

            ConnectionResult.FAILED_TO_CLAIM_INTERFACE -> {
                Log.v(LOG_TAG, "Failed to claim interface")
                false
            }
        }
    }

    fun printRawText(data: String): Boolean {
        Log.v(LOG_TAG, "start to print raw data $data")
        return when (openConnection()) {
            ConnectionResult.SUCCESS -> {
                Thread {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    val transferResult =
                        mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Transfer Result: $transferResult")
                    if (transferResult == -1) {
                        closeConnectionIfExists()
                        tryToReconnectFailedDevice()
                    }
                }.start()
                true
            }

            ConnectionResult.DEVICE_NOT_INITIALIZED -> {
                Log.v(LOG_TAG, "Device not initialized")
                false
            }

            ConnectionResult.USB_MANAGER_NOT_INITIALIZED -> {
                Log.v(LOG_TAG, "USB Manager not initialized")
                false
            }

            ConnectionResult.CONNECTION_ALREADY_OPEN -> {
                Thread {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    val transferResult =
                        mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Transfer Result: $transferResult")
                    if (transferResult == -1) {
                        closeConnectionIfExists()
                        tryToReconnectFailedDevice()
                    }
                }.start()
                true
            }

            ConnectionResult.FAILED_TO_OPEN_CONNECTION -> {
                Log.v(LOG_TAG, "Failed to open connection")
                false
            }

            ConnectionResult.FAILED_TO_CLAIM_INTERFACE -> {
                Log.v(LOG_TAG, "Failed to claim interface")
                false
            }
        }


    }


    fun write(bytes: ByteArray): Boolean {
        Log.v(LOG_TAG, "start to print raw data $bytes")

        val connectionResult = openConnection {
            mPendingDataToPrint = bytes;
        };


        return when (connectionResult) {
            ConnectionResult.SUCCESS -> {
                Thread {
                    val transferResult =
                        mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Transfer Result: $transferResult")
                    if (transferResult == -1) {
                        mPendingDataToPrint = bytes;
                        closeConnectionIfExists()
                        tryToReconnectFailedDevice()
                    }
                }.start()
                true
            }

            ConnectionResult.DEVICE_NOT_INITIALIZED -> {
                Log.v(LOG_TAG, "Device not initialized")
                false
            }

            ConnectionResult.USB_MANAGER_NOT_INITIALIZED -> {
                Log.v(LOG_TAG, "USB Manager not initialized")
                false
            }

            ConnectionResult.CONNECTION_ALREADY_OPEN -> {
                Log.v(LOG_TAG, "Connection already open. Proceeding with writing.")
                val transferResult =
                    mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                Log.i(LOG_TAG, "Transfer Result: $transferResult")
                if (transferResult == -1) {
                    mPendingDataToPrint = bytes;
                    closeConnectionIfExists()
                    tryToReconnectFailedDevice()
                }
                true
            }

            ConnectionResult.FAILED_TO_OPEN_CONNECTION -> {
                Log.v(LOG_TAG, "Failed to open connection")
                mPendingDataToPrint = bytes;
                false
            }

            ConnectionResult.FAILED_TO_CLAIM_INTERFACE -> {
                Log.v(LOG_TAG, "Failed to claim interface")
                false
            }
        }
    }


    private fun tryToReconnectFailedDevice() {

        val usbDevices = ArrayList(mUSBManager!!.deviceList.values)

        if (usbDevices.isNotEmpty()) {
            val device = usbDevices[0]
            selectDevice(device.vendorId, device.productId)
            return;
        }

        Log.e(LOG_TAG, "No USB devices available for reconnection")
        Toast.makeText(mContext, "No USB devices available for reconnection", Toast.LENGTH_SHORT)
            .show()

    }

}