package com.harukisolodev.harukistream.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class NovaWindowClass { COMPACT, MEDIUM, EXPANDED, CAR_LANDSCAPE }

data class NovaAdaptiveInfo(
    val windowClass: NovaWindowClass,
    val width: Dp,
    val height: Dp,
    val useNavigationRail: Boolean,
    val feedColumns: Int,
    val largeTouchTargets: Boolean,
    val contentMaxWidth: Dp
) {
    val isCompact: Boolean get() = windowClass == NovaWindowClass.COMPACT
    val isLarge: Boolean get() = !isCompact
    val isCarLike: Boolean get() = windowClass == NovaWindowClass.CAR_LANDSCAPE
}

fun novaAdaptiveInfo(width: Dp, height: Dp): NovaAdaptiveInfo {
    val carLike = width >= 900.dp && height <= 680.dp
    val cls = when {
        carLike -> NovaWindowClass.CAR_LANDSCAPE
        width < 600.dp -> NovaWindowClass.COMPACT
        width < 840.dp -> NovaWindowClass.MEDIUM
        else -> NovaWindowClass.EXPANDED
    }
    val columns = when {
        width >= 1280.dp -> 3
        width >= 700.dp -> 2
        else -> 1
    }
    return NovaAdaptiveInfo(
        windowClass = cls,
        width = width,
        height = height,
        useNavigationRail = width >= 600.dp,
        feedColumns = columns,
        largeTouchTargets = carLike,
        contentMaxWidth = if (width >= 1400.dp) 1320.dp else width
    )
}
