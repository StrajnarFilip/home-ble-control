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
        manager.updateAppWidget(ids, wideViews(context, context.garage.status.value))
    }

    companion object {
        /** Re-renders the wide widget and both tiles. */
        fun renderAll(context: Context, status: GarageStatus) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            fun update(provider: Class<out AppWidgetProvider>, views: () -> RemoteViews) {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views())
            }
            update(GarageWidget::class.java) { wideViews(context, status) }
            update(GarageTile::class.java) { garageTileViews(context, status) }
            update(RelayTile::class.java) { relayTileViews(context, status) }
        }
    }
}

/** A 1x1 tile that triggers the garage door. */
class GarageTile : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, garageTileViews(context, context.garage.status.value))
    }
}

/** A 1x1 tile that toggles the relay and shows its state. */
class RelayTile : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, relayTileViews(context, context.garage.status.value))
    }
}

private fun wideViews(context: Context, status: GarageStatus): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_garage)
    views.setTextViewText(R.id.widget_status, statusText(context, status))
    views.setTextViewText(R.id.widget_relay_state, relayStateText(context, status))
    val on = status.relayOn == true
    views.setInt(R.id.widget_relay, "setBackgroundResource", if (on) R.drawable.widget_button_on else R.drawable.widget_button)
    // Color resources (not resolved colors), so the launcher follows dark mode switches.
    views.setColorStateList(R.id.widget_relay_label, "setTextColor", if (on) R.color.widget_text_on else R.color.widget_text_dim)
    views.setColorStateList(R.id.widget_relay_state, "setTextColor", if (on) R.color.widget_text_on else R.color.widget_text)
    views.setOnClickPendingIntent(R.id.widget_garage, garageTap(context, status))
    views.setOnClickPendingIntent(R.id.widget_relay, relayTap(context, status))
    return views
}

private fun garageTileViews(context: Context, status: GarageStatus): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_tile_garage)
    views.setTextViewText(R.id.widget_tile_text, tileText(context, status, Control.GARAGE, context.getString(R.string.garage_door)))
    views.setOnClickPendingIntent(android.R.id.background, garageTap(context, status))
    return views
}

private fun relayTileViews(context: Context, status: GarageStatus): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_tile_relay)
    val label = context.getString(R.string.relay) + "\n" + relayStateText(context, status)
    views.setTextViewText(R.id.widget_tile_text, tileText(context, status, Control.RELAY, label))
    val on = status.relayOn == true
    views.setInt(android.R.id.background, "setBackgroundResource", if (on) R.drawable.widget_tile_on else R.drawable.widget_tile)
    views.setImageViewResource(R.id.widget_tile_icon, if (on) R.drawable.ic_tile_relay_on else R.drawable.ic_tile_relay)
    views.setColorStateList(R.id.widget_tile_text, "setTextColor", if (on) R.color.widget_text_on else R.color.widget_text)
    views.setOnClickPendingIntent(android.R.id.background, relayTap(context, status))
    return views
}

private fun relayStateText(context: Context, status: GarageStatus): String = context.getString(
    when (status.relayOn) {
        true -> R.string.relay_on
        false -> R.string.relay_off
        null -> R.string.widget_relay_unknown
    },
)

private fun statusText(context: Context, s: GarageStatus): String = when {
    !s.paired -> context.getString(R.string.widget_open_to_pair)
    !hasBlePermissions(context) -> context.getString(R.string.widget_open_for_permission)
    s.busy && s.message == null -> context.getString(R.string.connecting)
    s.message != null -> s.message
    s.connected -> context.getString(R.string.widget_ready)
    else -> context.getString(R.string.not_connected)
}

/** Like [statusText], but shorter and without news about the other tile's control. */
private fun tileText(context: Context, s: GarageStatus, control: Control, ready: String): String {
    val mine = s.about == null || s.about == control
    return when {
        !s.paired || !hasBlePermissions(context) -> context.getString(R.string.tile_open_app)
        mine && s.message != null -> s.message
        mine && s.busy -> context.getString(R.string.connecting)
        s.connected -> ready
        else -> context.getString(R.string.not_connected)
    }
}

private fun garageTap(context: Context, status: GarageStatus): PendingIntent =
    if (canUseService(context, status)) service(context, 1, GarageService.ACTION_PULSE) else openApp(context)

// The toggle sends an explicit on/off. With the state unknown, a tap just connects.
private fun relayTap(context: Context, status: GarageStatus): PendingIntent = when {
    !canUseService(context, status) -> openApp(context)
    status.relayOn == true -> service(context, 2, GarageService.ACTION_RELAY, on = false)
    status.relayOn == false -> service(context, 3, GarageService.ACTION_RELAY, on = true)
    else -> service(context, 4, GarageService.ACTION_REFRESH)
}

// Without the permission the service couldn't go foreground (which crashes the app), so open the app.
private fun canUseService(context: Context, status: GarageStatus) = status.paired && hasBlePermissions(context)

private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
)

// A foreground-service intent: a widget tap may start the service even from the background.
private fun service(context: Context, requestCode: Int, action: String, on: Boolean? = null): PendingIntent {
    val intent = Intent(context, GarageService::class.java).setAction(action)
    if (on != null) intent.putExtra(GarageService.EXTRA_ON, on)
    return PendingIntent.getForegroundService(
        context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
