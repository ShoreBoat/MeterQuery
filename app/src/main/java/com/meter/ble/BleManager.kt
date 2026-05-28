package com.meter.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * 一次完整的读表流程：扫描 -> 连接 -> 开notify -> 写命令 -> 收一帧 -> 断开。
 * 弱信号场景做了重试与超时。
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_HINT = "FFF0"
        val WRITE_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface Listener {
        fun onLog(msg: String)
        fun onFrame(rawHex: String)
        fun onError(msg: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var gatt: BluetoothGatt? = null
    private var scanner = false
    private var listener: Listener? = null
    private var targetAddress: String = ""
    private var nameKeyword: String = ""
    private var commandHex: String = ""

    private fun log(m: String) = handler.post { listener?.onLog(m) }
    private fun err(m: String) = handler.post { listener?.onError(m) }

    fun readOnce(address: String, nameKw: String, cmdHex: String, l: Listener) {
        listener = l
        targetAddress = MeterProtocol.normalizeAddress(address)
        nameKeyword = nameKw.uppercase()
        commandHex = cmdHex
        val a = adapter
        if (a == null || !a.isEnabled) { err("蓝牙未开启"); return }
        startScan()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = (dev.name ?: result.scanRecord?.deviceName ?: "")
            val addrMatch = MeterProtocol.normalizeAddress(dev.address ?: "") == targetAddress
            val nameMatch = nameKeyword.isNotEmpty() && name.uppercase().contains(nameKeyword)
            if (addrMatch || nameMatch) {
                log("发现目标: $name ${dev.address} RSSI=${result.rssi}")
                stopScan()
                connect(dev)
            }
        }
        override fun onScanFailed(errorCode: Int) { err("扫描失败 code=$errorCode") }
    }

    private fun startScan() {
        val s = adapter?.bluetoothLeScanner ?: run { err("无法获取扫描器"); return }
        scanner = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        s.startScan(null, settings, scanCallback)
        log("开始扫描... (15s)")
        handler.postDelayed({
            if (scanner) { stopScan(); err("扫描超时：未发现表，确认小程序已退出、手机贴近电表") }
        }, 15000)
    }

    private fun stopScan() {
        if (scanner) {
            scanner = false
            try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        }
    }

    private fun connect(dev: BluetoothDevice) {
        log("连接中...")
        gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("已连接，发现服务...")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("连接断开 status=$status")
                cleanup()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val notifyChar = findChar(g, NOTIFY_UUID)
            if (notifyChar == null) { err("找不到 notify 特征"); cleanup(); return }
            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                writeCommand(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            writeCommand(g)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val hex = MeterProtocol.bytesToHex(ch.value ?: ByteArray(0))
            log("收到: $hex")
            handler.post { listener?.onFrame(hex) }
            handler.postDelayed({ disconnect() }, 500)
        }

        // 新 API 回调
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val hex = MeterProtocol.bytesToHex(value)
            log("收到: $hex")
            handler.post { listener?.onFrame(hex) }
            handler.postDelayed({ disconnect() }, 500)
        }
    }

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (svc in g.services) {
            svc.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun writeCommand(g: BluetoothGatt) {
        val writeChar = findChar(g, WRITE_UUID)
        if (writeChar == null) { err("找不到 write 特征"); cleanup(); return }
        val bytes = MeterProtocol.hexToBytes(commandHex)
        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        run {
            writeChar.value = bytes
            g.writeCharacteristic(writeChar)
        }
        log("已发送命令: $commandHex")
    }

    fun disconnect() { try { gatt?.disconnect() } catch (_: Exception) {} }

    private fun cleanup() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }
}
