package dev.hackatlas.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val route = if (BuildConfig.DEBUG) intent.getStringExtra("screen") ?: "home" else "home"
        // Debug-only proof that saved details do not depend on the bundled catalog.
        val savedOnly = BuildConfig.DEBUG && intent.getBooleanExtra("savedOnly", false)
        val appState = (application as HackAtlasApplication).guestState(catalogEnabled = !savedOnly)
        setContent { App(appState, initialRoute = route) }
    }
}
