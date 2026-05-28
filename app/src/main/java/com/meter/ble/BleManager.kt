package com.meter.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * 一次完整的读表流程：扫描 -> 连接 -> 发现服务 -> 开notify -> 写命令 -> 收一帧 -> 断开。
 * 针对国产 DL/T645 蓝牙模块做了时序兜底：每步之间加延时，开notify后无论CCCD回调是否触发都兜底发命令。
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
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
    private var commandSent = false
    private var frameReceived = false

    private fun log(m: String) = handler.post { listener?.onLog(m) }
    private fun err(m: String) = handler.post { listener?.onError(m) }

    fun readOnce(address: String, nameKw: String, cmdHex: String, l: Listener) {
        listener = l
        targetAddress = MeterProtocol.normalizeAddress(address)
        nameKeyword = nameKw.uppercase()
        commandHex = cmdHex
        commandSent = false
        frameReceived = false
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
                log("已连接(status=$status)，0.6s后发现服务...")
                // 连接后稍等再发现服务，国产模块需要缓冲
                handler.postDelayed({ g.discoverServices() }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("连接断开 status=$status")
                if (!frameReceived) err("断开前未收到数据 (status=$status)")
                cleanup()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("服务发现完成 status=$status")
            // 打印所有服务和特征，方便确认 UUID 是否真的对
            val sb = StringBuilder("特征列表: ")
            for (svc in g.services) for (ch in svc.characteristics) {
                sb.append(ch.uuid.toString().substring(4, 8)).append("(")
                    .append(ch.properties).append(") ")
            }
            log(sb.toString())

            val notifyChar = findChar(g, NOTIFY_UUID)
            if (notifyChar == null) { err("找不到 notify 特征 FFF2"); return }

            val ok = g.setCharacteristicNotification(notifyChar, true)
            log("订阅notify: $ok")
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
                log("已请求开启CCCD")
            } else {
                log("无CCCD描述符，直接发命令")
            }
            // 兜底：无论CCCD回调是否触发，1秒后强制发命令
            handler.postDelayed({ if (!commandSent) writeCommand(g) }, 1000)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("CCCD写入完成 status=$status")
            // CCCD 写成功后再等0.3s发命令，给模块缓冲
            handler.postDelayed({ if (!commandSent) writeCommand(g) }, 300)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleFrame(ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleFrame(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            log("命令写入回执 status=$status")
        }
    }

    private fun handleFrame(data: ByteArray) {
        val hex = MeterProtocol.bytesToHex(data)
        log("收到: $hex")
        frameReceived = true
        handler.post { listener?.onFrame(hex) }
        handler.postDelayed({ disconnect() }, 800)
    }

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (svc in g.services) {
            svc.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun writeCommand(g: BluetoothGatt) {
        if (commandSent) return
        val writeChar = findChar(g, WRITE_UUID)
        if (writeChar == null) { err("找不到 write 特征 FFF1"); return }
        commandSent = true
        val bytes = MeterProtocol.hexToBytes(commandHex)
        // 优先用带响应的写(更可靠)；若特征不支持则退回无响应
        val canWriteDefault = (writeChar.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val writeType = if (canWriteDefault)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(writeChar, bytes, writeType)
        } else {
            @Suppress("DEPRECATION")
            run {
                writeChar.writeType = writeType
                writeChar.value = bytes
                g.writeCharacteristic(writeChar)
            }
        }
        log("已发送命令(type=$writeType): $commandHex")
        // 发命令后给8秒等数据，超时还没收到就报告
        handler.postDelayed({
            if (!frameReceived) { err("发命令后8秒未收到数据，可能命令格式或notify不对"); disconnect() }
        }, 8000)
    }

    fun disconnect() { try { gatt?.disconnect() } catch (_: Exception) {} }

    private fun cleanup() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }
}
