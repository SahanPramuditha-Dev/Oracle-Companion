package dev.oracle.companion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import dev.oracle.companion.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private class SphereMesh(val total: Int) {
    val x = FloatArray(total)
    val y = FloatArray(total)
    val z = FloatArray(total)
    init {
        for (i in 0 until total) {
            val yVal = 1f - 2f * (i + 0.5f) / total
            val r = kotlin.math.sqrt((1f - yVal * yVal).coerceAtLeast(0f))
            val phi = i * 2.39996323f
            x[i] = kotlin.math.cos(phi) * r
            y[i] = yVal
            z[i] = kotlin.math.sin(phi) * r
        }
    }
}

private val SharedSphereMesh = SphereMesh(140)

internal val LocalDarkTheme = compositionLocalOf { true }

internal val OracleDarkColors = darkColorScheme(
    primary = Color(0xffa5b4fc),
    onPrimary = Color(0xff101435),
    primaryContainer = Color(0xff272e55),
    onPrimaryContainer = Color(0xffe0e7ff),
    secondary = Color(0xff67e8f9),
    onSecondary = Color(0xff07323a),
    secondaryContainer = Color(0xff153640),
    onSecondaryContainer = Color(0xffb4f3fc),
    background = Color(0xff0a0b10),
    onBackground = Color(0xffedf0f8),
    surface = Color(0xff151822),
    onSurface = Color(0xffedf0f8),
    surfaceVariant = Color(0xff202534),
    onSurfaceVariant = Color(0xffa9b2c5),
    outline = Color(0xff687389),
    outlineVariant = Color(0xff343b4d),
    error = Color(0xffff859a),
    onError = Color(0xff450b1b),
    errorContainer = Color(0xff48202d),
    onErrorContainer = Color(0xffffd9e0)
)

// 2. Luxury Warm Editorial Light Theme (Ref: User Reference Design)
internal val OracleLightColors = lightColorScheme(
    primary = Color(0xffde6736), // Warm Terracotta / Burnt Orange (#DE6736)
    onPrimary = Color(0xffffffff),
    primaryContainer = Color(0xfff7ede7),
    onPrimaryContainer = Color(0xff3e1500),
    secondary = Color(0xffde6736),
    onSecondary = Color(0xffffffff),
    secondaryContainer = Color(0xfff2ebe2),
    onSecondaryContainer = Color(0xff2b241e),
    background = Color(0xffece6dd), // Warm Linen / Oatmeal Sand (#ECE6DD)
    onBackground = Color(0xff1a1715), // Deep Charcoal (#1A1715)
    surface = Color(0xfff5f1eb), // Soft Warm Cream Card (#F5F1EB)
    onSurface = Color(0xff1a1715),
    surfaceVariant = Color(0xffdfd8cd), // Status Pill Sand (#DFD8CD)
    onSurfaceVariant = Color(0xff7d756d), // Warm Stone Taupe (#7D756D)
    outline = Color(0xffdfd8ce),
    outlineVariant = Color(0xffe8e2d8)
)

internal val OracleColors = OracleLightColors

internal fun cardBorderBrush(isDark: Boolean): Brush = if (isDark) {
    Brush.verticalGradient(listOf(Color(0x26ffffff), Color(0x1fffffff)))
} else {
    Brush.verticalGradient(listOf(Color(0xffe5ddd1), Color(0xffdfd7ca)))
}

internal val CardBorderBrush: Brush
    @Composable get() = cardBorderBrush(LocalDarkTheme.current)

internal val OracleTypography = Typography().let { defaults ->
    Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = FontFamily.SansSerif),
        displayMedium = defaults.displayMedium.copy(fontFamily = FontFamily.SansSerif),
        displaySmall = defaults.displaySmall.copy(fontFamily = FontFamily.SansSerif),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = FontFamily.SansSerif),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = defaults.titleLarge.copy(fontFamily = FontFamily.SansSerif),
        titleMedium = defaults.titleMedium.copy(fontFamily = FontFamily.SansSerif),
        titleSmall = defaults.titleSmall.copy(fontFamily = FontFamily.SansSerif),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = defaults.bodySmall.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = defaults.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        labelMedium = defaults.labelMedium.copy(fontFamily = FontFamily.SansSerif),
        labelSmall = defaults.labelSmall.copy(fontFamily = FontFamily.SansSerif)
    )
}

internal enum class HomeTab(val label: String) {
    HOME("Home"), CHAT("Chat"), RECORDS("Records"), ATLAS("Atlas"), PEOPLE("People"), VISION("Camera"), CONTROL("Control"), MONITOR("Monitor"), ACTIVITY("Activity"), DEVICE("Device"), DEVICE_CONTROLS("Device controls")
}

private data class NavDestination(
    val tab: HomeTab,
    val label: String,
    val glyph: String,
    val isHubTrigger: Boolean = false
)

private data class HubModuleItem(
    val title: String,
    val subtitle: String,
    val tag: String,
    val glyph: String,
    val accentColor: Color,
    val tab: HomeTab?,
    val isSettings: Boolean = false
)

// Floating Glassmorphic Capsule Island Navigation Dock with Workspaces Hub
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionNavigation(
    tab: HomeTab,
    onSettings: (() -> Unit)? = null,
    onNavigate: (HomeTab) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val navBg = if (isDark) Color(0xF0111622) else Color(0xF7FFFFFF)
    val navBorder = CardBorderBrush
    val activePillBg = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val activePillContent = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF)
    val inactiveContent = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    var showHubSheet by rememberSaveable { mutableStateOf(false) }

    val isHubActive = showHubSheet || tab in setOf(
        HomeTab.VISION, HomeTab.ATLAS, HomeTab.RECORDS,
        HomeTab.PEOPLE, HomeTab.ACTIVITY, HomeTab.DEVICE
    )

    val navTabs = remember {
        listOf(
            NavDestination(HomeTab.HOME, "Home", "Home"),
            NavDestination(HomeTab.CHAT, "Assistant", "Chat"),
            NavDestination(HomeTab.CONTROL, "Controls", "Control"),
            NavDestination(HomeTab.VISION, "Hub", "Hub", isHubTrigger = true)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = navBg,
            border = BorderStroke(1.dp, navBorder),
            shadowElevation = if (isDark) 8.dp else 12.dp,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                navTabs.forEach { dest ->
                    val isSelected = if (dest.isHubTrigger) {
                        isHubActive
                    } else if (dest.tab == HomeTab.CONTROL) {
                        tab in setOf(HomeTab.CONTROL, HomeTab.DEVICE_CONTROLS, HomeTab.MONITOR)
                    } else {
                        dest.tab == tab
                    }

                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed) 0.94f else if (isSelected) 1.0f else 0.96f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "tab_scale"
                    )

                    Surface(
                        onClick = {
                            if (dest.isHubTrigger) {
                                showHubSheet = true
                            } else {
                                onNavigate(dest.tab)
                            }
                        },
                        interactionSource = interactionSource,
                        shape = CircleShape,
                        color = if (isSelected) activePillBg else Color.Transparent,
                        shadowElevation = if (isSelected) (if (isDark) 4.dp else 6.dp) else 0.dp,
                        modifier = Modifier
                            .weight(if (isSelected) 1.35f else 1.0f)
                            .fillMaxHeight()
                            .scale(scale)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(22.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CompanionGlyph(
                                    name = dest.glyph,
                                    color = if (isSelected) activePillContent else inactiveContent
                                )
                            }
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn(tween(200)) + expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)),
                                exit = fadeOut(tween(150)) + shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow))
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = dest.label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp
                                        ),
                                        fontFamily = FontFamily.SansSerif,
                                        maxLines = 1,
                                        softWrap = false,
                                        color = activePillContent
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHubSheet) {
        WorkspacesHubSheet(
            onDismiss = { showHubSheet = false },
            onNavigate = { destination ->
                showHubSheet = false
                onNavigate(destination)
            },
            onSettings = {
                showHubSheet = false
                if (onSettings != null) onSettings()
                else onNavigate(HomeTab.DEVICE)
            }
        )
    }
}

