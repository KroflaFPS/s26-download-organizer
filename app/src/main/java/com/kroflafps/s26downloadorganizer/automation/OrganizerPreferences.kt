package com.kroflafps.s26downloadorganizer.automation

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OrganizerPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableAutoSort = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SORT, true))
    val autoSortEnabled = mutableAutoSort.asStateFlow()

    fun isAutoSortEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SORT, true)

    fun setAutoSortEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_SORT, enabled) }
        mutableAutoSort.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "s26_organizer_prefs"
        const val KEY_AUTO_SORT = "auto_sort_enabled"
    }
}
