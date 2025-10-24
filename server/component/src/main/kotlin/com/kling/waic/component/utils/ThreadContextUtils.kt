package com.kling.waic.component.utils

import com.kling.waic.component.utils.Constants.DEFAULT_ACTIVITY
import org.slf4j.MDC

object ThreadContextUtils {
    private const val ACTIVITY = "activity"

    fun putActivity(activity: String) {
        MDC.put(ACTIVITY, activity)
    }

    fun getActivity(): String {
        return MDC.get(ACTIVITY) ?: DEFAULT_ACTIVITY
    }
}

object Constants {
    const val DEFAULT_ACTIVITY = "default"
}