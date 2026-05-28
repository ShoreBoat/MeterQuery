package com.meter.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var ble: BleManager
    private lateinit var logView: TextView
    private lateinit var resultView: TextView
    private lateinit var addrInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var intervalInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var lastAlerted = false

    private val CHANNEL_ID = "meter_alert"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(this)
        createChannel()
        setContentView(buildUi())
        requestPerms()
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0,20,0,4) }

        ll.addView(TextView(this).apply { text = "电表监控"; textSize = 22f; setPadding(0,0,0,20) })

        ll.addView(label("电表地址"))
        addrInput = EditText(this).apply { setText("A2:50:72:78:25:51") }
        ll.addView(addrInput)

        ll.addView(label("名字关键字"))
        nameInput = EditText(this).apply { setText("NS-DDS-BLE") }
        ll.addView(nameInput)

        ll.addView(label("提醒阈值(剩余电量,度)"))
        thresholdInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("10")
        }
        ll.addView(thresholdInput)

        ll.addView(label("查询间隔(分钟)"))
        intervalInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("60")
        }
        ll.addView(intervalInput)

        val readBtn = Button(this).apply { text = "立即查询一次" }
        readBtn.setOnClickListener { readOnce() }
        ll.addView(readBtn)

        val pollBtn = Button(this).apply { text = "开始定时监控" }
        pollBtn.setOnClickListener {
            if (!polling) { polling = true; pollBtn.text = "停止监控"; startPolling() }
            else { polling = false; pollBtn.text = "开始定时监控"; handler.removeCallbacksAndMessages(null) }
        }
        ll.addView(pollBtn)

        ll.addView(label("解析结果"))
        resultView = TextView(this).apply { text = "—"; textSize = 13f }
        ll.addView(resultView)

        ll.addView(label("日志"))
        logView = TextView(this).apply { text = ""; textSize = 11f }
        ll.addView(logView)

        root.addView(ll)
        return root
    }

    private fun readOnce() {
        resultView.text = "查询中..."
        val cmd = MeterProtocol.buildCommand(addrInput.text.toString())
        ble.readOnce(addrInput.text.toString(), nameInput.text.toString(), cmd, this)
    }

    private fun startPolling() {
        val intervalMin = intervalInput.text.toString().toLongOrNull() ?: 60
        val task = object : Runnable {
            override fun run() {
                if (!polling) return
                readOnce()
                handler.postDelayed(this, intervalMin * 60_000)
            }
        }
        handler.post(task)
        onLog("定时监控已开启，每 ${intervalMin} 分钟一次")
    }

    // ---- BleManager.Listener ----
    override fun onLog(msg: String) {
        runOnUiThread { logView.text = "$msg\n${logView.text}".take(2000) }
    }

    override fun onError(msg: String) {
        runOnUiThread {
            logView.text = "❌ $msg\n${logView.text}".take(2000)
            resultView.text = "查询失败: $msg"
        }
    }

    override fun onFrame(rawHex: String) {
        runOnUiThread {
            try {
                val r = MeterProtocol.parseResponse(rawHex)
                val sb = StringBuilder()
                sb.append("原始帧: $rawHex\n\n")
                sb.append("数据标识: ${r.dataId}\n")
                sb.append("剩余金额(猜): ${r.leftMoney}\n")
                sb.append("已用金额(猜): ${r.usedMoney}\n")
                sb.append("电流(猜): ${r.currentA} A\n")
                sb.append("剩余电量候选: ${r.leftKwhCandidates}\n")
                sb.append("payload: ${r.payloadHex}\n")
                sb.append("u32块: ${r.rawChunks}\n")
                resultView.text = sb.toString()

                // 阈值判断：用剩余电量候选的第一个(待你确认偏移后改成准确字段)
                val threshold = thresholdInput.text.toString().toDoubleOrNull()
                val left = r.leftKwhCandidates.firstOrNull()
                if (threshold != null && left != null) {
                    if (left <= threshold && !lastAlerted) {
                        notifyLow(left, threshold); lastAlerted = true
                    } else if (left > threshold) lastAlerted = false
                }
            } catch (e: Exception) {
                resultView.text = "解析失败: ${e.message}\n原始: $rawHex"
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "电表提醒", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun notifyLow(left: Double, threshold: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("电表电量偏低")
            .setContentText("剩余约 $left 度，已低于阈值 $threshold 度，请及时充值")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, n)
    }

    private fun requestPerms() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }
}
