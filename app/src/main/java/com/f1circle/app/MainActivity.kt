package com.f1circle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.f1circle.app.data.OpenF1Client
import com.f1circle.app.data.RaceSession
import com.f1circle.app.data.SessionData
import com.f1circle.app.model.CircleModel
import com.f1circle.app.ui.LoadingScreen
import com.f1circle.app.ui.ReplayScreen
import com.f1circle.app.ui.SessionPicker
import com.f1circle.app.ui.Years
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF111111)).safeDrawingPadding()) {
                    App()
                }
            }
        }
    }
}

private class Loaded(val data: SessionData, val model: CircleModel)

@Composable
private fun App() {
    val context = LocalContext.current
    val client = remember { OpenF1Client(context.cacheDir) }

    var year by remember { mutableIntStateOf(Years.first()) }
    var sessions by remember { mutableStateOf<List<RaceSession>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var listAttempt by remember { mutableIntStateOf(0) }

    var picked by remember { mutableStateOf<RaceSession?>(null) }
    var step by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf<Loaded?>(null) }

    LaunchedEffect(year, listAttempt) {
        sessions = null
        listError = null
        try {
            sessions = withContext(Dispatchers.IO) { client.raceSessions(year) }
        } catch (e: Exception) {
            listError = "Couldn't load races: ${e.message}"
        }
    }

    LaunchedEffect(picked) {
        val session = picked ?: return@LaunchedEffect
        loaded = null
        try {
            loaded = withContext(Dispatchers.IO) {
                val data = client.loadSession(session) { step = it }
                Loaded(data, CircleModel(data))
            }
        } catch (e: Exception) {
            picked = null
            listError = "Couldn't load ${session.country}: ${e.message}"
        }
    }

    BackHandler(enabled = picked != null) {
        picked = null
        loaded = null
    }

    val current = loaded
    val session = picked
    when {
        session == null -> SessionPicker(year, sessions, listError, onYear = { year = it }, onPick = { picked = it }, onRetry = { listAttempt++ })
        current == null -> LoadingScreen(session, step)
        else -> ReplayScreen(current.data, current.model, onBack = {
            picked = null
            loaded = null
        })
    }
}
