package tech.tamerin.contextfriendpro

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What every screen needs: the app, copy in the chosen language, localized formatting, and a safe way to run work. */
class Ui(val app: ProApp, val lang: String, private val dict: Map<String, Map<String, String>>,
         val act: (suspend () -> Unit) -> Unit) {
    val t: CopyText = { key -> dict[lang]?.get(key) ?: dict.getValue("en")[key] ?: key }

    /** Copy with {placeholders}. */
    fun tf(key: String, vararg args: Pair<String, Any>): String = args.fold(t(key)) { s, (k, v) -> s.replace("{$k}", v.toString()) }

    val rtl = lang == "he"

    /** The app language decides dates and times; the device locale is used when it is the same language. */
    val locale: Locale = Locale.getDefault().let { device ->
        val same = device.language == lang || (lang == "he" && device.language == "iw")
        if (same) device else Locale.forLanguageTag(when (lang) { "de" -> "de-DE"; "he" -> "he-IL"; else -> "en-GB" })
    }

    fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(ms))

    fun day(ms: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val now = Calendar.getInstance()
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(then, now) -> t("today")
            sameDay(then, yesterday) -> t("yesterday")
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ms))
        }
    }

    fun dateTime(ms: Long) = day(ms) + " · " + time(ms)

    fun duration(ms: Long): String = if (ms < 60_000) tf("duration_s", "n" to (ms / 1000).coerceAtLeast(1))
        else tf("duration_min", "n" to ((ms + 30_000) / 60_000))

    /** Technical values (model ids, request ids, times) keep their order inside Hebrew text. */
    fun ltr(s: String): String = BidiFormatter.getInstance(rtl).unicodeWrap(s, TextDirectionHeuristicsCompat.LTR)

    /** The meeting's real title, or a localized date title. Never an invented topic. */
    fun title(s: Session): String = s.topicTitle() ?: tf("meeting_on", "date" to day(s.createdAt))

    /** Date, time and length; without a real title the day is already in the title, so only time and length. */
    fun meta(s: Session): String = listOfNotNull(if (s.topicTitle() == null) time(s.createdAt) else dateTime(s.createdAt),
        s.durationMs.takeIf { it > 0 }?.let(::duration)).joinToString(" · ")
}

/** Meeting content (summaries, transcripts) takes its direction from its own language, not from the UI language. */
@Composable fun contentStyle(base: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) =
    base.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content)

fun clock(ms: Long) = "%02d:%02d".format(ms / 60000, (ms / 1000) % 60)

// ---- small shared components -------------------------------------------------------------------------------------

val PagePadding = 20.dp

/** Top bar for child screens: back, title, optional actions. */
@Composable fun PageTop(title: String, ui: Ui, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, ui.t("back")) }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() })
        actions()
    }
}

@Composable fun Heading(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = modifier.semantics { heading() })

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp).semantics { heading() })

/** A white card with a light outline: the one card style in the app. */
@Composable fun Panel(modifier: Modifier = Modifier, color: Color = Brand.Surface, border: Color = Brand.Divider, onClick: (() -> Unit)? = null,
                      content: @Composable ColumnScope.() -> Unit) {
    val inner: @Composable () -> Unit = { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = color), border = BorderStroke(1.dp, border)) { inner() }
    else Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = color), border = BorderStroke(1.dp, border)) { inner() }
}

/** A state label: colour plus readable text (never colour alone). */
@Composable fun StatusChip(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                              icon: ImageVector? = null, container: Color = Brand.Primary, content: Color = Color.White) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if (enabled) Brand.Primary else Brand.Divider)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A tappable settings row: icon, title, current value, chevron. */
@Composable fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Brand.Primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.Muted)
    }
}

/** A labelled checkbox for one-time confirmations (switches are for saved preferences). */
@Composable fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** One option of a single choice. The whole row selects it, and screen readers announce one radio button with its label. */
@Composable fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** A note with an icon: information, warning or success. */
@Composable fun Note(text: String, icon: ImageVector, container: Color = Brand.PrimaryContainer, content: Color = Brand.OnPrimaryContainer, title: String? = null) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable fun StepDot(state: StepState) {
    when (state) {
        StepState.DONE -> Surface(color = Brand.Success, shape = CircleShape, modifier = Modifier.size(24.dp)) {
            Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = Color.White, modifier = Modifier.padding(3.dp))
        }
        StepState.CURRENT -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        StepState.PENDING -> Surface(color = Color.Transparent, shape = CircleShape, border = BorderStroke(2.dp, Brand.Outline), modifier = Modifier.size(24.dp)) {}
    }
}
