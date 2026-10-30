package com.harshkatariya.fieldflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harshkatariya.fieldflow.ui.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint class MainActivity : ComponentActivity() {
    private val vm: FieldViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            FieldTheme(if (theme == "system") isSystemInDarkTheme() else theme == "dark") { FieldFlow(vm) }
        }
    }
}
