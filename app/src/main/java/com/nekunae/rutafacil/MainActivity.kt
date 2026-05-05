package com.nekunae.rutafacil

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.compose.*
import com.nekunae.rutafacil.ui.theme.RutaFacilTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

const val GOOGLE_MAPS_API_KEY = "AIzaSyCJCnArRYnQ8euZF4p28w-F2yNYas8QE48"

// Colores para cada marcador
val MARKER_COLORS = listOf(
    Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFF9800),
    Color(0xFF9C27B0), Color(0xFFE91E63), Color(0xFF00BCD4)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RutaFacilTheme { MainScreen() } }
    }
}

data class RouteInfo(
    val polylinePoints: List<LatLng>,
    val distanceText: String,
    val durationText: String
)

enum class InputMode { MAP, TEXT }

// ── Pantalla principal ───────────────────────────────────────────────────────

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Map, null) }, label = { Text("Mapa") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.List, null) }, label = { Text("Lista") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> MapScreen()
                1 -> ListScreen()
            }
        }
    }
}

// ── Pantalla de Mapa ─────────────────────────────────────────────────────────

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@SuppressLint("MissingPermission")
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val bogota = LatLng(4.60971, -74.08175)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bogota, 12f)
    }

    var inputMode by remember { mutableStateOf(InputMode.TEXT) }
    val waypoints = remember { mutableStateListOf<LatLng>() }
    val waypointTexts = remember { mutableStateListOf(
        "Cl. 13 #16-74, Bogotá",
        "Carrera 30 y Calle 57, Teusaquillo, Bogotá",
        "Tv. 3C. 49 - 02 Bogotá D.C."
    ) }
    var routeInfo by remember { mutableStateOf<RouteInfo?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasLocationPermission = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    fun getCurrentLocation(onResult: (LatLng) -> Unit) {
        if (!hasLocationPermission) { errorMessage = "Permiso de ubicación denegado"; return }
        coroutineScope.launch {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                val cts = CancellationTokenSource()
                val loc = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
                if (loc != null) {
                    val ll = LatLng(loc.latitude, loc.longitude)
                    userLocation = ll
                    onResult(ll)
                } else {
                    errorMessage = "No se pudo obtener la ubicación"
                }
            } catch (e: Exception) {
                errorMessage = "Error GPS: ${e.message}"
            }
        }
    }

    fun clearRoute() {
        waypoints.clear()
        waypointTexts.clear()
        waypointTexts.addAll(listOf("", ""))
        routeInfo = null; errorMessage = null
    }

    fun calculateMultiRoute(points: List<LatLng>) {
        if (points.size < 2) return
        coroutineScope.launch {
            isLoadingRoute = true; errorMessage = null
            try {
                routeInfo = fetchRouteMulti(points)
            } catch (e: Exception) {
                errorMessage = "No se pudo calcular: ${e.message}"
            } finally {
                isLoadingRoute = false
            }
        }
    }

    fun geocodeAndRouteAll() {
        if (waypointTexts.any { it.isBlank() }) { errorMessage = "Completa todos los puntos"; return }
        focusManager.clearFocus()
        coroutineScope.launch {
            isLoadingRoute = true; errorMessage = null
            try {
                val resolved = waypointTexts.map { addr ->
                    geocodeAddress(addr) ?: throw Exception("No encontrado: \"$addr\"")
                }
                waypoints.clear(); waypoints.addAll(resolved)
                routeInfo = fetchRouteMulti(resolved)
                cameraPositionState.position = CameraPosition.fromLatLngZoom(resolved.first(), 13f)
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoadingRoute = false
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
        geocodeAndRouteAll()
    }

    // Estado del hint
    val mapHint = when {
        waypoints.isEmpty() -> "Toca para agregar PUNTO 1 (origen)"
        waypoints.size == 1 -> "Toca para agregar PUNTO 2"
        else -> "${waypoints.size} puntos • Toca para agregar más"
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Mapa ──────────────────────────────────────────────────────────────
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
            uiSettings = MapUiSettings(myLocationButtonEnabled = false),
            onMapClick = { latLng ->
                if (inputMode == InputMode.MAP && !isLoadingRoute) {
                    waypoints.add(latLng)
                    routeInfo = null
                    if (waypoints.size >= 2) calculateMultiRoute(waypoints.toList())
                }
            }
        ) {
            waypoints.forEachIndexed { i, point ->
                val label = when (i) {
                    0 -> "Origen"
                    waypoints.lastIndex -> "Destino"
                    else -> "Punto ${i + 1}"
                }
                Marker(
                    state = MarkerState(position = point),
                    title = label,
                    snippet = "Lat: ${"%.4f".format(point.latitude)}, Lng: ${"%.4f".format(point.longitude)}"
                )
            }
            userLocation?.let {
                Circle(center = it, radius = 30.0, fillColor = Color(0x440000FF), strokeColor = Color(0xFF0000FF), strokeWidth = 2f)
            }
            routeInfo?.let { route ->
                Polyline(points = route.polylinePoints, color = Color(0xFF1565C0), width = 12f)
            }
        }

        // ── Botones top-right (GPS + toggle modo) ─────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Botón GPS
            FloatingActionButton(
                onClick = {
                    getCurrentLocation { loc ->
                        if (inputMode == InputMode.MAP) {
                            waypoints.add(loc)
                            cameraPositionState.position = CameraPosition.fromLatLngZoom(loc, 15f)
                            if (waypoints.size >= 2) calculateMultiRoute(waypoints.toList())
                        } else {
                            // En modo texto, poner "Mi ubicación" en el primer campo vacío
                            val idx = waypointTexts.indexOfFirst { it.isBlank() }
                            if (idx != -1) waypointTexts[idx] = "Mi ubicación (${
                                "%.4f".format(loc.latitude)},${
                                "%.4f".format(loc.longitude)})"
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Mi ubicación", modifier = Modifier.size(20.dp))
            }
            // Toggle modo
            FloatingActionButton(
                onClick = { inputMode = if (inputMode == InputMode.MAP) InputMode.TEXT else InputMode.MAP; clearRoute() },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = if (inputMode == InputMode.MAP) Icons.Default.Edit else Icons.Default.Map,
                    contentDescription = "Modo",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ── Hint modo mapa ────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = inputMode == InputMode.MAP,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 12.dp, end = 70.dp),
            enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 4.dp
            ) {
                Text(
                    text = mapHint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // ── Panel texto con waypoints dinámicos ───────────────────────────────
        AnimatedVisibility(
            visible = inputMode == InputMode.TEXT,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 12.dp, start = 12.dp, end = 70.dp),
            enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()
        ) {
            Card(elevation = CardDefaults.cardElevation(6.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    waypointTexts.forEachIndexed { index, value ->
                        val label = when (index) {
                            0 -> "Origen"
                            waypointTexts.lastIndex -> "Destino"
                            else -> "Parada ${index}"
                        }
                        val dotColor = MARKER_COLORS.getOrElse(index) { Color.Gray }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(dotColor, shape = MaterialTheme.shapes.small)
                            )
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(
                                value = value,
                                onValueChange = { waypointTexts[index] = it },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    imeAction = if (index == waypointTexts.lastIndex) ImeAction.Search else ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(onSearch = { geocodeAndRouteAll() }),
                                trailingIcon = {
                                    if (waypointTexts.size > 2 && index != 0 && index != waypointTexts.lastIndex) {
                                        IconButton(onClick = { waypointTexts.removeAt(index) }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Close, null)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    // Botón + parada
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { waypointTexts.add(waypointTexts.lastIndex, "") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Añadir parada")
                        }
                        Button(
                            onClick = { geocodeAndRouteAll() },
                            enabled = !isLoadingRoute,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Buscar")
                        }
                    }
                }
            }
        }

        // ── Loading ───────────────────────────────────────────────────────────
        if (isLoadingRoute) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Calculando ruta…")
                }
            }
        }

        // ── Error ─────────────────────────────────────────────────────────────
        errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
                action = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
            ) { Text(msg) }
        }

        // ── Info ruta + Botones de control ────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            routeInfo?.let { route ->
                Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Ruta con ${waypoints.size} puntos", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RouteInfoItem("Distancia", route.distanceText)
                            RouteInfoItem("Duración", route.durationText)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (waypoints.isNotEmpty()) {
                    // Deshacer último punto
                    OutlinedButton(
                        onClick = {
                            if (waypoints.isNotEmpty()) {
                                waypoints.removeLast()
                                routeInfo = null
                                if (waypoints.size >= 2) calculateMultiRoute(waypoints.toList())
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Undo, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Deshacer")
                    }
                    FilledTonalButton(onClick = { clearRoute() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Limpiar")
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteInfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

// ── Geocoding API ─────────────────────────────────────────────────────────────

suspend fun geocodeAddress(address: String): LatLng? = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(address, "UTF-8")
    val url = "https://maps.googleapis.com/maps/api/geocode/json?address=$encoded&language=es&key=$GOOGLE_MAPS_API_KEY"
    val json = JSONObject(URL(url).readText())
    if (json.getString("status") != "OK") return@withContext null
    val loc = json.getJSONArray("results").getJSONObject(0)
        .getJSONObject("geometry").getJSONObject("location")
    LatLng(loc.getDouble("lat"), loc.getDouble("lng"))
}

// ── Directions API multi-waypoint ─────────────────────────────────────────────

suspend fun fetchRouteMulti(points: List<LatLng>): RouteInfo = withContext(Dispatchers.IO) {
    require(points.size >= 2)
    val origin = "${points.first().latitude},${points.first().longitude}"
    val destination = "${points.last().latitude},${points.last().longitude}"
    val waypointsParam = if (points.size > 2) {
        "&waypoints=" + points.subList(1, points.lastIndex)
            .joinToString("|") { "${it.latitude},${it.longitude}" }
    } else ""

    val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=$origin&destination=$destination$waypointsParam" +
            "&mode=driving&language=es&key=$GOOGLE_MAPS_API_KEY"

    val json = JSONObject(URL(url).readText())
    check(json.getString("status") == "OK") { "Directions: ${json.getString("status")}" }

    val route = json.getJSONArray("routes").getJSONObject(0)

    // Distancia y duración totales sumando todas las legs
    val legs = route.getJSONArray("legs")
    var totalDistanceM = 0L
    var totalDurationS = 0L
    for (i in 0 until legs.length()) {
        val leg = legs.getJSONObject(i)
        totalDistanceM += leg.getJSONObject("distance").getLong("value")
        totalDurationS += leg.getJSONObject("duration").getLong("value")
    }
    val distText = if (totalDistanceM >= 1000) "${"%.1f".format(totalDistanceM / 1000.0)} km"
    else "$totalDistanceM m"
    val durText = buildString {
        val h = totalDurationS / 3600; val m = (totalDurationS % 3600) / 60
        if (h > 0) append("${h}h ")
        append("${m}min")
    }

    RouteInfo(
        polylinePoints = decodePolyline(route.getJSONObject("overview_polyline").getString("points")),
        distanceText = distText,
        durationText = durText
    )
}

// ── Decoder polilínea ─────────────────────────────────────────────────────────

fun decodePolyline(encoded: String): List<LatLng> {
    val poly = mutableListOf<LatLng>()
    var index = 0; val len = encoded.length
    var lat = 0; var lng = 0
    while (index < len) {
        var b: Int; var shift = 0; var result = 0
        do { b = encoded[index++].code - 63; result = result or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        shift = 0; result = 0
        do { b = encoded[index++].code - 63; result = result or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        poly.add(LatLng(lat / 1E5, lng / 1E5))
    }
    return poly
}

// ── Lista ─────────────────────────────────────────────────────────────────────

@Composable
fun ListScreen() {
    var text by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<String>() }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Agregar texto") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { if (text.isNotBlank()) { items.add(text); text = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Añadir") }
        Spacer(Modifier.height(16.dp))
        LazyColumn { itemsIndexed(items) { _, item -> Text(item, modifier = Modifier.padding(8.dp)) } }
    }
}