package com.meter.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var ble: BleManager
    private lateinit var logView: TextView
    private lateinit var bigValue: TextView
    private lateinit var subValue: TextView
    private lateinit var addrInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var pollBtn: Button
    private var lastAlerted = false

    private val GREEN = Color.parseColor("#2E7D32")
    private val GREEN_LIGHT = Color.parseColor("#E8F5E9")
    private val GREY = Color.parseColor("#9E9E9E")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(this)
        setContentView(buildUi())
        requestPerms()
        refreshFromStore()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(16)
            layoutParams = lp
            elevation = dp(2).toFloat()
        }
    }

    private fun fieldLabel(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(GREY); setPadding(0, dp(12), 0, dp(2))
    }

    private fun styledButton(t: String, primary: Boolean): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        setTextColor(if (primary) Color.WHITE else GREEN)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (primary) setColor(GREEN) else {
                setColor(Color.WHITE); setStroke(dp(2), GREEN)
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        )
        lp.topMargin = dp(12)
        layoutParams = lp
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2F4F3"))
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        // 标题
        ll.addView(TextView(this).apply {
            text = "电表监控"
            textSize = 24f
            setTextColor(Color.parseColor("#212121"))
            setPadding(dp(4), 0, 0, dp(16))
        })

        // 大数字卡片
        val valueCard = card().apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(GREEN)
            }
            gravity = Gravity.CENTER
        }
        bigValue = TextView(this).apply {
            text = "-- 度"
            textSize = 40f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        subValue = TextView(this).apply {
            text = "剩余电量"
            textSize = 14f
            setTextColor(GREEN_LIGHT)
            gravity = Gravity.CENTER
        }
        valueCard.addView(bigValue)
        valueCard.addView(subValue)
        ll.addView(valueCard)

        // 设置卡片
        val cfgCard = card()
        cfgCard.addView(fieldLabel("电表地址"))
        addrInput = EditText(this).apply { setText("A2:50:72:78:25:51"); textSize = 15f }
        cfgCard.addView(addrInput)
        cfgCard.addView(fieldLabel("提醒阈值 (剩余电量, 度)"))
        thresholdInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("10"); textSize = 15f
        }
        cfgCard.addView(thresholdInput)
        cfgCard.addView(fieldLabel("查询间隔 (分钟)"))
        intervalInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("60"); textSize = 15f
        }
        cfgCard.addView(intervalInput)
        ll.addView(cfgCard)

        // 按钮卡片
        val btnCard = card()
        val readBtn = styledButton("立即查询一次", true)
        readBtn.setOnClickListener { readOnce() }
        btnCard.addView(readBtn)

        pollBtn = styledButton(if (MeterService.running) "停止后台监控" else "开始后台监控 (锁屏也跑)", false)
        pollBtn.setOnClickListener { togglePolling() }
        btnCard.addView(pollBtn)
        ll.addView(btnCard)

        // 日志卡片
        val logCard = card()
        logCard.addView(fieldLabel("日志"))
        logView = TextView(this).apply { text = ""; textSize = 11f; setTextColor(GREY) }
        logCard.addView(logView)
        ll.addView(logCard)

        root.addView(ll)
        return root
    }

    private fun togglePolling() {
        if (!MeterService.running) {
            saveConfig()
            val i = Intent(this, MeterService::class.java).apply { action = MeterService.ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            pollBtn.text = "停止后台监控"
            onLog("后台监控已开启,每 ${intervalInput.text} 分钟一次,锁屏也运行")
        } else {
            val i = Intent(this, MeterService::class.java).apply { action = MeterService.ACTION_STOP }
            startService(i)
            pollBtn.text = "开始后台监控 (锁屏也跑)"
            onLog("后台监控已停止")
        }
    }

    private fun refreshFromStore() {
        val left = MeterStore.leftKwh(this)
        if (left >= 0) {
            bigValue.text = "%.2f 度".format(left)
            subValue.text = "剩余电量 · 更新于 " + timeStr(MeterStore.updatedAt(this))
        }
    }

    private fun timeStr(ms: Long): String {
        if (ms <= 0) return "--"
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun readOnce() {
        bigValue.text = "查询中…"
        saveConfig()
        val cmd = MeterProtocol.buildCommand(addrInput.text.toString())
        ble.readOnce(addrInput.text.toString(), "", cmd, this)
    }

    private fun saveConfig() {
        val addr = addrInput.text.toString()
        val th = thresholdInput.text.toString().toDoubleOrNull() ?: 10.0
        val interval = intervalInput.text.toString().toLongOrNull() ?: 60
        MeterStore.saveConfig(this, addr, th, interval)
    }

    override fun onLog(msg: String) {
        runOnUiThread { logView.text = "$msg\n${logView.text}".take(1500) }
    }

    override fun onError(msg: String) {
        runOnUiThread {
            logView.text = "❌ $msg\n${logView.text}".take(1500)
            bigValue.text = "查询失败"
            subValue.text = msg.take(20)
        }
    }

    override fun onFrame(rawHex: String) {
        runOnUiThread {
            try {
                val r = MeterProtocol.parseResponse(rawHex)
                val left = r.leftKwh
                if (left != null) {
                    MeterStore.save(this, left, r.totalKwh ?: -1.0)
                    MeterWidget.refresh(this)
                    bigValue.text = "%.2f 度".format(left)
                    subValue.text = "剩余电量 · 总用 ${r.totalKwh} 度"
                    onLog("查询成功: 剩余 $left 度")
                    val threshold = thresholdInput.text.toString().toDoubleOrNull()
                    if (threshold != null) {
                        if (left <= threshold && !lastAlerted) { notifyLow(left, threshold); lastAlerted = true }
                        else if (left > threshold) lastAlerted = false
                    }
                }
            } catch (e: Exception) {
                onLog("解析失败: ${e.message}")
            }
        }
    }

    private fun notifyLow(left: Double, threshold: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(MeterService.CH_ALERT, "电量提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(this, MeterService.CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("电表电量偏低")
            .setContentText("剩余约 $left 度,已低于阈值 $threshold 度,请及时充值")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1002, n)
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
