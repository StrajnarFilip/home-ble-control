package com.strajnar.garage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: a garage door button and a relay toggle. Taps go straight to the running
 * [GarageService] over its existing connection; [Garage] re-renders the widget on every change.
 */
class GarageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, context.garage.status.value, manager, ids)
    }

    companion object {
        fun renderAll(context: Context, status: GarageStatus) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, GarageWidget::class.java))
            if (ids.isNotEmpty()) render(context, status, manager, ids)
        }

        private fun render(context: Context, status: GarageStatus, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_garage)
            views.setTextViewText(R.id.widget_status, statusText(context, status))
            views.setTextViewText(
                R.id.widget_relay_state,
                context.getString(
                    when (status.relayOn) {
                        true -> R.string.relay_on
                        false -> R.string.relay_off
                        null -> R.string.widget_relay_unknown
                    },
                ),
            )
            val on = status.relayOn == true
            views.setInt(R.id.widget_relay, "setBackgroundResource", if (on) R.drawable.widget_button_on else R.drawable.widget_button)
            // Color resources (not resolved colors), so the launcher follows dark mode switches.
            views.setColorStateList(R.id.widget_relay_label, "setTextColor", if (on) R.color.widget_text_on else R.color.widget_text_dim)
            views.setColorStateList(R.id.widget_relay_state, "setTextColor", if (on) R.color.widget_text_on else R.color.widget_text)
            // Without the permission the service couldn't go foreground (which crashes the app), so open the app.
            if (status.paired && hasBlePermissions(context)) {
                views.setOnClickPendingIntent(R.id.widget_garage, service(context, 1, GarageService.ACTION_PULSE))
                // The toggle sends an explicit on/off. With the state unknown, a tap just connects.
                views.setOnClickPendingIntent(
                    R.id.widget_relay,
                    when (status.relayOn) {
                        true -> service(context, 2, GarageService.ACTION_RELAY, on = false)
                        false -> service(context, 3, GarageService.ACTION_RELAY, on = true)
                        null -> service(context, 4, GarageService.ACTION_REFRESH)
                    },
                )
            } else {
                val open = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.widget_garage, open)
                views.setOnClickPendingIntent(R.id.widget_relay, open)
            }
            manager.updateAppWidget(ids, views)
        }

        private fun statusText(context: Context, s: GarageStatus): String = when {
            !s.paired -> context.getString(R.string.widget_open_to_pair)
            !hasBlePermissions(context) -> context.getString(R.string.widget_open_for_permission)
            s.busy && s.message == null -> context.getString(R.string.connecting)
            s.message != null -> s.message
            s.connected -> context.getString(R.string.widget_ready)
            else -> context.getString(R.string.not_connected)
        }

        // A foreground-service intent: a widget tap may start the service even from the background.
        private fun service(context: Context, requestCode: Int, action: String, on: Boolean? = null): PendingIntent {
            val intent = Intent(context, GarageService::class.java).setAction(action)
            if (on != null) intent.putExtra(GarageService.EXTRA_ON, on)
            return PendingIntent.getForegroundService(
                context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
