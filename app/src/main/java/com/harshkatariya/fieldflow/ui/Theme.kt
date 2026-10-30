package com.harshkatariya.fieldflow.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val light = lightColorScheme(primary = Color(0xFF006D65), onPrimary = Color.White, primaryContainer = Color(0xFFC4F4EB), onPrimaryContainer = Color(0xFF003832),
    secondary = Color(0xFF47617C), secondaryContainer = Color(0xFFD7EDEB), onSecondaryContainer = Color(0xFF123D3A), surfaceContainer = Color(0xFFE9EFF5), background = Color(0xFFF3F6FA), surface = Color(0xFFFCFDFF), surfaceVariant = Color(0xFFE6EDF3),
    onSurface = Color(0xFF15263C), onBackground = Color(0xFF15263C), error = Color(0xFFB42336))
private val dark = darkColorScheme(primary = Color(0xFF6CE0CE), onPrimary = Color(0xFF003832), primaryContainer = Color(0xFF004D45), onPrimaryContainer = Color(0xFFB7F6E8),
    secondary = Color(0xFFADC6E2), secondaryContainer = Color(0xFF24463F), onSecondaryContainer = Color(0xFFC4F4EB), surfaceContainer = Color(0xFF16263A), background = Color(0xFF0B1421), surface = Color(0xFF142134), surfaceVariant = Color(0xFF23354B),
    onSurface = Color(0xFFE4ECF6), onBackground = Color(0xFFE4ECF6), error = Color(0xFFFFB3B8))
@Composable fun FieldTheme(dark: Boolean, content: @Composable () -> Unit) { MaterialTheme(colorScheme = if (dark) com.harshkatariya.fieldflow.ui.dark else light, content = content) }
