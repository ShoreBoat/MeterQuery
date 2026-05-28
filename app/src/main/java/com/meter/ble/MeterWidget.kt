package com.meter.ble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 桌面小组件:显示剩余电量(度)。点击可打开App。 */
class MeterWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, mgr, id)
    }

    companion object {
        /** 数据更新后调用,刷新所有桌面小组件 */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, MeterWidget::class.java))
            for (id in ids) render(ctx, mgr, id)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val left = MeterStore.leftKwh(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_meter)
            val text = if (left < 0) "--" else "%.2f".format(left)
            views.setTextViewText(R.id.widget_value, text)
            views.setTextViewText(R.id.widget_label, "剩余电量(度)")

            // 点击小组件打开App
            val intent = Intent(ctx, MainActivity::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(ctx, 0, intent, flags)
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            mgr.updateAppWidget(id, views)
        }
    }
}
