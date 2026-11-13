package app.wakeup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Pick a palette. Each choice applies right away so you can see it behind the dialog. */
@Composable
fun ColorPickerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val current = Palettes.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Card).padding(20.dp),
        ) {
            Text("Colors", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Tap one to try it.", color = Dim, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Palettes.all.forEach { p -> PaletteRow(p, selected = p.key == current.key) { Palettes.set(ctx, p) } }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                Text("Done")
            }
        }
    }
}

/** A row drawn in the palette's own colors, so it previews itself. */
@Composable
private fun PaletteRow(p: Palette, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.bg)
            .border(2.dp, if (selected) p.accent else p.cardHi, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(p.credit, color = p.dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(p.accent, p.second, p.good, p.bad, p.card).forEach { c ->
                    Box(Modifier.size(18.dp).clip(CircleShape).background(c))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (selected) p.accent else p.card),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = p.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}