// Workspaces & Hub Bottom Sheet for universal module access from any screen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspacesHubSheet(
    onDismiss: () -> Unit,
    onNavigate: (HomeTab) -> Unit,
    onSettings: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val sheetBg = if (isDark) Color(0xFF111622) else Color(0xFFFFFFFF)
    val cardBg = if (isDark) Color(0xFF171E2E) else Color(0xFFF8FAFC)
    val cardBorder = CardBorderBrush

    val items = remember {
        listOf(
            HubModuleItem(
                title = "Vision Studio",
                subtitle = "Optical AI & HUD",
                tag = "CAMERA",
                glyph = "Camera",
                accentColor = Color(0xFFF97316),
                tab = HomeTab.VISION
            ),
            HubModuleItem(
                title = "Atlas Maps",
                subtitle = "Routes & Parking",
                tag = "GPS",
                glyph = "Atlas",
                accentColor = Color(0xFF38BDF8),
                tab = HomeTab.ATLAS
            ),
            HubModuleItem(
                title = "Records & Notes",
                subtitle = "Timers & Sync",
                tag = "TASKS",
                glyph = "Records",
                accentColor = Color(0xFFA855F7),
                tab = HomeTab.RECORDS
            ),
            HubModuleItem(
                title = "People & Identity",
                subtitle = "Face Registry",
                tag = "IDENTITY",
                glyph = "People",
                accentColor = Color(0xFF10B981),
                tab = HomeTab.PEOPLE
            ),
            HubModuleItem(
                title = "System Monitor",
                subtitle = "Host Telemetry",
                tag = "PC HUD",
                glyph = "Control",
                accentColor = Color(0xFF06B6D4),
                tab = HomeTab.MONITOR
            ),
            HubModuleItem(
                title = "Device Settings",
                subtitle = "Host & Network",
                tag = "CONFIG",
                glyph = "Settings",
                accentColor = Color(0xFFF59E0B),
                tab = null,
                isSettings = true
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetBg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Workspaces & Tools",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp
                        ),
                        fontFamily = FontFamily.SansSerif,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Instant access to all connected companion modules",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.SansSerif,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(38.dp)
                ) {
                    Text(
                        "×",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2-Column Grid
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { item ->
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val scale by animateFloatAsState(
                                targetValue = if (isPressed) 0.95f else 1.0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "hub_card_scale"
                            )

                            Surface(
                                onClick = {
                                    if (item.isSettings) onSettings()
                                    else item.tab?.let { onNavigate(it) }
                                },
                                interactionSource = interactionSource,
                                shape = RoundedCornerShape(18.dp),
                                color = cardBg,
                                border = BorderStroke(1.dp, cardBorder),
                                shadowElevation = if (isDark) 2.dp else 4.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(84.dp)
                                    .scale(scale)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = item.accentColor.copy(alpha = if (isDark) 0.18f else 0.14f),
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CompanionGlyph(
                                                name = item.glyph,
                                                color = item.accentColor
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            ),
                                            fontFamily = FontFamily.SansSerif,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = item.subtitle,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.sp
                                            ),
                                            fontFamily = FontFamily.SansSerif,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Status Radar Pulse Dot
@Composable
internal fun StatusRadarDot(online: Boolean) {
    val isDark = LocalDarkTheme.current
    val greenColor = if (isDark) Color(0xff10b981) else Color(0xff059669)
    if (!online) {
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        return
    }
    val transition = rememberInfiniteTransition(label = "radar")
    val radius by transition.animateFloat(
        initialValue = 4f, targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_radius"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.75f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_alpha"
    )

    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = greenColor.copy(alpha = alpha),
                radius = radius.dp.toPx(),
                style = Stroke(1.2.dp.toPx())
            )
            drawCircle(
                color = greenColor,
                radius = 3.5.dp.toPx()
            )
        }
    }
}

// Radial Dial Telemetry Gauge (Ref: Image 1 Circular Dial)
@Composable
internal fun RadialDialGauge(
    title: String,
    currentValue: Int?,
    unit: String = "%",
    subtitle: String,
    activeColor: Color,
    selectedMode: String,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    ramPercent: Int? = null,
    remoteConnection: Boolean = false,
    connected: Boolean = false,
    onInspect: (() -> Unit)? = null
) {
    val isDark = LocalDarkTheme.current
    val animatedProgress by animateFloatAsState(
        targetValue = (currentValue ?: 0).coerceIn(0, 100) / 100f,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "radial_progress"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardBorderBrush),
        shadowElevation = if (isDark) 4.dp else 8.dp
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Mode Toggle Pills (CPU / RAM / SYS) - Single-line, no wrap bug
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0xff182233) else Color(0xffe8edf4))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf("CPU", "RAM", "SYS").forEach { mode ->
                        val isSelected = mode == selectedMode
                        Surface(
                            onClick = { onModeChange(mode) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) {
                                if (isDark) MaterialTheme.colorScheme.primaryContainer else Color.White
                            } else Color.Transparent,
                            border = if (isSelected) {
                                BorderStroke(1.dp, if (isDark) Color(0xff98c5f2).copy(alpha = 0.4f) else Color(0xffcbd5e1))
                            } else null,
                            shadowElevation = if (isSelected && !isDark) 2.dp else 0.dp
                        ) {
                            Text(
                                text = mode,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = if (isSelected) {
                                    if (isDark) Color(0xff98c5f2) else Color(0xff1d4ed8)
                                } else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Radial Dial Arc Canvas
            Box(
                modifier = Modifier.size(190.dp).padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val centerOffset = center
                    val arcRadius = size.minDimension * 0.45f
                    val totalTicks = 38
                    val startAngle = 145f
                    val sweepAngle = 250f

                    for (i in 0 until totalTicks) {
                        val frac = i / (totalTicks - 1f)
                        val tickAngle = startAngle + frac * sweepAngle
                        val tickRad = Math.toRadians(tickAngle.toDouble())
                        val isLit = frac <= animatedProgress

                        val innerR = arcRadius - (if (i % 5 == 0) 13.dp.toPx() else 8.dp.toPx())
                        val outerR = arcRadius

                        val startX = centerOffset.x + innerR * cos(tickRad).toFloat()
                        val startY = centerOffset.y + innerR * sin(tickRad).toFloat()
                        val endX = centerOffset.x + outerR * cos(tickRad).toFloat()
                        val endY = centerOffset.y + outerR * sin(tickRad).toFloat()

                        val tickColor = if (isLit) {
                            if (frac > 0.82f) Color(0xffff4d4f) else activeColor
                        } else {
                            if (isDark) Color(0xff222e40) else Color(0xffe2e8f0)
                        }

                        drawLine(
                            color = tickColor,
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = if (i % 5 == 0) 3.5.dp.toPx() else 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        currentValue?.toString() ?: "—",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (currentValue != null) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            unit.uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }

            // Secondary Telemetry Chips Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryMetricChip(
                    label = "MEMORY",
                    value = ramPercent?.let { "$it%" } ?: "—",
                    accentColor = Color(0xff98c5f2),
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricChip(
                    label = "CONNECTION",
                    value = when {
                        !connected -> "Offline"
                        remoteConnection -> "Tailscale"
                        else -> "LAN"
                    },
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }

            if (onInspect != null) {
                Surface(
                    onClick = onInspect,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Open System Monitor", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text("›", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricChip(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (isDark) Color(0xff141d2c) else Color(0xfff1f5f9),
        border = BorderStroke(1.dp, if (isDark) Color(0xff223046) else Color(0xffe2e8f0))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
    }
}

// Bento Action Card (Ref: Image 1 2x2 Grid)
@Composable
private fun BentoActionCard(
    title: String,
    subtitle: String,
    tag: String,
    glyph: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardBorderBrush),
        shadowElevation = if (isDark) 3.dp else 6.dp
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accentColor.copy(alpha = if (isDark) 0.15f else 0.12f)
                ) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        CompanionGlyph(glyph, accentColor)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDark) Color(0xff1d2738) else Color(0xfff1f5f9)
                ) {
                    Text(
                        tag,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

// Sensory Camera & Vision Stream to Desktop Card
@Composable
private fun SensoryStreamCard(
    connected: Boolean,
    pcName: String,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val skyBlue = Color(0xff38bdf8)
    val indicatorColor = if (connected) Color(0xff10b981) else Color(0xff64748b)

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardBorderBrush),
        shadowElevation = if (isDark) 3.dp else 6.dp
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = skyBlue.copy(alpha = if (isDark) 0.16f else 0.12f)
                    ) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CompanionGlyph("Camera", skyBlue)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Sensory Vision Stream",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (connected) "Linked to $pcName" else "Desktop offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDark) Color(0xff141e2e) else Color(0xfff1f5f9),
                    border = BorderStroke(0.5.dp, indicatorColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Canvas(Modifier.size(7.dp)) {
                            drawCircle(indicatorColor)
                        }
                        Text(
                            if (connected) "STREAM READY" else "OFFLINE",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = indicatorColor
                        )
                    }
                }
            }

            Text(
                "Capture photos or stream mobile optics directly to desktop multimodal models and autonomous vision tools.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Launch Studio Camera",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = skyBlue
                )
                CompanionGlyph("Next", skyBlue)
            }
        }
    }
}

