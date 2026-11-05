package com.strajnar.garage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Android Auto's way into the app: a screen with one garage door button. Only Android Auto binds
 * to this service, so on a phone without it none of this code runs.
 */
class GarageCarAppService : CarAppService() {
    // The service is exported and its button moves the door, so it only accepts Android Auto, on
    // debug builds too. ALLOW_ALL_HOSTS_VALIDATOR would let any installed app press the button.
    @SuppressLint("PrivateResource") // CarAppService's documentation points apps to this list.
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    // Runs before the host is validated, so this must not do anything yet.
    override fun onCreateSession(sessionInfo: SessionInfo): Session = GarageCarSession()
}

private class GarageCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Best effort: if Android refuses it from here, the first tap connects by itself.
        carContext.garage.startService(carContext)
        return GarageCarScreen(carContext)
    }
}

internal class GarageCarScreen(carContext: CarContext) : Screen(carContext) {
    private val garage = carContext.garage

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { garage.status.collect { invalidate() } }
                // The tile's status line is cut off after one line, so errors also get a toast.
                garage.status
                    .map { s -> s.message.takeIf { s.isError && s.about == Control.GARAGE } }
                    .distinctUntilChanged()
                    .filterNotNull()
                    .collect { CarToast.makeText(carContext, it, CarToast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onGetTemplate(): Template =
        garageCarTemplate(carContext, garage.status.value, garage::pulseGarage)
}

/**
 * The car screen for [status]. Only the tile's status line changes between updates: the host limits
 * how many new screens an app may show, and a template with the same titles only counts as a refresh.
 */
internal fun garageCarTemplate(context: Context, status: GarageStatus, onTap: () -> Unit): GridTemplate {
    val usable = status.paired && hasBlePermissions(context)
    val text =
        if (usable) tileText(context, status, Control.GARAGE, context.getString(R.string.widget_ready))
        else context.getString(R.string.car_set_up_on_phone)
    val tile = GridItem.Builder()
        .setTitle(context.getString(R.string.garage_door))
        .setText(text)
    if (status.busy) {
        tile.setLoading(true) // Garage ignores commands while busy, so don't offer a button.
    } else {
        val icon = IconCompat.createWithResource(context, R.drawable.ic_tile_garage)
        tile.setImage(CarIcon.Builder(icon).setTint(CarColor.DEFAULT).build(), GridItem.IMAGE_TYPE_ICON)
        if (usable) tile.setOnClickListener(onTap)
    }
    return GridTemplate.Builder()
        .setHeader(
            Header.Builder()
                .setTitle(context.getString(R.string.app_name))
                .setStartHeaderAction(Action.APP_ICON)
                .build(),
        )
        .setSingleList(ItemList.Builder().addItem(tile.build()).build())
        .build()
}
