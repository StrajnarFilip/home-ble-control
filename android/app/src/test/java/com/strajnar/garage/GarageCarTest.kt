package com.strajnar.garage

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.car.app.HostInfo
import androidx.car.app.SessionInfo
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.OnClickDelegate
import androidx.car.app.OnDoneCallback
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSigningInfo
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class GarageCarTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private var taps = 0

    @Before
    fun grantBluetooth() = shadowOf(app).grantPermissions(*BLE_PERMISSIONS)

    private fun tile(status: GarageStatus) =
        garageCarTemplate(app, status) { taps++ }.singleList!!.items.single() as GridItem

    private fun tap(delegate: OnClickDelegate?) {
        delegate!!.sendClick(object : OnDoneCallback {})
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun readyTileTriggersTheDoor() {
        val tile = tile(GarageStatus(paired = true, connected = true))
        assertEquals("Garage door", tile.title.toString())
        assertEquals("Ready", tile.text.toString())
        assertNotNull(tile.image)
        tap(tile.onClickDelegate)
        assertEquals(1, taps)
    }

    @Test
    fun disconnectedTileStillTriggersTheDoorBecauseATapConnects() {
        val tile = tile(GarageStatus(paired = true, connected = false))
        assertEquals("Not connected", tile.text.toString())
        tap(tile.onClickDelegate)
        assertEquals(1, taps)
    }

    @Test
    fun busyTileShowsASpinnerAndNoButton() {
        val tile = tile(GarageStatus(paired = true, connected = true, busy = true, about = Control.GARAGE))
        assertTrue(tile.isLoading)
        assertNull(tile.onClickDelegate)
    }

    @Test
    fun unpairedTileSendsYouToThePhone() {
        val tile = tile(GarageStatus(paired = false))
        assertEquals("Set up on your phone", tile.text.toString())
        assertNull(tile.onClickDelegate)
    }

    @Test
    fun missingPermissionSendsYouToThePhone() {
        shadowOf(app).denyPermissions(*BLE_PERMISSIONS)
        val tile = tile(GarageStatus(paired = true))
        assertEquals("Set up on your phone", tile.text.toString())
        assertNull(tile.onClickDelegate)
    }

    @Test
    fun relayNewsStaysOffTheGarageTile() {
        val status = GarageStatus(paired = true, connected = true, message = "Relay switched on", about = Control.RELAY)
        assertEquals("Ready", tile(status).text.toString())
    }

    // The host limits new screens, and only counts an update as a refresh if these titles stay put.
    @Test
    fun everyStateKeepsTheSameTitles() {
        val states = listOf(
            GarageStatus(paired = false),
            GarageStatus(paired = true),
            GarageStatus(paired = true, connected = true),
            GarageStatus(paired = true, busy = true),
            GarageStatus(paired = true, message = "Timed out", isError = true, about = Control.GARAGE),
        )
        val titles = states.map { s ->
            val template = garageCarTemplate(app, s) {}
            template.header!!.title.toString() to (template.singleList!!.items.single() as GridItem).title.toString()
        }
        assertEquals(1, titles.distinct().size)
    }

    @Test
    fun tapInTheCarSendsTheCommandAndShowsItsError() {
        app.garage.onPairingCode("00".repeat(16))
        val carContext = TestCarContext.createCarContext(app)
        val screen = ScreenController(GarageCarScreen(carContext)).moveToState(Lifecycle.State.RESUMED)
        ShadowLooper.idleMainLooper()

        val template = screen.templatesReturned.last() as GridTemplate
        tap((template.singleList!!.items.single() as GridItem).onClickDelegate)

        // Bluetooth is off under Robolectric, so the command fails on its way to the garage.
        assertEquals(Control.GARAGE, app.garage.status.value.about)
        assertEquals("Bluetooth is off", app.garage.status.value.message)
        val toasts = carContext.getCarService(TestAppManager::class.java).toastsShown
        assertEquals(listOf("Bluetooth is off"), toasts.map { it.toString() })
    }

    @Test
    fun openingTheCarAppStartsTheConnectionService() {
        app.garage.onPairingCode("00".repeat(16))
        val session = Robolectric.setupService(GarageCarAppService::class.java)
            .onCreateSession(SessionInfo.DEFAULT_SESSION_INFO)
        assertNull("nothing may start before the host is validated", shadowOf(app).nextStartedService)

        SessionController(session, TestCarContext.createCarContext(app), Intent())
            .moveToState(Lifecycle.State.CREATED)

        assertEquals(GarageService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    }

    @Test
    fun otherAppsCannotPoseAsAndroidAuto() {
        val gearhead = "com.google.android.projection.gearhead"
        val certificate = Signature("abcd")
        val host = HostInfo(gearhead, 12345)
        install(gearhead, host.uid, certificate)
        val validator = Robolectric.setupService(GarageCarAppService::class.java).createHostValidator()

        assertFalse(validator.isValidHost(host))
        assertFalse(validator.isValidHost(HostInfo("com.example.not.installed", 12346)))

        // Control: it was only the certificate that got this one rejected.
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue(HostValidator.Builder(app).addAllowedHost(gearhead, digest).build().isValidHost(host))
    }

    private fun install(name: String, appUid: Int, certificate: Signature) {
        val signing = Shadow.newInstanceOf(SigningInfo::class.java)
        Shadow.extract<ShadowSigningInfo>(signing).apply {
            setSignatures(arrayOf(certificate))
            setPastSigningCertificates(arrayOf(certificate))
        }
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = name
                signingInfo = signing
                applicationInfo = ApplicationInfo().apply {
                    packageName = name
                    uid = appUid
                }
            },
        )
    }
}
