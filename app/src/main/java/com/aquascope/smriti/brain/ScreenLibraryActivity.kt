package com.aquascope.smriti.brain

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aquascope.smriti.brain.library.ScreenClip
import com.aquascope.smriti.brain.library.ScreenLibraryStore
import com.aquascope.ui.SmritiScreenActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * Browse swipe/gallery captures by app, website, ScreenMind category, or keyword.
 */
class ScreenLibraryActivity : SmritiScreenActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmritiBrainTheme {
                ScreenLibraryScreen(onClose = { finish() })
            }
        }
    }
}

private val Navy = Color(0xFF071A2B)
private val Surface = Color(0xCC0C2A43)
private val Hairline = Color(0xFF1E3F55)
private val Cyan = Color(0xFF4AAFC2)
private val Ocean = Color(0xFF126A8A)
private val Ink = Color(0xFFF5F5F0)
private val Faint = Color(0xFF7A9398)
private val Panel = RoundedCornerShape(18.dp)

private enum class Facet { ALL, CATEGORY, APP, WEBSITE, KEYWORD }

@Composable
private fun ScreenLibraryScreen(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var clips by remember { mutableStateOf(ScreenLibraryStore.all(ctx)) }
    var query by remember { mutableStateOf("") }
    var facet by remember { mutableStateOf(Facet.ALL) }
    var selected by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val listener: (List<ScreenClip>) -> Unit = { clips = it }
        ScreenLibraryStore.addListener(listener)
        onDispose { ScreenLibraryStore.removeListener(listener) }
    }

    val categories = remember(clips) { ScreenLibraryStore.categories(ctx) }
    val apps = remember(clips) { ScreenLibraryStore.apps(ctx) }
    val sites = remember(clips) { ScreenLibraryStore.websites(ctx) }
    val keys = remember(clips) { ScreenLibraryStore.keywords(ctx) }

    val filtered = remember(clips, query, facet, selected) {
        when (facet) {
            Facet.ALL -> ScreenLibraryStore.search(ctx, query = query)
            Facet.CATEGORY -> ScreenLibraryStore.search(ctx, query = query, category = selected)
            Facet.APP -> ScreenLibraryStore.search(ctx, query = query, app = selected)
            Facet.WEBSITE -> ScreenLibraryStore.search(ctx, query = query, website = selected)
            Facet.KEYWORD -> ScreenLibraryStore.search(ctx, query = query, keyword = selected)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Navy)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SCREEN LIBRARY", color = Ink, fontSize = 18.sp, letterSpacing = 2.sp)
                Text(
                    "${clips.size} captures · filter by app / site / keyword",
                    color = Faint,
                    fontSize = 11.sp
                )
            }
            TextButton(onClick = onClose) {
                Text("Close", color = Cyan)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            placeholder = { Text("Search text, apps, sites…", color = Faint) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                cursorColor = Cyan,
                focusedBorderColor = Cyan,
                unfocusedBorderColor = Ocean
            )
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Facet.entries.forEach { f ->
                val label = when (f) {
                    Facet.ALL -> "All"
                    Facet.CATEGORY -> "Category"
                    Facet.APP -> "Apps"
                    Facet.WEBSITE -> "Websites"
                    Facet.KEYWORD -> "Keywords"
                }
                Chip(label, selected = facet == f) {
                    facet = f
                    selected = null
                }
            }
        }

        if (facet != Facet.ALL) {
            val options = when (facet) {
                Facet.CATEGORY -> categories
                Facet.APP -> apps
                Facet.WEBSITE -> sites
                Facet.KEYWORD -> keys
                else -> emptyList()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (options.isEmpty()) {
                    Text(
                        "No ${facet.name.lowercase()} yet — swipe a few screens",
                        color = Faint,
                        fontSize = 12.sp
                    )
                } else {
                    options.forEach { opt ->
                        Chip(opt, selected = selected == opt, compact = true) {
                            selected = if (selected == opt) null else opt
                        }
                    }
                }
            }
        }

        Text(
            "${filtered.size} results",
            color = Faint,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filtered, key = { it.id }) { clip ->
                ClipCard(clip) {
                    val video = clip.videoPath
                    if (!video.isNullOrBlank() && File(video).exists()) {
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                File(video)
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(Intent.createChooser(intent, "Play clip"))
                        } catch (t: Throwable) {
                            Toast.makeText(ctx, "Could not play: ${t.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        NeuralCoreSession.lastScreenOcr = clip.ocrText
                        NeuralCoreSession.lastScreenOcrPath = clip.ocrPath
                        Toast.makeText(ctx, "Loaded into Ask — open Neural Core", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Navy else Ink,
        fontSize = if (compact) 12.sp else 13.sp,
        modifier = Modifier
            .background(if (selected) Cyan else Surface, RoundedCornerShape(20.dp))
            .border(1.dp, if (selected) Cyan else Hairline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun ClipCard(clip: ScreenClip, onUse: () -> Unit) {
    val whenText = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
        .format(Date(clip.timestampMs))
    val bmp = remember(clip.imagePath) {
        clip.imagePath?.let { path ->
            runCatching {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface, Panel)
            .border(1.dp, Hairline, Panel)
            .clickable(onClick = onUse)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .background(Navy, RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(clip.appName, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${clip.category} · $whenText",
                    color = Cyan,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (clip.websites.isNotEmpty()) {
                    Text(
                        clip.websites.take(2).joinToString(" · "),
                        color = Faint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        if (clip.summary.isNotBlank()) {
            Text(
                clip.summary,
                color = Ink,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (!clip.videoPath.isNullOrBlank()) {
            Text(
                "PEACE video · tap to play",
                color = Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (clip.keywords.isNotEmpty()) {
            Text(
                clip.keywords.take(8).joinToString("  ·  "),
                color = Faint,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            clip.ocrText.take(180).ifBlank { "(no OCR text)" },
            color = Faint,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
