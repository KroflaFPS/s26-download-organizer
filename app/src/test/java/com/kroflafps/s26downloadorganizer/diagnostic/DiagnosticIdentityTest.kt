package com.kroflafps.s26downloadorganizer.diagnostic

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import com.kroflafps.s26downloadorganizer.BuildConfig
import com.kroflafps.s26downloadorganizer.operations.OperationPolicy
import com.kroflafps.s26downloadorganizer.storage.AndroidDiagnosticCopies
import com.kroflafps.s26downloadorganizer.storage.StorageAccessAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises actual Android intent construction with controlled activities; never opens Settings or shared files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class DiagnosticIdentityTest {
    private fun activity(attempt: (Intent) -> Unit) = object : Activity() {
        override fun getPackageName(): String = BuildConfig.APPLICATION_ID
        override fun startActivity(intent: Intent) = attempt(intent)
    }
    @Test fun allFilesSettingsUsesInstalledApplicationIdRatherThanCodeNamespace() {
        val intents = mutableListOf<Intent>()
        assertTrue(StorageAccessAdapter().openSettings(activity { intents.add(it) }))
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intents.single().action)
        assertEquals("package:${BuildConfig.APPLICATION_ID}", intents.single().dataString)
    }
    @Test fun unavailableSpecificSettingsRetainsCorrectIdentityInAppDetailsFallback() {
        val intents = mutableListOf<Intent>()
        assertTrue(StorageAccessAdapter().openSettings(activity {
            intents.add(it)
            if (intents.size < 3) throw ActivityNotFoundException("disposable test")
        }))
        assertEquals(listOf(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS), intents.map { it.action })
        assertNull(intents[1].data)
        assertEquals("package:${BuildConfig.APPLICATION_ID}", intents.last().dataString)
    }
    @Test fun onlySeparatelyIdentifiedDiagnosticVariantCanPassTheBuildGate() {
        val diagnostic = BuildConfig.BUILD_TYPE == "diagnostic"
        assertEquals(diagnostic, AndroidDiagnosticCopies.allowed())
        assertEquals(diagnostic, BuildConfig.APPLICATION_ID == DiagnosticPolicy.APPLICATION_ID)
        assertFalse(OperationPolicy.MUTATIONS_ENABLED)
        if (!diagnostic) try { AndroidDiagnosticCopies.open(); fail("Normal app entered diagnostic I/O") }
        catch (_: IllegalStateException) { /* Refused before permission or filesystem access. */ }
    }
}