// Universal AI Command Bar
@Composable
private fun AICommandBar(
    isDark: Boolean,
    onOpenChat: () -> Unit,
    onVoice: () -> Unit,
    onCamera: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onOpenChat),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardBorderBrush),
        shadowElevation = if (isDark) 3.dp else 5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CompanionGlyph("Search", MaterialTheme.colorScheme.primary)
                Text(
                    text = "Ask Oracle or type command…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    softWrap = false
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Voice quick launch
                val voiceInteraction = remember { MutableInteractionSource() }
                val isVoicePressed by voiceInteraction.collectIsPressedAsState()
                val voiceScale by animateFloatAsState(if (isVoicePressed) 0.92f else 1f, label = "v_scale")
                Surface(
                    onClick = onVoice,
                    interactionSource = voiceInteraction,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.scale(voiceScale)
                ) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        CompanionGlyph("Voice", MaterialTheme.colorScheme.primary)
                    }
                }
                // Camera quick launch
                val camInteraction = remember { MutableInteractionSource() }
                val isCamPressed by camInteraction.collectIsPressedAsState()
                val camScale by animateFloatAsState(if (isCamPressed) 0.92f else 1f, label = "c_scale")
                Surface(
                    onClick = onCamera,
                    interactionSource = camInteraction,
                    shape = CircleShape,
                    color = (if (isDark) Color(0xfff59e0b) else Color(0xffea580c)).copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, (if (isDark) Color(0xfff59e0b) else Color(0xffea580c)).copy(alpha = 0.35f)),
                    modifier = Modifier.scale(camScale)
                ) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        CompanionGlyph("Camera", if (isDark) Color(0xfff59e0b) else Color(0xffea580c))
                    }
                }
            }
        }
    }
}

