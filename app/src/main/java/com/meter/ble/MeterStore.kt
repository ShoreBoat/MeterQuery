package com.meter.ble

import android.content.Context

/** 保存最新读数和配置,供 Service / Widget / Activity 共享 */
object MeterStore {
    private const val PREF = "meter_store"

    fun save(ctx: Context, leftKwh: Double, totalKwh: Double) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("leftKwh", leftKwh.toFloat())
            .putFloat("totalKwh", totalKwh.toFloat())
            .putLong("updatedAt", System.currentTimeMillis())
            .apply()
    }

    fun leftKwh(ctx: Context): Float =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("leftKwh", -1f)

    fun totalKwh(ctx: Context): Float =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("totalKwh", -1f)

    fun updatedAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("updatedAt", 0L)

    // 配置:地址、阈值、间隔
    fun saveConfig(ctx: Context, address: String, threshold: Double, intervalMin: Long) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("address", address)
            .putFloat("threshold", threshold.toFloat())
            .putLong("intervalMin", intervalMin)
            .apply()
    }

    fun address(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("address", "A2:50:72:78:25:51") ?: ""

    fun threshold(ctx: Context): Float =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("threshold", 10f)

    fun intervalMin(ctx: Context): Long =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("intervalMin", 60L)
}
