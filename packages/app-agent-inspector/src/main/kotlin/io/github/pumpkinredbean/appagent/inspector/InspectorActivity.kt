package io.github.pumpkinredbean.appagent.inspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal class InspectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = InspectorColorScheme) {
                val store = InspectorRegistry.store
                if (store == null) {
                    DetachedInspectorScreen(onClose = ::finish)
                } else {
                    val snapshot by store.snapshot.collectAsStateWithLifecycle()
                    var selectedCallId by rememberSaveable { mutableStateOf<String?>(null) }
                    val selectedCall = snapshot.calls.firstOrNull { it.call.id == selectedCallId }

                    BackHandler(enabled = selectedCall != null) {
                        selectedCallId = null
                    }

                    if (selectedCall == null) {
                        InspectorHistoryScreen(
                            snapshot = snapshot,
                            onCallSelected = { selectedCallId = it },
                        )
                    } else {
                        InspectorCallDetailScreen(
                            record = selectedCall,
                            onBack = { selectedCallId = null },
                        )
                    }
                }
            }
        }
    }
}

private val InspectorColorScheme = lightColorScheme(
    primary = Color(0xFF3659C9),
    surface = Color.White,
    onSurface = Color(0xFF171A1F),
    outlineVariant = Color(0xFFE1E4E9),
)
