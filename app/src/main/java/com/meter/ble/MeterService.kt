package com.meter.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 前台服务:常驻通知 + 按间隔后台查询电表。锁屏/后台也能跑。
 * 启动: startForegroundService(Intent(ctx, MeterService::class.java))
 * 停止: ctx.stopService(...)
 */
class MeterService : Service() {

    companion object {
        const val CH_ONGOING = "meter_ongoing"  // 常驻通知渠道
        const val CH_ALERT = "meter_alert"       // 低电量提醒渠道
        const val NOTIF_ID = 1001
        const val ALERT_ID = 1002
        const val ACTION_START = "com.meter.ble.START"
        const val ACTION_STOP = "com.meter.ble.STOP"
        @Volatile var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ble: BleManager
    private var lastAlerted = false
    private var pollTask: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ble = BleManager(this)
        createChannels()
        startForeground(NOTIF_ID, buildOngoing("电表监控运行中", "等待第一次查询…"))
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            pollTask?.let { handler.removeCallbacks(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        scheduleNext(0)  // 立即查一次,然后按间隔循环
        return START_STICKY  // 被系统杀掉后尽量重启
    }

    private fun scheduleNext(delayMs: Long) {
        pollTask?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            doQuery()
            val interval = MeterStore.intervalMin(this).coerceAtLeast(1) * 60_000
            scheduleNext(interval)
        }
        pollTask = task
        handler.postDelayed(task, delayMs)
    }

    private fun doQuery() {
        val addr = MeterStore.address(this)
        val cmd = MeterProtocol.buildCommand(addr)
        ble.readOnce(addr, "", cmd, object : BleManager.Listener {
            override fun onLog(msg: String) {}
            override fun onError(msg: String) {
                updateOngoing("电表监控运行中", "上次查询失败:$msg")
            }
            override fun onFrame(rawHex: String) {
                try {
                    val r = MeterProtocol.parseResponse(rawHex)
                    val left = r.leftKwh ?: return
                    val total = r.totalKwh ?: -1.0
                    MeterStore.save(this@MeterService, left, total)
                    MeterWidget.refresh(this@MeterService)
                    updateOngoing("剩余 $left 度", "更新于 " + nowStr())
                    // 阈值提醒
                    val th = MeterStore.threshold(this@MeterService).toDouble()
                    if (left <= th && !lastAlerted) {
                        pushAlert(left, th); lastAlerted = true
                    } else if (left > th) lastAlerted = false
                } catch (_: Exception) {}
            }
        })
    }

    private fun nowStr(): String {
        val c = java.util.Calendar.getInstance()
        return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_ONGOING, "电表监控(常驻)", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "电量提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun buildOngoing(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateOngoing(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildOngoing(title, text))
    }

    private fun pushAlert(left: Double, th: Double) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("电表电量偏低")
            .setContentText("剩余约 $left 度,已低于阈值 $th 度,请及时充值")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }

    override fun onDestroy() {
        running = false
        pollTask?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
