package com.rober.weeklybudget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import java.util.Locale

class BudgetWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        const val EXTRA_START_LISTENING = "start_listening"

        /** Push fresh numbers to any placed widgets. */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, BudgetWidget::class.java))
            if (ids.isNotEmpty()) update(context, mgr, ids)
        }

        private fun update(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            val today = LocalDate.now()
            val weekStart = today.minusDays((today.dayOfWeek.value % 7).toLong())
            val db = Db(context)
            val txs = db.listBetween(weekStart.toEpochDay(), weekStart.plusDays(6).toEpochDay())
            db.close()
            val spent = txs.filter { !it.isIncome }.sumOf { it.amountCents }
            val income = txs.filter { it.isIncome }.sumOf { it.amountCents }
            val cap = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getLong("weekly_cap_cents", 0)

            val spentColor = when {
                cap > 0 && spent >= cap -> context.getColor(R.color.expense_red)
                cap > 0 && spent * 100 / cap >= 80 -> context.getColor(R.color.cap_warn)
                cap > 0 -> context.getColor(R.color.income_green)
                else -> context.getColor(R.color.widget_text)
            }
            var sub = when {
                cap > 0 && spent > cap -> context.getString(R.string.widget_over, money(spent - cap))
                cap > 0 -> context.getString(R.string.widget_left, money(cap - spent), money(cap))
                else -> context.getString(R.string.widget_spent_label)
            }
            if (income > 0) sub += "  ·  +" + money(income)

            for (id in ids) {
                val rv = RemoteViews(context.packageName, R.layout.widget_budget)
                rv.setTextViewText(R.id.widgetSpent, money(spent))
                rv.setTextColor(R.id.widgetSpent, spentColor)
                rv.setTextViewText(R.id.widgetSub, sub)

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                rv.setOnClickPendingIntent(
                    R.id.widgetRoot,
                    PendingIntent.getActivity(
                        context, 0, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                val micIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra(EXTRA_START_LISTENING, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                rv.setOnClickPendingIntent(
                    R.id.widgetMic,
                    PendingIntent.getActivity(
                        context, 1, micIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                mgr.updateAppWidget(id, rv)
            }
        }

        private fun money(cents: Long) = String.format(Locale.US, "$%,.2f", cents / 100.0)
    }
}
