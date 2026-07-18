package com.autoaccounting.ui

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi

internal const val MAX_REQUESTED_REFRESH_RATE_HZ = 120f
private const val REFRESH_RATE_MATCH_TOLERANCE_HZ = 0.5f

internal fun Activity.requestHighRefreshRate() {
    val display = currentDisplay() ?: return
    val currentMode = display.mode
    val supportedRefreshRates = display.supportedModes
        .asSequence()
        .filter { mode ->
            mode.physicalWidth == currentMode.physicalWidth &&
                mode.physicalHeight == currentMode.physicalHeight
        }
        .map { it.refreshRate }
        .toList()
    applyRefreshRatePreference(selectPreferredRefreshRate(supportedRefreshRates))
}

@Suppress("DEPRECATION")
private fun Activity.currentDisplay(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        windowManager.defaultDisplay
    }

internal fun selectPreferredRefreshRate(
    supportedRefreshRatesHz: List<Float>,
    desiredRefreshRateHz: Float = MAX_REQUESTED_REFRESH_RATE_HZ
): Float? {
    if (!desiredRefreshRateHz.isFinite() || desiredRefreshRateHz <= 0f) return null

    val availableRates = supportedRefreshRatesHz
        .filter { it.isFinite() && it > 0f }
        .distinct()
        .sorted()
    if (availableRates.isEmpty()) return null

    return availableRates
        .filter { it <= desiredRefreshRateHz + REFRESH_RATE_MATCH_TOLERANCE_HZ }
        .maxOrNull()
}

internal fun Activity.applyRefreshRatePreference(targetRefreshRateHz: Float?) {
    if (targetRefreshRateHz == null) return

    window.attributes = window.attributes.apply {
        preferredRefreshRate = targetRefreshRateHz
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

    val contentRoot = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        ?: return
    contentRoot.post {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            contentRoot.propagateRequestedFrameRate(
                targetRefreshRateHz,
                true
            )
        } else {
            contentRoot.requestFrameRateRecursively(targetRefreshRateHz)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun View.requestFrameRateRecursively(frameRate: Float) {
    requestedFrameRate = frameRate
    if (this !is ViewGroup) return

    for (index in 0 until childCount) {
        getChildAt(index).requestFrameRateRecursively(frameRate)
    }
}
