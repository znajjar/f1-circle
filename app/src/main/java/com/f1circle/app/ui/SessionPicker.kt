package com.f1circle.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.f1circle.app.data.RaceSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Years = listOf(2026, 2025, 2024, 2023)

private val DateFormat = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

@Composable
fun SessionPicker(
    year: Int,
    sessions: List<RaceSession>?,
    error: String?,
    onYear: (Int) -> Unit,
    onPick: (RaceSession) -> Unit,
    onRetry: () -> Unit,
    onLive: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).padding(16.dp)) {
        Text("F1 Circle", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Follow a session live or pick a race to replay", color = Color(0xFF999999), fontSize = 14.sp)
        Row(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .background(Color(0xFF2A1215), RoundedCornerShape(10.dp))
                .clickable(onClick = onLive)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(Color(0xFFE53935), CircleShape))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Live timing", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Whatever session is running right now", color = Color(0xFFBBBBBB), fontSize = 13.sp)
            }
            Text("›", color = Color.White, fontSize = 22.sp)
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Years.forEach { y ->
                val on = y == year
                Text(
                    y.toString(),
                    color = if (on) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(if (on) Color.White else Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                        .clickable { onYear(y) }
                        .padding(vertical = 8.dp),
                )
            }
        }
        when {
            error != null -> Message(error, onRetry)
            sessions == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            sessions.isEmpty() -> Message("No races found for $year.", null)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions.reversed(), key = { it.sessionKey }) { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1C1C1C), RoundedCornerShape(10.dp))
                            .clickable { onPick(s) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.country, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("${s.circuit} · ${s.name}", color = Color(0xFF999999), fontSize = 13.sp)
                        }
                        Text(DateFormat.format(Instant.ofEpochMilli(s.startMs)), color = Color(0xFFBBBBBB), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(session: RaceSession, step: String) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF111111)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("${session.country} · ${session.name}", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
        Text("Loading $step…", color = Color(0xFF999999), fontSize = 14.sp)
    }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color(0xFFBBBBBB), textAlign = TextAlign.Center)
        onRetry?.let { TextButton(onClick = it) { Text("Retry") } }
    }
}
