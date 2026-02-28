package com.waveguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.waveguard.ui.navigation.NavGraph
import com.waveguard.ui.theme.WaveGuardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WaveGuardTheme {
                NavGraph()
            }
        }
    }
}
