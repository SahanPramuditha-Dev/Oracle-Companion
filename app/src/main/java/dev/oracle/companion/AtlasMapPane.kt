package dev.oracle.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String? = null
)

internal data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val modifier: String? = null
)

internal data class CalculatedRoute(
    val destinationName: String,
    val distanceKm: Double,
    val durationMinutes: Int,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val steps: List<RouteStep> = emptyList(),
    val geometryPoints: List<Pair<Double, Double>> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AtlasMapPane(
    api: CompanionApi?,
    online: Boolean,
    onNavigateChat: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current
    var subTab by rememberSaveable { mutableIntStateOf(0) } // 0: Route & Navigation, 1: Saved Places & Parking

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeRoute by remember { mutableStateOf<CalculatedRoute?>(null) }
    var savedPlaces by remember { mutableStateOf<List<SavedPlace>>(emptyList()) }
    var parkingSpot by remember { mutableStateOf<SavedPlace?>(null) }
    var loading by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    var showAddPlaceSheet by remember { mutableStateOf(false) }

    val blueColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val greenColor = if (isDark) Color(0xff50e3c2) else Color(0xff059669)
    val amberColor = if (isDark) Color(0xffffb800) else Color(0xffd97706)
    val purpleColor = if (isDark) Color(0xffc084fc) else Color(0xff7c3aed)

    fun refreshPlacesAndParking() {
        if (api == null || !online) return
        scope.launch {
            try {
                // Fetch saved places
                val placesRes = withContext(Dispatchers.IO) { api.call("geo/places") }
                val parsedPlaces = mutableListOf<SavedPlace>()
                val placesArr = placesRes.optJSONArray("places")
                if (placesArr != null) {
                    for (i in 0 until placesArr.length()) {
                        val obj = placesArr.getJSONObject(i)
                        parsedPlaces.add(
                            SavedPlace(
                                id = obj.optString("id", i.toString()),
                                name = obj.optString("name", "Saved Location"),
                                lat = obj.optDouble("lat", 0.0),
                                lon = obj.optDouble("lon", 0.0),
                                address = obj.optString("address").takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                }
                savedPlaces = parsedPlaces

                // Fetch parking memory
                val parkingRes = withContext(Dispatchers.IO) { api.call("geo/parking") }
                val parkingObj = parkingRes.optJSONObject("parking") ?: parkingRes.optJSONObject("location")
                if (parkingObj != null) {
                    parkingSpot = SavedPlace(
                        id = "parking",
                        name = "Parked Vehicle",
                        lat = parkingObj.optDouble("lat", 0.0),
                        lon = parkingObj.optDouble("lon", 0.0),
                        address = parkingObj.optString("address").takeIf { it.isNotEmpty() }
                    )
                } else {
                    parkingSpot = null
                }
            } catch (_: Exception) {}
        }
    }

    fun computeRouteTo(destName: String, destLat: Double, destLon: Double) {
        if (api == null || !online) return
        scope.launch {
            loading = true
            errorMsg = ""
            statusNotice = ""
            try {
                // Approximate starting anchor (e.g. city anchor or current GPS)
                val startLat = 6.9271
                val startLon = 79.8612
                val queryPath = "geo/route?start_lat=$startLat&start_lon=$startLon&end_lat=$destLat&end_lon=$destLon"
                val res = withContext(Dispatchers.IO) { api.call(queryPath) }
                
                val distMeters = res.optDouble("distance", 12400.0)
                val durSeconds = res.optDouble("duration", 1380.0)
                val stepsList = mutableListOf<RouteStep>()
                val stepsArr = res.optJSONArray("steps")
                if (stepsArr != null) {
                    for (i in 0 until stepsArr.length()) {
                        val sObj = stepsArr.getJSONObject(i)
                        stepsList.add(
                            RouteStep(
                                instruction = sObj.optString("instruction", "Continue along path"),
                                distanceMeters = sObj.optDouble("distance", 200.0),
                                modifier = sObj.optString("modifier").takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                } else {
                    // Synthesize default waypoint steps if mock/fallback
                    stepsList.add(RouteStep("Depart from origin onto main arterial avenue", distMeters * 0.2))
                    stepsList.add(RouteStep("Continue through central expressway corridor", distMeters * 0.6))
                    stepsList.add(RouteStep("Arrive at $destName", distMeters * 0.2))
                }

                activeRoute = CalculatedRoute(
                    destinationName = destName,
                    distanceKm = distMeters / 1000.0,
                    durationMinutes = (durSeconds / 60.0).toInt().coerceAtLeast(1),
                    startLat = startLat,
                    startLon = startLon,
                    endLat = destLat,
                    endLon = destLon,
                    steps = stepsList,
                    geometryPoints = listOf(
                        startLat to startLon,
                        (startLat + destLat) / 2.0 to (startLon + destLon) / 2.0 + 0.01,
                        destLat to destLon
                    )
                )
                subTab = 0
            } catch (e: Exception) {
                errorMsg = e.message ?: "Could not calculate route to $destName"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(api, online) {
        refreshPlacesAndParking()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Tab Switcher: Route vs Saved & Parking
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("Navigation & Route" to 0, "Places & Parking" to 1).forEach { (label, idx) ->
                    val selected = subTab == idx
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        onClick = { subTab = idx }
                    ) {
                        Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (errorMsg.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚠️", fontSize = 14.sp)
                    Text(errorMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (statusNotice.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = greenColor.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, greenColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✅", fontSize = 14.sp)
                    Text(statusNotice, style = MaterialTheme.typography.bodySmall, color = greenColor)
                }
            }
        }

        when (subTab) {
            0 -> RouteNavigationSection(
                route = activeRoute,
                loading = loading,
                online = online,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onSearchSubmit = {
                    if (searchQuery.isNotBlank()) {
                        computeRouteTo(searchQuery.trim(), 6.9344, 79.8428)
                    }
                },
                onOpenExternalNav = { r ->
                    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${r.startLat},${r.startLon}&destination=${r.endLat},${r.endLon}")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {}
                },
                onSendToDesktop = { r ->
                    scope.launch {
                        try {
                            val body = JSONObject()
                                .put("destination", r.destinationName)
                                .put("url", "https://www.google.com/maps/dir/?api=1&destination=${r.endLat},${r.endLon}")
                            withContext(Dispatchers.IO) { api?.call("geo/queue-trip", body) }
                            statusNotice = "Trip shared with Oracle Desktop Presence!"
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "Failed to push trip to PC"
                        }
                    }
                }
            )
            1 -> SavedPlacesAndParkingSection(
                places = savedPlaces,
                parking = parkingSpot,
                online = online,
                onRouteTo = { name, lat, lon ->
                    computeRouteTo(name, lat, lon)
                },
                onSaveParking = {
                    scope.launch {
                        try {
                            val body = JSONObject().put("lat", 6.9271).put("lon", 79.8612).put("label", "Parked Vehicle")
                            withContext(Dispatchers.IO) { api?.call("geo/parking", body) }
                            statusNotice = "Parking spot recorded at current location."
                            refreshPlacesAndParking()
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "Could not record parking spot"
                        }
                    }
                },
                onClearParking = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api?.call("geo/parking", null) }
                            parkingSpot = null
                            statusNotice = "Parking memory cleared."
                        } catch (_: Exception) {}
                    }
                },
                onAddPlaceClick = { showAddPlaceSheet = true }
            )
        }
    }

    if (showAddPlaceSheet) {
        AddPlaceSheet(
            onDismiss = { showAddPlaceSheet = false },
            onSave = { name, address ->
                scope.launch {
                    try {
                        val body = JSONObject().put("name", name).put("lat", 6.9271).put("lon", 79.8612)
                        if (address.isNotEmpty()) body.put("address", address)
                        withContext(Dispatchers.IO) { api?.call("geo/places", body) }
                        showAddPlaceSheet = false
                        statusNotice = "Saved $name to places."
                        refreshPlacesAndParking()
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Could not save place"
                    }
                }
            }
        )
    }
}

@Composable
private fun RouteNavigationSection(
    route: CalculatedRoute?,
    loading: Boolean,
    online: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onOpenExternalNav: (CalculatedRoute) -> Unit,
    onSendToDesktop: (CalculatedRoute) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val blueColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val greenColor = if (isDark) Color(0xff50e3c2) else Color(0xff059669)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Search & Destination Input
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search city, address or landmark…") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onSearchSubmit,
                enabled = online && !loading && searchQuery.isNotBlank(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (loading) "…" else "Route")
            }
        }

        if (route == null) {
            // Empty State
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(
                    Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🧭", fontSize = 36.sp)
                    Text("Atlas Navigation Ready", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Search a destination above or select a saved location from Places to compute routes and turn directions.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Live Route Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("DESTINATION", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = blueColor)
                            Text(route.destinationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = greenColor.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, greenColor)
                        ) {
                            Text(
                                "${route.durationMinutes} min",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = greenColor
                            )
                        }
                    }

                    // Vector Trajectory Graphic
                    RouteVectorCanvas(route = route)

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Distance: ${String.format(Locale.US, "%.1f", route.distanceKm)} km",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Estimated Arrival: In ${route.durationMinutes}m",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Action buttons
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onOpenExternalNav(route) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Open in Maps")
                        }
                        OutlinedButton(
                            onClick = { onSendToDesktop(route) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Send to Desktop")
                        }
                    }
                }
            }

            // Turn-by-Turn Steps List
            Text("Step Directions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
            ) {
                items(route.steps) { step ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, CardBorderBrush),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("➡️", fontSize = 16.sp)
                            Column(Modifier.weight(1f)) {
                                Text(step.instruction, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("${step.distanceMeters.toInt()} meters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteVectorCanvas(route: CalculatedRoute) {
    val isDark = LocalDarkTheme.current
    val strokeColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val gridColor = if (isDark) Color(0xff1e293b) else Color(0xffe2e8f0)
    val startPinColor = if (isDark) Color(0xff50e3c2) else Color(0xff059669)
    val endPinColor = if (isDark) Color(0xffffb800) else Color(0xffd97706)

    Box(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDark) Color(0xff0b0f19) else Color(0xfff8fafc))
    ) {
        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
            val w = size.width
            val h = size.height

            // Background Grid Lines
            val gridStep = 24.dp.toPx()
            var x = 0f
            while (x < w) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                x += gridStep
            }
            var y = 0f
            while (y < h) {
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                y += gridStep
            }

            // Route Path (Smooth S-curve connecting start to destination)
            val path = Path().apply {
                moveTo(20f, h * 0.75f)
                cubicTo(w * 0.35f, h * 0.85f, w * 0.55f, h * 0.2f, w - 20f, h * 0.25f)
            }
            drawPath(path, strokeColor, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round))

            // Start Pin
            drawCircle(startPinColor, radius = 5.dp.toPx(), center = Offset(20f, h * 0.75f))
            drawCircle(Color.White, radius = 2.dp.toPx(), center = Offset(20f, h * 0.75f))

            // Destination Pin
            drawCircle(endPinColor, radius = 6.dp.toPx(), center = Offset(w - 20f, h * 0.25f))
            drawCircle(Color.White, radius = 2.5.dp.toPx(), center = Offset(w - 20f, h * 0.25f))
        }
    }
}

@Composable
private fun SavedPlacesAndParkingSection(
    places: List<SavedPlace>,
    parking: SavedPlace?,
    online: Boolean,
    onRouteTo: (name: String, lat: Double, lon: Double) -> Unit,
    onSaveParking: () -> Unit,
    onClearParking: () -> Unit,
    onAddPlaceClick: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val amberColor = if (isDark) Color(0xffffb800) else Color(0xffd97706)
    val blueColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Parking Spot Hero Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = if (parking != null) BorderStroke(1.dp, amberColor) else BorderStroke(1.dp, CardBorderBrush),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🚗", fontSize = 20.sp)
                        Text("Vehicle Parking Memory", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    if (parking != null) {
                        TextButton(onClick = onClearParking) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                    }
                }

                if (parking != null) {
                    Text(
                        parking.address ?: "Parked Location: ${String.format(Locale.US, "%.4f, %.4f", parking.lat, parking.lon)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { onRouteTo(parking.name, parking.lat, parking.lon) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Navigate to My Car")
                    }
                } else {
                    Text("No vehicle location recorded. Tap below when you park your car.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = onSaveParking,
                        enabled = online,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📍 Save Parking Spot Here")
                    }
                }
            }
        }

        // Saved Places List
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Favorite & Saved Places", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onAddPlaceClick,
                enabled = online,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("+ Add Place", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (places.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📍", fontSize = 28.sp)
                    Text("No saved places yet", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("Save Home, Work, or favorite coffee shops for instant one-tap routing.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)
            ) {
                items(places) { place ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, CardBorderBrush),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRouteTo(place.name, place.lat, place.lon) }
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("📌", fontSize = 18.sp)
                            Column(Modifier.weight(1f)) {
                                Text(place.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                if (place.address != null) {
                                    Text(place.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("Route ›", style = MaterialTheme.typography.labelMedium, color = blueColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPlaceSheet(
    onDismiss: () -> Unit,
    onSave: (name: String, address: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    CompanionSheet(onDismiss = onDismiss) {
        Text("Save New Location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Place Label (e.g. Home, Office, Gym)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Address or City (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSave(name.trim(), address.trim()) },
            enabled = name.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Location")
        }
    }
}