// Smart Context Glance Card
@Composable
private fun SmartGlanceCard(
    connected: Boolean,
    pcName: String,
    remoteConnection: Boolean,
    cpuPercent: Int?,
    ramPercent: Int?,
    onTasksClick: () -> Unit,
    onStatusClick: () -> Unit,
    isDark: Boolean
) {
    val metrics = listOfNotNull(
        cpuPercent?.let { "CPU $it%" },
        ramPercent?.let { "Memory $it%" }
    )
    val emerald = if (isDark) Color(0xff10b981) else Color(0xff059669)
    val amber = if (isDark) Color(0xfff59e0b) else Color(0xffea580c)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardBorderBrush),
        shadowElevation = if (isDark) 2.dp else 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusRadarDot(connected)
                    Text(
                        if (connected) "PC CONNECTED" else "HOST OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = if (connected) emerald else MaterialTheme.colorScheme.error
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = (if (connected) emerald else amber).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, (if (connected) emerald else amber).copy(alpha = 0.3f))
                ) {
                    Text(
                        if (connected) (if (remoteConnection) "Tailscale Mesh" else "Local network") else "Standby",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (connected) emerald else amber
                    )
                }
            }

            Text(
                text = if (connected) {
                    metrics.joinToString(" · ").ifEmpty { "PC telemetry is unavailable." }
                } else {
                    "Check that Oracle Central is running on $pcName."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = onTasksClick,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        CompanionGlyph("Tasks", MaterialTheme.colorScheme.primary)
                        Text(
                            "Tasks & Timers",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Surface(
                    onClick = onStatusClick,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        CompanionGlyph("Chat", MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Ask Status",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// 1-Tap PC Quick Controls Deck
@Composable
private fun PcQuickControlsDeck(
    connected: Boolean,
    onExecuteAction: (String, String) -> Unit,
    onOpenControlPane: () -> Unit,
    isDark: Boolean
) {
    val skyBlue = if (isDark) Color(0xff98c5f2) else Color(0xff1d4ed8)
    val amber = if (isDark) Color(0xfff59e0b) else Color(0xffea580c)
    val purple = Color(0xffa855f7)
    val emerald = if (isDark) Color(0xff10b981) else Color(0xff059669)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Quick PC Controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                onClick = onOpenControlPane,
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "All controls",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = skyBlue
                    )
                    CompanionGlyph("Next", skyBlue)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Lock PC
            QuickControlPill(
                glyph = "Lock",
                title = "Lock PC",
                accentColor = amber,
                enabled = connected,
                onClick = { onExecuteAction("lock", "Lock Workstation") }
            )
            // 2. Play / Pause
            QuickControlPill(
                glyph = "PlayPause",
                title = "Play / Pause",
                accentColor = emerald,
                enabled = connected,
                onClick = { onExecuteAction("play_pause", "Media Play / Pause") }
            )
            // 3. Mute
            QuickControlPill(
                glyph = "Mute",
                title = "Mute Audio",
                accentColor = Color(0xfff43f5e),
                enabled = connected,
                onClick = { onExecuteAction("mute", "Toggle Mute") }
            )
            // 4. Sleep PC
            QuickControlPill(
                glyph = "Sleep",
                title = "Sleep PC",
                accentColor = purple,
                enabled = connected,
                onClick = { onExecuteAction("sleep", "Sleep Host") }
            )
            // 5. VS Code
            QuickControlPill(
                glyph = "Code",
                title = "VS Code",
                accentColor = skyBlue,
                enabled = connected,
                onClick = { onExecuteAction("vscode", "Open VS Code") }
            )
            // 6. Browser
            QuickControlPill(
                glyph = "Browser",
                title = "Browser",
                accentColor = Color(0xff06b6d4),
                enabled = connected,
                onClick = { onExecuteAction("browser", "Open Browser") }
            )
        }
    }
}

@Composable
private fun QuickControlPill(
    glyph: String,
    title: String,
    accentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (enabled) accentColor.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (isDark) 2.dp else 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = if (isDark) 0.18f else 0.12f)
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    CompanionGlyph(glyph, if (enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Main Companion Home Screen matching professional 3-zone design plan
@Composable
internal fun CompanionHome(
    pc: String,
    connected: Boolean,
    error: String,
    voiceEnabled: Boolean,
    voiceStatus: String?,
    onTalk: () -> Unit,
    onDevice: () -> Unit,
    onNavigate: (HomeTab) -> Unit,
    health: org.json.JSONObject? = null,
    remoteConnection: Boolean = false,
    onQuickPrompt: ((String) -> Unit)? = null,
    isDark: Boolean = false,
    onToggleTheme: () -> Unit = {},
    onPcAction: ((String, String) -> Unit)? = null,
    oracleRunning: Boolean = true
) {
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 0..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    var dialMode by remember { mutableStateOf("CPU") }
    val cpu = health?.optDouble("cpuPercent")?.takeIf { connected && it in 0.0..100.0 }?.toInt()
    val ram = health?.optDouble("ramPercent")?.takeIf { connected && it in 0.0..100.0 }?.toInt()
    val displayName = pc.ifBlank { "Jarvis-MarkI" }
    val colors = MaterialTheme.colorScheme
    val emerald = if (isDark) Color(0xff10b981) else Color(0xff059669)
    val amber = if (isDark) Color(0xfff59e0b) else Color(0xffea580c)
    val skyBlue = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val orbSize = 136.dp

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ZONE 1: TOP BAR & NEURAL COMMAND HERO
            // 1. Top Bar Header: Brand wordmark, Live Clock Capsule, Theme Toggle & Avatar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        onClick = onDevice,
                        shape = CircleShape,
                        color = colors.surface,
                        border = BorderStroke(2.dp, Brush.sweepGradient(listOf(colors.primary, amber, colors.primary))),
                        shadowElevation = if (isDark) 4.dp else 6.dp,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.avatar_profile),
                            contentDescription = "User profile",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "$greeting,",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = colors.onSurface
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Live Clock & Status Capsule
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, CardBorderBrush),
                        shadowElevation = if (isDark) 2.dp else 4.dp,
                        onClick = onDevice
                    ) {
                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Calendar.getInstance().time)
                        Row(
                            Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StatusRadarDot(connected)
                            Text(
                                timeStr,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                        }
                    }

                    // Theme Toggle Button
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, CardBorderBrush),
                        shadowElevation = if (isDark) 2.dp else 4.dp,
                        onClick = onToggleTheme
                    ) {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            CompanionGlyph(if (isDark) "Sun" else "Moon", colors.primary)
                        }
                    }
                }
            }

            // 2. AI Command Bar: Search / Ask Oracle (No cut-off, clean placeholder)
            AICommandBar(
                isDark = isDark,
                onOpenChat = { onNavigate(HomeTab.CHAT) },
                onVoice = onTalk,
                onCamera = { onNavigate(HomeTab.VISION) }
            )

            // 3. Hero Neural Hub Bento Card (Orb + Live Status + Animated Talk Pill)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, CardBorderBrush),
                shadowElevation = if (isDark) 4.dp else 8.dp
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusRadarDot(connected && oracleRunning)
                                Text(
                                    if (!connected) "DISCONNECTED"
                                    else if (oracleRunning) "ORACLE ACTIVE"
                                    else "ORACLE STANDBY",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp,
                                        fontSize = 12.sp
                                    ),
                                    color = if (!connected) colors.onSurfaceVariant else if (oracleRunning) emerald else amber
                                )
                            }
                            Text(
                                if (connected) (if (remoteConnection) "Tailscale Mesh · Encrypted" else "Local LAN · 0.4ms Latency") else "Pair PC to connect",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.primaryContainer.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
                            onClick = onDevice
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CompanionGlyph("Control", colors.primary)
                                Text("PC", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.primary)
                            }
                        }
                    }

                    // 3D Holographic Globe
                    Box(Modifier.size(orbSize), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        colors.primary.copy(alpha = 0.2f),
                                        colors.primary.copy(alpha = 0.05f),
                                        Color.Transparent
                                    )
                                )
                            )
                        }
                        OracleOrb(
                            Modifier.size(orbSize),
                            isListening = voiceStatus != null,
                            onClick = if (voiceEnabled && voiceStatus == null) onTalk else null
                        )
                    }

                    // Illuminated Talk Pill with Spring Click
                    val talkInteraction = remember { MutableInteractionSource() }
                    val isTalkPressed by talkInteraction.collectIsPressedAsState()
                    val talkScale by animateFloatAsState(if (isTalkPressed) 0.96f else 1f, label = "talk_scale")
                    Surface(
                        onClick = onTalk,
                        enabled = voiceEnabled && voiceStatus == null,
                        interactionSource = talkInteraction,
                        shape = RoundedCornerShape(22.dp),
                        color = if (voiceStatus != null) amber.copy(alpha = 0.15f) else colors.primaryContainer,
                        border = BorderStroke(1.dp, if (voiceStatus != null) amber else colors.primary.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .scale(talkScale)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompanionGlyph("Voice", if (voiceStatus != null) amber else colors.primary)
                            Text(
                                voiceStatus ?: "Tap to talk with Oracle",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (voiceStatus != null) amber else colors.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            if (connected && !oracleRunning) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xff151822),
                    border = BorderStroke(1.dp, amber.copy(alpha = 0.5f)),
                    shadowElevation = if (isDark) 4.dp else 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusRadarDot(false)
                                Text(
                                    "ORACLE CENTRAL OFFLINE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = amber,
                                    letterSpacing = 1.2.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = amber.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    if (remoteConnection) "Tailscale Linked" else "LAN Linked",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = amber,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Text(
                            "$displayName is reachable over the network, but Oracle Central AI services are currently switched off to conserve host power.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xff94a3b8)
                        )

                        Button(
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xff7c3aed),
                                contentColor = Color.White
                            ),
                            onClick = {
                                onPcAction?.invoke("system.oracle_start", "Start Oracle System")
                            }
                        ) {
                            Text(
                                "▶  ACTIVATE ORACLE SYSTEM",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            // ZONE 2: UNIFIED HOST TELEMETRY & 1-TAP CONTROL DECK
            // 4. 1-Tap PC Quick Controls Deck
            PcQuickControlsDeck(
                connected = connected,
                onExecuteAction = { action, label ->
                    onPcAction?.invoke(action, label)
                },
                onOpenControlPane = { onNavigate(HomeTab.DEVICE_CONTROLS) },
                isDark = isDark
            )

            // 5. Host Processor / Memory / System Radial Dial Gauge (No wrap bug!)
            RadialDialGauge(
                title = when (dialMode) {
                    "CPU" -> "Host Processor"
                    "RAM" -> "Memory Allocation"
                    else -> "System Telemetry"
                },
                currentValue = when (dialMode) {
                    "CPU" -> cpu
                    "RAM" -> ram
                    else -> if (cpu != null && ram != null) ((cpu + ram) / 2) else null
                },
                unit = "%",
                subtitle = if (connected) "$displayName · ${if (remoteConnection) "Tailscale Network" else "Local Network"}" else "Connect to view telemetry",
                activeColor = when (dialMode) {
                    "CPU" -> amber
                    "RAM" -> skyBlue
                    else -> emerald
                },
                selectedMode = dialMode,
                onModeChange = { dialMode = it },
                ramPercent = ram,
                remoteConnection = remoteConnection,
                connected = connected,
                onInspect = { onNavigate(HomeTab.MONITOR) }
            )

            // ZONE 3: WORKSPACES & TOOLS MATRIX
            Text(
                "Workspaces & Tools",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoActionCard(
                    title = "System Monitor",
                    subtitle = "CPU, GPU & Sensors",
                    tag = "Diagnostics",
                    glyph = "Telemetry",
                    accentColor = skyBlue,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(HomeTab.MONITOR) }
                )
                BentoActionCard(
                    title = "Device Controls",
                    subtitle = "Power & Media",
                    tag = "Remote",
                    glyph = "Control",
                    accentColor = amber,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(HomeTab.DEVICE_CONTROLS) }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoActionCard(
                    title = "VS Code",
                    subtitle = "Dev Workspace",
                    tag = "Desktop Tools",
                    glyph = "Code",
                    accentColor = Color(0xff3b82f6),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(HomeTab.CONTROL) }
                )
                BentoActionCard(
                    title = "Vision AI",
                    subtitle = "Optical Studio",
                    tag = "Camera HUD",
                    glyph = "Camera",
                    accentColor = amber,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(HomeTab.VISION) }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoActionCard(
                    title = "Assistant",
                    subtitle = "Ask Oracle",
                    tag = "Multimodal",
                    glyph = "Chat",
                    accentColor = emerald,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(HomeTab.CHAT) }
                )
                BentoActionCard(
                    title = "Records & Notes",
                    subtitle = "Timers & Sync",
                    tag = "Tasks",
                    glyph = "Tasks",
                    accentColor = Color(0xffa855f7),
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(HomeTab.RECORDS) }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoActionCard(
                    title = "Atlas Maps",
                    subtitle = "Routes & Parking",
                    tag = "GPS",
                    glyph = "Atlas",
                    accentColor = skyBlue,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(HomeTab.ATLAS) }
                )
                BentoActionCard(
                    title = "People & Consent",
                    subtitle = "Face Registry",
                    tag = "Identity",
                    glyph = "People",
                    accentColor = emerald,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(HomeTab.PEOPLE) }
                )
            }

            // Sensory Camera & Vision Stream to Desktop Card
            SensoryStreamCard(
                connected = connected,
                pcName = displayName,
                isDark = isDark,
                onClick = { onNavigate(HomeTab.VISION) }
            )

            // Quick Prompt Suggestions (when connected)
            if (connected) {
                Text("Suggested Queries", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Check system status" to HomeTab.CHAT,
                        "View reminders" to HomeTab.RECORDS,
                        "Open VS Code" to HomeTab.CONTROL,
                        "Analyze photo" to HomeTab.VISION,
                        "Recent actions" to HomeTab.ACTIVITY
                    ).forEach { (prompt, targetTab) ->
                        Surface(
                            onClick = {
                                if (onQuickPrompt != null && targetTab == HomeTab.CHAT) {
                                    onQuickPrompt(prompt)
                                } else {
                                    onNavigate(targetTab)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, CardBorderBrush),
                            shadowElevation = if (isDark) 1.dp else 3.dp
                        ) {
                            Text(
                                prompt,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// 2x2 Quick Action Tile matching the reference design
@Composable
private fun QuickActionTile(
    title: String,
    subtitle: String,
    glyph: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tile_press"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardBorderBrush),
        shadowElevation = if (isDark) 3.dp else 4.dp,
        modifier = modifier
            .height(118.dp)
            .scale(scale)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.15f else 0.10f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CompanionGlyph(glyph, MaterialTheme.colorScheme.primary)
                    }
                }
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CompanionGlyph("ChevronRight", MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Connected Device Sheet
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectedDeviceSheet(
    pc: String, connected: Boolean, oracleOnline: Boolean,
    remoteConnection: Boolean, model: String?, health: org.json.JSONObject?,
    onDismiss: () -> Unit, onControl: () -> Unit, onManage: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val greenColor = if (isDark) Color(0xff10b981) else Color(0xff059669)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CompanionGlyph("Control", MaterialTheme.colorScheme.primary) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(pc, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(if (connected) "● Online" else "Offline", color = if (connected) greenColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                }
                FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                    Text("×", fontSize = 22.sp, modifier = Modifier.semantics {
                        contentDescription = "Close device details"
                    })
                }
            }
            Column {
                DeviceDetail("Connection", when {
                    !connected -> "Disconnected"
                    remoteConnection -> "Remote via Tailscale"
                    else -> "Local network"
                })
                DeviceDetail("Pairing", "Secure")
                DeviceDetail("Oracle Central", if (oracleOnline) "Online" else "Unavailable")
                DeviceDetail("Local model", model ?: "Unavailable")
                val cpu = health?.optDouble("cpuPercent")?.takeIf { it.isFinite() }
                val ram = health?.optDouble("ramPercent")?.takeIf { it.isFinite() }
                DeviceDetail("CPU Load", cpu?.let { "${it.toInt()}%" } ?: "Unavailable")
                DeviceDetail("RAM Allocation", ram?.let { "${it.toInt()}%" } ?: "Unavailable")
                health?.optLong("sampledAt")?.takeIf { it > 0 }?.let { seconds ->
                    val formatted = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
                        .format(java.util.Date(seconds * 1000))
                    DeviceDetail("Last sampled", formatted)
                }
            }
            Button(onClick = onControl, enabled = oracleOnline, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Open remote control")
            }
            TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Manage connection")
            }
        }
    }
}

@Composable
private fun DeviceDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// Interactive 3D Holographic Globe
@Composable
internal fun OracleOrb(
    modifier: Modifier,
    isListening: Boolean = false,
    isThinking: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isDark = LocalDarkTheme.current
    var dragYaw by remember { mutableFloatStateOf(0f) }
    var dragPitch by remember { mutableFloatStateOf(0f) }
    var gyroYaw by remember { mutableFloatStateOf(0f) }
    var gyroPitch by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val rMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rMatrix, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rMatrix, orientation)
                    val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
                    val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                    gyroYaw = (rollDeg * 0.45f).coerceIn(-28f, 28f)
                    gyroPitch = (pitchDeg * 0.35f).coerceIn(-22f, 22f)
                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    gyroYaw = (-ax * 2.8f).coerceIn(-28f, 28f)
                    gyroPitch = ((ay - 5f) * 2.2f).coerceIn(-22f, 22f)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager?.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    val smoothGyroYaw by animateFloatAsState(
        targetValue = gyroYaw,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "gyro_yaw"
    )
    val smoothGyroPitch by animateFloatAsState(
        targetValue = gyroPitch,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "gyro_pitch"
    )

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "orb_press"
    )

    val transition = rememberInfiniteTransition(label = "orb")
    val autoRotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 4000 else if (isThinking) 2800 else 24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auto_rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = if (isListening) 0.94f else 0.98f,
        targetValue = if (isListening) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 700 else if (isThinking) 550 else 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )
    val waveRipple by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 1200 else 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_wave"
    )
    val satelliteAngle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 2200 else if (isThinking) 1600 else 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "satellite"
    )
    val shimmerTime by transition.animateFloat(
        initialValue = 0f, targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val mesh = SharedSphereMesh

    Box(
        modifier = modifier
            .scale(pressScale)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isPressed = true },
                    onDragEnd = { isPressed = false },
                    onDragCancel = { isPressed = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragYaw += dragAmount.x * 0.45f
                        dragPitch = (dragPitch - dragAmount.y * 0.35f).coerceIn(-40f, 40f)
                    }
                )
            }
            .clickable(
                enabled = onClick != null,
                role = Role.Button,
                onClick = { onClick?.invoke() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val baseRadius = size.minDimension * 0.34f
            val radius = baseRadius * pulse
            val glowColor = if (isListening) {
                if (isDark) Color(0xff10b981) else Color(0xff059669)
            } else {
                if (isDark) Color(0xff98c5f2) else Color(0xff2563eb)
            }
            val centerOffset = center

            val totalYawRad = Math.toRadians((autoRotation + dragYaw + smoothGyroYaw).toDouble()).toFloat()
            val totalPitchRad = Math.toRadians((-18.0 + dragPitch + smoothGyroPitch).toDouble()).toFloat()
            val cosYaw = cos(totalYawRad)
            val sinYaw = sin(totalYawRad)
            val cosPitch = cos(totalPitchRad)
            val sinPitch = sin(totalPitchRad)

            // 1. Audio wave halos when listening
            if (isListening) {
                val haloRadius = radius * (1.28f + waveRipple * 0.55f)
                val haloAlpha = (1f - waveRipple).coerceIn(0f, 1f) * 0.5f
                drawCircle(
                    color = glowColor.copy(alpha = haloAlpha),
                    radius = haloRadius,
                    style = Stroke(1.5.dp.toPx())
                )
                val haloRadius2 = radius * (1.1f + ((waveRipple + 0.5f) % 1f) * 0.55f)
                val haloAlpha2 = (1f - ((waveRipple + 0.5f) % 1f)).coerceIn(0f, 1f) * 0.35f
                drawCircle(
                    color = glowColor.copy(alpha = haloAlpha2),
                    radius = haloRadius2,
                    style = Stroke(1.dp.toPx())
                )
            }

            // 2. Ambient outer atmosphere aura
            drawCircle(
                Brush.radialGradient(
                    listOf(glowColor.copy(alpha = if (isListening) 0.35f else 0.18f), Color.Transparent),
                    center = centerOffset, radius = radius * 1.55f
                ),
                radius * 1.55f
            )

            // 3 & 4. Back hemisphere particles (Z < 0) without heap allocation
            val count = mesh.total
            val pxBase = 0.55.dp.toPx()
            for (i in 0 until count) {
                val x0 = mesh.x[i]
                val y0 = mesh.y[i]
                val z0 = mesh.z[i]
                val z1 = x0 * sinYaw + z0 * cosYaw
                val z2 = y0 * sinPitch + z1 * cosPitch
                if (z2 < 0f) {
                    val x1 = x0 * cosYaw - z0 * sinYaw
                    val y2 = y0 * cosPitch - z1 * sinPitch
                    val screenX = centerOffset.x + x1 * radius
                    val screenY = centerOffset.y + y2 * radius
                    val twinkle = 0.8f + 0.25f * sin(shimmerTime * 2f + i * 0.6f)
                    val depthFactor = (1f + z2).coerceIn(0.1f, 0.7f)
                    val alpha = if (isDark) 0.12f * depthFactor * twinkle else 0.16f * depthFactor * twinkle
                    drawCircle(
                        color = glowColor.copy(alpha = alpha),
                        radius = pxBase * depthFactor,
                        center = Offset(screenX, screenY)
                    )
                }
            }

            // 5. Back orbital ring segment
            rotate(-22f + (dragPitch + smoothGyroPitch) * 0.2f) {
                val orbit = Size(radius * 2.85f, radius * 0.76f)
                drawOval(
                    glowColor.copy(alpha = if (isDark) 0.25f else 0.20f),
                    centerOffset - Offset(orbit.width / 2, orbit.height / 2),
                    orbit,
                    style = Stroke(0.8.dp.toPx())
                )
            }

            // 6. Deep glass sphere core (volumetric illumination)
            val hotspotOffset = Offset(
                centerOffset.x - radius * 0.28f + sin(shimmerTime) * 8.dp.toPx(),
                centerOffset.y - radius * 0.32f + cos(shimmerTime) * 6.dp.toPx()
            )
            val coreGradients = if (isDark) {
                listOf(
                    Color(0xff416292),
                    Color(0xff182c47),
                    Color(0xff09121d),
                    Color(0xff060a10)
                )
            } else {
                listOf(
                    Color(0xffffffff),
                    Color(0xffe0edff),
                    Color(0xffbfdbfe),
                    Color(0xff93c5fd)
                )
            }
            drawCircle(
                Brush.radialGradient(
                    coreGradients,
                    center = hotspotOffset,
                    radius = radius * 1.35f
                ),
                radius
            )

            // 7. Holographic 3D Latitude Wireframe Grid
            listOf(0.0f, 0.52f, -0.52f).forEach { latY ->
                val latRadius = sqrt((1f - latY * latY).coerceAtLeast(0f)) * radius
                val latCenterY = centerOffset.y + latY * radius * cosPitch
                val ellipseHeight = latRadius * sinPitch.absoluteValue.coerceAtLeast(0.14f)
                drawOval(
                    color = glowColor.copy(alpha = if (latY == 0f) (if (isDark) 0.22f else 0.26f) else (if (isDark) 0.13f else 0.15f)),
                    topLeft = Offset(centerOffset.x - latRadius, latCenterY - ellipseHeight / 2),
                    size = Size(latRadius * 2, ellipseHeight),
                    style = Stroke(0.7.dp.toPx())
                )
            }

            // 8. Front hemisphere particles (Z >= 0) without heap allocation
            val ptBase = 0.75.dp.toPx()
            val ptZScale = 1.3.dp.toPx()
            for (i in 0 until count) {
                val x0 = mesh.x[i]
                val y0 = mesh.y[i]
                val z0 = mesh.z[i]
                val z1 = x0 * sinYaw + z0 * cosYaw
                val z2 = y0 * sinPitch + z1 * cosPitch
                if (z2 >= 0f) {
                    val x1 = x0 * cosYaw - z0 * sinYaw
                    val y2 = y0 * cosPitch - z1 * sinPitch
                    val screenX = centerOffset.x + x1 * radius
                    val screenY = centerOffset.y + y2 * radius
                    val twinkle = 0.8f + 0.25f * sin(shimmerTime * 2f + i * 0.6f)
                    val alpha = (0.35f + 0.65f * z2) * twinkle
                    val ptRadius = ptBase + z2 * ptZScale
                    if (z2 > 0.65f) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.7f * z2 * twinkle),
                            radius = ptRadius * 0.45f,
                            center = Offset(screenX, screenY)
                        )
                    }
                    drawCircle(
                        color = glowColor.copy(alpha = alpha.coerceIn(0f, 1f)),
                        radius = ptRadius,
                        center = Offset(screenX, screenY)
                    )
                }
            }

            // 9. Luminous Fresnel Atmosphere Rim Ring
            drawCircle(
                Brush.radialGradient(
                    listOf(
                        Color.Transparent,
                        glowColor.copy(alpha = 0.05f),
                        glowColor.copy(alpha = if (isListening) 0.85f else (if (isDark) 0.65f else 0.55f))
                    ),
                    center = centerOffset,
                    radius = radius * 1.02f
                ),
                radius * 1.02f,
                style = Stroke(1.2.dp.toPx())
            )

            // 10. Front Primary Orbit Ring with Active Satellite Beacon
            rotate(-22f + (dragPitch + smoothGyroPitch) * 0.2f) {
                val orbit = Size(radius * 2.85f, radius * 0.76f)
                drawOval(
                    glowColor.copy(alpha = if (isListening) 0.95f else (if (isDark) 0.75f else 0.85f)),
                    centerOffset - Offset(orbit.width / 2, orbit.height / 2),
                    orbit,
                    style = Stroke(1.2.dp.toPx())
                )

                val satRad = Math.toRadians(satelliteAngle.toDouble())
                val satX = centerOffset.x + (orbit.width / 2) * cos(satRad).toFloat()
                val satY = centerOffset.y + (orbit.height / 2) * sin(satRad).toFloat()

                drawCircle(
                    color = glowColor.copy(alpha = 0.35f),
                    radius = 5.dp.toPx(),
                    center = Offset(satX, satY)
                )
                drawCircle(
                    color = if (isDark) Color.White else Color(0xff1d4ed8),
                    radius = 2.dp.toPx(),
                    center = Offset(satX, satY)
                )
            }

            // 11. Counter-inclined Gyroscopic Ring
            rotate(52f - (dragYaw + smoothGyroYaw) * 0.15f) {
                val orbit2 = Size(radius * 2.45f, radius * 1.55f)
                drawOval(
                    glowColor.copy(alpha = if (isDark) 0.25f else 0.30f),
                    centerOffset - Offset(orbit2.width / 2, orbit2.height / 2),
                    orbit2,
                    style = Stroke(0.8.dp.toPx())
                )
            }
        }
    }
}

