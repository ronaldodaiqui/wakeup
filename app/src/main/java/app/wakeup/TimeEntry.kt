package app.wakeup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** 0-9, delete and enter, in big friendly keys. Shared by time entry and the math mission. */
@Composable
fun NumberPad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit,
    enterEnabled: Boolean = true,
    keyHeight: Dp = 68.dp,
) {
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '<', '0', '>')
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    val enter = k == '>'
                    val enabled = !enter || enterEnabled
                    Box(
                        Modifier.weight(1f).alpha(if (enabled) 1f else 0.35f)
                            .height(keyHeight).clip(RoundedCornerShape(18.dp))
                            .background(if (enter) Sun else CardHi)
                            .clickable(enabled = enabled) {
                                when (k) {
                                    '<' -> onDelete()
                                    '>' -> onEnter()
                                    else -> onDigit(k)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (k) {
                            '<' -> Icon(Icons.AutoMirrored.Filled.Backspace, "Delete", tint = Ink)
                            '>' -> Icon(Icons.Filled.Check, "Done", tint = OnAccent)
                            else -> Text(k.toString(), fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Ink)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What typed digits mean, read like a microwave:
 * "7" → 7:00, "12" → 12:00, "730" → 7:30, "1234" → 12:34.
 * Returns (hour as typed, minute), or null if it isn't a real time.
 */
fun parseTyped(digits: String): Pair<Int, Int>? {
    if (digits.isEmpty()) return null
    val (h, m) = if (digits.length <= 2) digits.toInt() to 0
    else digits.dropLast(2).toInt() to digits.takeLast(2).toInt()
    return if (h in 0..23 && m in 0..59) h to m else null
}

/** Typed hour → 24 h, using AM/PM when it's ambiguous (1–12). */
fun typedTo24(h: Int, pm: Boolean, use24: Boolean): Int = when {
    use24 || h > 12 -> h
    h == 0 -> 0
    else -> h % 12 + if (pm) 12 else 0
}

@Composable
fun TimeEntryDialog(hour: Int, minute: Int, onDone: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val use24 = remember { is24h(ctx) }
    var digits by remember { mutableStateOf("") }
    var pm by remember { mutableStateOf(hour >= 12) }

    val typed = parseTyped(digits)
    // What would be saved right now (the old time until you type something).
    val result: Pair<Int, Int>? = if (digits.isEmpty()) hour to minute
    else typed?.let { (h, m) -> typedTo24(h, pm, use24) to m }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Card).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Type the time", color = Dim, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // Big readout
            Row(verticalAlignment = Alignment.Bottom) {
                val shown = when {
                    digits.isEmpty() -> timeParts(ctx, hour, minute)
                    typed == null -> (if (digits.length <= 2) "$digits:00" else digits.dropLast(2) + ":" + digits.takeLast(2)) to ""
                    else -> timeParts(ctx, result!!.first, result.second)
                }
                val (main, ap) = shown
                Text(
                    main, fontSize = 64.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        digits.isEmpty() -> Dim
                        typed == null -> Bad
                        else -> Ink
                    },
                )
                if (ap.isNotEmpty()) {
                    Text(" $ap", fontSize = 22.sp, color = Dim, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
            Text(
                when {
                    typed == null && digits.isNotEmpty() -> "That's not a real time"
                    digits.isEmpty() -> "e.g. 730 for 7:30, 1234 for 12:34"
                    else -> " "
                },
                color = if (typed == null && digits.isNotEmpty()) Bad else Dim,
                style = MaterialTheme.typography.bodySmall,
            )

            // AM / PM (only matters for 1–12 in 12-hour mode)
            if (!use24) {
                val ambiguous = digits.isEmpty() || (typed != null && typed.first in 1..12)
                Row(
                    Modifier.padding(top = 12.dp).alpha(if (ambiguous) 1f else 0.35f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf("AM" to false, "PM" to true).forEach { (l, isPm) ->
                        val sel = if (ambiguous) pm == isPm else (result?.first ?: 0) >= 12 == isPm
                        Box(
                            Modifier.width(84.dp).height(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (sel) Lav else CardHi)
                                .clickable(enabled = ambiguous) { pm = isPm },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(l, fontWeight = FontWeight.SemiBold, color = if (sel) Night else Dim)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            NumberPad(
                onDigit = { if (digits.length < 4) digits += it },
                onDelete = { digits = digits.dropLast(1) },
                onEnter = { result?.let { (h, m) -> onDone(h, m) } },
                enterEnabled = result != null,
                keyHeight = 60.dp,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 4.dp)) {
                Text("Cancel", color = Dim)
            }
        }
    }
}
