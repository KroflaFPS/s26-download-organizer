package com.kroflafps.s26downloadorganizer.telemetry

import org.junit.Assert.*
import org.junit.Test

class TriggerLifecycleRepairTest {
    @Test fun stopDuringRearmCancelsAndSuppressesJobFinished() {
        val state = TriggerLifecycle()
        var cancelled = false
        var finished = false
        state.finish({ assertTrue(state.stop { cancelled = true }) }, { finished = true })
        assertTrue(cancelled)
        assertFalse(finished)
    }

    @Test fun failedRearmStillFinishesOnlyOnceWithoutCreatingAnInProcessRetryLoop() {
        val state = TriggerLifecycle()
        var completions = 0
        try { state.finish({ throw IllegalStateException("synthetic scheduling failure") }, { completions++ }); fail() }
        catch (_: IllegalStateException) { }
        state.finish({ fail("Duplicate retry") }, { completions++ })
        assertEquals(1, completions)
    }

    @Test fun duplicateFinishAndLateStopDoNotFinishOrCancelAgain() {
        val state = TriggerLifecycle()
        var completions = 0
        state.finish({}, { completions++ })
        state.finish({}, { completions++ })
        assertEquals(1, completions)
        assertFalse(state.stop { fail("Late stop cancelled completed work") })
    }
}
