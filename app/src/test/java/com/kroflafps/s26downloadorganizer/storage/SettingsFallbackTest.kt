package com.kroflafps.s26downloadorganizer.storage

import org.junit.Assert.*
import org.junit.Test

class SettingsFallbackTest {
    private val choices = listOf(StorageSettingsTarget.APP_ALL_FILES, StorageSettingsTarget.GENERAL_ALL_FILES, StorageSettingsTarget.APP_DETAILS)

    @Test fun firstSuccessStopsFallbacks() {
        val attempted = mutableListOf<StorageSettingsTarget>()
        assertTrue(openStorageAccessSettings { attempted += it; true })
        assertEquals(choices.take(1), attempted)
    }

    @Test fun missingAppSpecificScreenFallsBackInOrder() {
        val attempted = mutableListOf<StorageSettingsTarget>()
        assertTrue(openStorageAccessSettings { attempted += it; it == StorageSettingsTarget.APP_DETAILS })
        assertEquals(choices, attempted)
    }

    @Test fun allUnavailableIsReportedAsFailure() {
        assertFalse(openStorageAccessSettings { false })
    }

    @Test fun generalAllFilesScreenIsPreferredBeforeApplicationDetails() {
        val attempted = mutableListOf<StorageSettingsTarget>()
        assertTrue(openStorageAccessSettings { attempted += it; it == StorageSettingsTarget.GENERAL_ALL_FILES })
        assertEquals(choices.take(2), attempted)
    }
}
