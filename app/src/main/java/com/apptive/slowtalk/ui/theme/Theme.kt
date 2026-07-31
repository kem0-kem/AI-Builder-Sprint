package com.apptive.slowtalk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.apptive.slowtalk.Ink
import com.apptive.slowtalk.BlockSurface
import com.apptive.slowtalk.Paper
import com.apptive.slowtalk.Purple

private val AppColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0EBFF),
    onPrimaryContainer = Ink,
    secondary = Color(0xFFE98175),
    background = Paper,
    onBackground = Ink,
    surface = BlockSurface,
    onSurface = Ink,
    outline = Color(0xFFDDD7D0)
)

@Composable
fun SlowTalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = Typography(),
        content = content
    )
}
