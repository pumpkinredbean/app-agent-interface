package io.github.pumpkinredbean.appagent.example.tradingv3

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import io.github.pumpkinredbean.appagent.example.tradingv3.agent.TradingAgentController
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.MarketTheme
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.session.AgentSessionScreen

class MainActivity : ComponentActivity() {
    private lateinit var controller: TradingAgentController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller = ViewModelProvider(this)[TradingAgentController::class.java]

        setContent {
            val darkTheme = controller.state.darkTheme
            SideEffect {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MarketTheme(darkTheme = darkTheme) {
                AgentSessionScreen(controller = controller)
            }
        }
    }
}
