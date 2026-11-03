package com.kroflafps.s26downloadorganizer.diagnostic

import android.app.Application
import com.kroflafps.s26downloadorganizer.BuildConfig
import com.kroflafps.s26downloadorganizer.operations.OperationPolicy
import com.kroflafps.s26downloadorganizer.storage.AndroidDiagnosticCopies
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DiagnosticPlatformGateTest {
    @Test fun legacyAndroidCannotOpenDiagnosticIoEvenWithDebugCapabilityCompiled() {
        assertTrue(BuildConfig.DEBUG && BuildConfig.DIAGNOSTIC_COPY_ENABLED)
        assertFalse(OperationPolicy.MUTATIONS_ENABLED)
        assertFalse(AndroidDiagnosticCopies.allowed())
        assertFalse(AndroidDiagnosticCopies.permission())
        try { AndroidDiagnosticCopies.open(); fail("Legacy API gate bypassed") } catch (_: IllegalStateException) { }
    }
}