// Companion Vector Glyphs
@Composable
internal fun CompanionGlyph(name: String, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val unit = size.width / 24
        val stroke = Stroke(width = unit * 1.7f, cap = StrokeCap.Round)
        fun line(vararg points: Pair<Int, Int>) {
            val path = Path()
            path.moveTo(points[0].first * unit, points[0].second * unit)
            points.drop(1).forEach { (x, y) -> path.lineTo(x * unit, y * unit) }
            drawPath(path, color, style = stroke)
        }
        fun box(x: Int, y: Int, width: Int, height: Int) {
            drawRoundRect(color, Offset(x * unit, y * unit), Size(width * unit, height * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 1.6f), style = stroke)
        }
        when (name) {
            "Home" -> {
                line(3 to 11, 12 to 3, 21 to 11, 20 to 11, 20 to 21, 4 to 21, 4 to 11)
                line(9 to 21, 9 to 14, 15 to 14, 15 to 21)
            }
            "Assistant", "Chat" -> {
                val path = Path().apply {
                    moveTo(5f * unit, 4f * unit)
                    lineTo(19f * unit, 4f * unit)
                    cubicTo(21f * unit, 4f * unit, 21f * unit, 14f * unit, 19f * unit, 14f * unit)
                    lineTo(9f * unit, 14f * unit)
                    lineTo(5f * unit, 18f * unit)
                    lineTo(5f * unit, 14f * unit)
                    cubicTo(3f * unit, 14f * unit, 3f * unit, 4f * unit, 5f * unit, 4f * unit)
                    close()
                }
                drawPath(path, color, style = stroke)
            }
            "Devices" -> {
                drawRoundRect(color, Offset(7f * unit, 3f * unit), Size(14f * unit, 12f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 2f), style = stroke)
                drawRoundRect(color, Offset(3f * unit, 8f * unit), Size(13f * unit, 13f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 2f), style = stroke)
            }
            "Settings" -> {
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawCircle(color, radius = unit * 3.8f, center = Offset(cx, cy), style = stroke)
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val r1 = unit * 6.2f
                    val r2 = unit * 9.2f
                    drawLine(color,
                        Offset(cx + r1 * cos(angle).toFloat(), cy + r1 * sin(angle).toFloat()),
                        Offset(cx + r2 * cos(angle).toFloat(), cy + r2 * sin(angle).toFloat()),
                        strokeWidth = unit * 2.2f, cap = StrokeCap.Round)
                }
            }
            "RemoteControl", "Desktop" -> {
                drawRoundRect(color, Offset(3f * unit, 4f * unit), Size(18f * unit, 12f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 2f), style = stroke)
                line(12 to 16, 12 to 20)
                line(8 to 20, 16 to 20)
            }
            "LiveVision", "Viewfinder" -> {
                line(4 to 9, 4 to 5, 8 to 5)
                line(16 to 5, 20 to 5, 20 to 9)
                line(4 to 15, 4 to 19, 8 to 19)
                line(16 to 19, 20 to 19, 20 to 15)
                drawCircle(color, radius = unit * 1.5f, center = Offset(size.width / 2f, size.height / 2f))
            }
            "AskOracle", "Sparkle" -> {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val path = Path().apply {
                    moveTo(cx, cy - unit * 9f)
                    cubicTo(cx, cy - unit * 3.5f, cx + unit * 3.5f, cy, cx + unit * 9f, cy)
                    cubicTo(cx + unit * 3.5f, cy, cx, cy + unit * 3.5f, cx, cy + unit * 9f)
                    cubicTo(cx, cy + unit * 3.5f, cx - unit * 3.5f, cy, cx - unit * 9f, cy)
                    cubicTo(cx - unit * 3.5f, cy, cx, cy - unit * 3.5f, cx, cy - unit * 9f)
                    close()
                }
                drawPath(path, color, style = stroke)
            }
            "Maps", "Location" -> {
                val cx = size.width / 2f
                val path = Path().apply {
                    moveTo(cx, unit * 21f)
                    cubicTo(cx - unit * 6.5f, unit * 14f, cx - unit * 7f, unit * 7.5f, cx, unit * 3.5f)
                    cubicTo(cx + unit * 7f, unit * 7.5f, cx + unit * 6.5f, unit * 14f, cx, unit * 21f)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawCircle(color, radius = unit * 2.5f, center = Offset(cx, unit * 8.5f), style = stroke)
            }
            "CPU" -> {
                drawRoundRect(color, Offset(6f * unit, 6f * unit), Size(12f * unit, 12f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 1.5f), style = stroke)
                drawRoundRect(color, Offset(9f * unit, 9f * unit), Size(6f * unit, 6f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.8f), style = stroke)
                for (x in listOf(8, 12, 16)) {
                    line(x to 6, x to 3)
                    line(x to 18, x to 21)
                }
                for (y in listOf(8, 12, 16)) {
                    line(6 to y, 3 to y)
                    line(18 to y, 21 to y)
                }
            }
            "Memory", "RAM" -> {
                drawRoundRect(color, Offset(3f * unit, 8f * unit), Size(18f * unit, 8f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 1.2f), style = stroke)
                for (x in listOf(5, 9, 13, 17)) {
                    drawRect(color, Offset(x * unit, 9.5f * unit), Size(2f * unit, 3f * unit))
                }
                for (x in listOf(4, 6, 8, 14, 16, 18)) {
                    line(x to 16, x to 18)
                }
            }
            "Windows" -> {
                val s = unit * 4.2f
                val gap = unit * 1.6f
                val left = unit * 4.5f
                val top = unit * 4.5f
                drawRect(color, Offset(left, top), Size(s, s))
                drawRect(color, Offset(left + s + gap, top), Size(s, s))
                drawRect(color, Offset(left, top + s + gap), Size(s, s))
                drawRect(color, Offset(left + s + gap, top + s + gap), Size(s, s))
            }
            "LiveStatus", "Broadcast" -> {
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawCircle(color, radius = unit * 1.6f, center = Offset(cx, cy))
                drawArc(color, startAngle = 135f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(cx - unit * 4.5f, cy - unit * 4.5f), size = Size(unit * 9f, unit * 9f), style = stroke)
                drawArc(color, startAngle = 315f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(cx - unit * 4.5f, cy - unit * 4.5f), size = Size(unit * 9f, unit * 9f), style = stroke)
                drawArc(color, startAngle = 135f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(cx - unit * 7.5f, cy - unit * 7.5f), size = Size(unit * 15f, unit * 15f), style = stroke)
                drawArc(color, startAngle = 315f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(cx - unit * 7.5f, cy - unit * 7.5f), size = Size(unit * 15f, unit * 15f), style = stroke)
            }
            "DotsMenu" -> {
                val cy = size.height / 2f
                drawCircle(color, radius = unit * 1.4f, center = Offset(unit * 6f, cy))
                drawCircle(color, radius = unit * 1.4f, center = Offset(unit * 12f, cy))
                drawCircle(color, radius = unit * 1.4f, center = Offset(unit * 18f, cy))
            }
            "ChevronRight" -> {
                line(10 to 8, 15 to 12, 10 to 16)
            }
            "Camera", "Vision" -> {
                box(3, 7, 18, 13)
                line(8 to 7, 9 to 4, 15 to 4, 16 to 7)
                drawCircle(color, radius = unit * 3.6f, center = Offset(size.width / 2f, size.height * 0.58f), style = stroke)
                drawCircle(color, radius = unit * 1.0f, center = Offset(unit * 17.5f, unit * 9.5f))
            }
            "Hub", "Grid", "Apps" -> {
                val s = unit * 4.2f
                val gap = unit * 2.2f
                val left = unit * 4.5f
                val top = unit * 4.5f
                drawRoundRect(color, Offset(left, top), Size(s, s), androidx.compose.ui.geometry.CornerRadius(unit * 1.2f), style = stroke)
                drawRoundRect(color, Offset(left + s + gap, top), Size(s, s), androidx.compose.ui.geometry.CornerRadius(unit * 1.2f), style = stroke)
                drawRoundRect(color, Offset(left, top + s + gap), Size(s, s), androidx.compose.ui.geometry.CornerRadius(unit * 1.2f), style = stroke)
                drawRoundRect(color, Offset(left + s + gap, top + s + gap), Size(s, s), androidx.compose.ui.geometry.CornerRadius(unit * 1.2f), style = stroke)
            }
            "Control" -> { box(3, 4, 18, 13); line(12 to 17, 12 to 21); line(8 to 21, 16 to 21) }
            "Records", "Tasks" -> {
                box(5, 3, 14, 18)
                line(8 to 7, 16 to 7)
                line(8 to 11, 10 to 13, 15 to 9)
                line(8 to 16, 16 to 16)
            }
            "Atlas", "Map" -> {
                line(12 to 3, 19 to 21, 12 to 17, 5 to 21, 12 to 3)
            }
            "People", "Identity" -> {
                drawCircle(color, radius = unit * 3.8f, center = Offset(size.width / 2f, size.height * 0.32f), style = stroke)
                line(5 to 21, 5 to 17, 8 to 14, 16 to 14, 19 to 17, 19 to 21)
            }
            "Activity" -> { box(5, 3, 14, 18); line(8 to 8, 16 to 8); line(8 to 12, 16 to 12); line(8 to 16, 13 to 16) }
            "Voice" -> { box(9, 3, 6, 12); line(5 to 11, 5 to 14, 8 to 18, 16 to 18, 19 to 14, 19 to 11); line(12 to 18, 12 to 22); line(8 to 22, 16 to 22) }
            "Next" -> line(10 to 7, 15 to 12, 10 to 17)
            "Oracle" -> {
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawCircle(Color(0xffcbd5e1), radius = unit * 7.5f, center = Offset(cx, cy), style = Stroke(width = unit * 1.5f))
                rotate(-34f, pivot = Offset(cx, cy)) {
                    val ellipsePath = Path().apply {
                        val left = cx - unit * 10.7f
                        val top = cy - unit * 3.5f
                        val right = cx + unit * 10.7f
                        val bottom = cy + unit * 3.5f
                        addOval(androidx.compose.ui.geometry.Rect(left, top, right, bottom))
                    }
                    drawPath(ellipsePath, Color(0xff38bdf8), style = Stroke(width = unit * 1.8f))
                    drawCircle(Color(0x5500f3ff), radius = unit * 2.8f, center = Offset(cx + unit * 8.35f, cy))
                    drawCircle(Color(0xff00f3ff), radius = unit * 1.8f, center = Offset(cx + unit * 8.35f, cy))
                    drawCircle(Color.White, radius = unit * 0.9f, center = Offset(cx + unit * 8.35f, cy))
                }
            }
            "Sun" -> {
                drawCircle(color, radius = unit * 4.2f, style = Stroke(width = unit * 1.6f, cap = StrokeCap.Round))
                val r1 = unit * 6.8f
                val r2 = unit * 9.8f
                for (i in 0 until 8) {
                    val rad = Math.toRadians((i * 45).toDouble())
                    drawLine(
                        color,
                        Offset(center.x + r1 * cos(rad).toFloat(), center.y + r1 * sin(rad).toFloat()),
                        Offset(center.x + r2 * cos(rad).toFloat(), center.y + r2 * sin(rad).toFloat()),
                        strokeWidth = unit * 1.7f, cap = StrokeCap.Round
                    )
                }
            }
            "Search" -> {
                drawCircle(color, radius = unit * 5.2f, center = Offset(unit * 10f, unit * 10f), style = stroke)
                line(14 to 14, 20 to 20)
            }
            "Lock" -> {
                box(5, 9, 14, 11)
                val shackle = Path().apply {
                    moveTo(unit * 8f, unit * 9f)
                    lineTo(unit * 8f, unit * 6f)
                    cubicTo(unit * 8f, unit * 3f, unit * 16f, unit * 3f, unit * 16f, unit * 6f)
                    lineTo(unit * 16f, unit * 9f)
                }
                drawPath(shackle, color, style = stroke)
                drawCircle(color, radius = unit * 1.4f, center = Offset(unit * 12f, unit * 14.5f))
            }
            "PlayPause", "Play" -> {
                val tri = Path().apply {
                    moveTo(unit * 8f, unit * 6f)
                    lineTo(unit * 17f, unit * 12f)
                    lineTo(unit * 8f, unit * 18f)
                    close()
                }
                drawPath(tri, color, style = stroke)
            }
            "Mute" -> {
                val horn = Path().apply {
                    moveTo(unit * 4f, unit * 9f)
                    lineTo(unit * 8f, unit * 9f)
                    lineTo(unit * 13f, unit * 5f)
                    lineTo(unit * 13f, unit * 19f)
                    lineTo(unit * 8f, unit * 15f)
                    lineTo(unit * 4f, unit * 15f)
                    close()
                }
                drawPath(horn, color, style = stroke)
                line(16 to 9, 21 to 15)
                line(21 to 9, 16 to 15)
            }
            "Sleep" -> {
                line(6 to 6, 12 to 6, 6 to 12, 12 to 12)
                line(13 to 12, 19 to 12, 13 to 18, 19 to 18)
            }
            "Code" -> {
                line(8 to 7, 3 to 12, 8 to 17)
                line(16 to 7, 21 to 12, 16 to 17)
                line(13 to 6, 11 to 18)
            }
            "Browser", "Web" -> {
                drawCircle(color, radius = unit * 8f, center = Offset(unit * 12f, unit * 12f), style = stroke)
                line(4 to 12, 20 to 12)
                val el = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(unit * 8f, unit * 4f, unit * 16f, unit * 20f))
                }
                drawPath(el, color, style = stroke)
            }
            "Telemetry", "Monitor", "Pulse" -> {
                line(3 to 12, 7 to 12, 10 to 6, 14 to 18, 17 to 10, 19 to 12, 21 to 12)
            }
            "Moon" -> {
                val path = Path().apply {
                    moveTo(16.5f * unit, 4.5f * unit)
                    cubicTo(9.5f * unit, 5.5f * unit, 7f * unit, 13.5f * unit, 11.5f * unit, 19f * unit)
                    cubicTo(5.5f * unit, 16f * unit, 6f * unit, 8f * unit, 12.5f * unit, 3.5f * unit)
                    close()
                }
                drawPath(path, color, style = stroke)
            }
        }
    }
}

@Composable
internal fun CompanionPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, CardBorderBrush),
        shadowElevation = if (LocalDarkTheme.current) 2.dp else 4.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun CompanionPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CompanionPage {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
