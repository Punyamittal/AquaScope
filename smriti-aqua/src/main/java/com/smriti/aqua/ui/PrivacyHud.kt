package com.smriti.aqua.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Always-visible offline guarantee: zero network, on-device only. */
@Composable
fun PrivacyHud(modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MossGreenSoft),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PRIVATE BY DEFAULT",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MossGreen,
                    modifier = Modifier.weight(1f),
                )
                Badge("MIC")
                Badge("NPU")
            }
            HudRow("Network calls: 0")
            HudRow("Airplane-mode ready")
            HudRow("No account · No server")
        }
    }
}

@Composable
private fun HudRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Badge(label: String) {
    Surface(
        color = AmberSoft,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AmberDeep,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
