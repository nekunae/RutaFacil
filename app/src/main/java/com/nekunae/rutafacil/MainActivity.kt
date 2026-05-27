package com.nekunae.rutafacil

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.nekunae.rutafacil.ui.theme.RutaFacilTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

const val GOOGLE_MAPS_API_KEY = "AIzaSyCJCnArRYnQ8euZF4p28w-F2yNYas8QE48"

val MARKER_COLORS = listOf(
    Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFF9800),
    Color(0xFF9C27B0), Color(0xFFE91E63), Color(0xFF00BCD4)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RutaFacilTheme {
                var showSplash by remember { mutableStateOf(true) }
                AnimatedContent(
                    targetState = showSplash,
                    transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }
                ) { isSplash ->
                    if (isSplash) SplashScreen(onFinished = { showSplash = false })
                    else MainScreen()
                }
            }
        }
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
    val waypointTexts = remember {
        mutableStateListOf(
            "Cl. 13 #16-74, Bogotá",
            "Carrera 30 y Calle 57, Teusaquillo, Bogotá",
            "Tv. 3C. 49 - 02 Bogotá D.C."
        )
    }
    var routeInfo by remember { mutableStateOf<RouteInfo?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasLocationPermission by remember { mutableStateOf(false) }

    // ── Estados de seguimiento en tiempo real ─────────────────────────────────
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var followCamera by remember { mutableStateOf(true) }   // la cámara sigue al usuario mientras trackea

    // Animación del pulso del indicador GPS
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseRadius by pulseAnim.animateFloat(
        initialValue = 20f, targetValue = 60f, label = "pulseRadius",
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart)
    )
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue = 0.5f, targetValue = 0f, label = "pulseAlpha",
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart)
    )

    // FusedLocationProviderClient reutilizable
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // LocationCallback — se llama cada vez que llega una nueva posición
    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val ll = LatLng(loc.latitude, loc.longitude)
                    userLocation = ll
                    if (followCamera) {
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                update = com.google.android.gms.maps.CameraUpdateFactory
                                    .newLatLngZoom(ll, 16f),
                                durationMs = 800
                            )
                        }
                    }
                }
            }
        }
    }

    // Solicitud de actualizaciones: 1 s de intervalo, 1 s mínimo
    val locationRequest = remember {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
    }

    // Iniciar / detener actualizaciones según isTracking
    LaunchedEffect(isTracking, hasLocationPermission) {
        if (isTracking && hasLocationPermission) {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } else {
            fusedClient.removeLocationUpdates(locationCallback)
        }
    }

    // Limpiar al salir del composable
    DisposableEffect(Unit) {
        onDispose { fusedClient.removeLocationUpdates(locationCallback) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasLocationPermission = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    fun clearRoute() {
        waypoints.clear()
        waypointTexts.clear()
        waypointTexts.addAll(listOf("", ""))
        routeInfo = null; errorMessage = null; isTracking = false
    }

    fun calculateMultiRoute(points: List<LatLng>) {
        if (points.size < 2) return
        coroutineScope.launch {
            isLoadingRoute = true; errorMessage = null
            try { routeInfo = fetchRouteMulti(points) }
            catch (e: Exception) { errorMessage = "No se pudo calcular: ${e.message}" }
            finally { isLoadingRoute = false }
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
            } catch (e: Exception) { errorMessage = e.message }
            finally { isLoadingRoute = false }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
        geocodeAndRouteAll()
    }

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
            properties = MapProperties(isMyLocationEnabled = false), // usamos nuestro indicador custom
            uiSettings = MapUiSettings(myLocationButtonEnabled = false),
            onMapClick = { latLng ->
                if (inputMode == InputMode.MAP && !isLoadingRoute) {
                    waypoints.add(latLng); routeInfo = null
                    if (waypoints.size >= 2) calculateMultiRoute(waypoints.toList())
                }
                followCamera = false // el usuario movió el mapa manualmente
            }
        ) {
            // Marcadores de waypoints
            waypoints.forEachIndexed { i, point ->
                val label = when (i) {
                    0 -> "Origen"; waypoints.lastIndex -> "Destino"; else -> "Punto ${i + 1}"
                }
                Marker(
                    state = MarkerState(position = point),
                    title = label,
                    snippet = "Lat: ${"%.4f".format(point.latitude)}, Lng: ${"%.4f".format(point.longitude)}"
                )
            }

            // Indicador de posición en tiempo real
            userLocation?.let { loc ->
                // Anillo de pulso (solo cuando está trackeando)
                if (isTracking) {
                    Circle(
                        center = loc,
                        radius = pulseRadius.toDouble(),
                        fillColor = Color(0x220000FF).copy(alpha = pulseAlpha),
                        strokeColor = Color(0x440000FF).copy(alpha = pulseAlpha),
                        strokeWidth = 2f
                    )
                }
                // Punto sólido de posición
                Circle(
                    center = loc,
                    radius = 12.0,
                    fillColor = if (isTracking) Color(0xFF1565C0) else Color(0xFF1565C0).copy(alpha = 0.5f),
                    strokeColor = Color.White,
                    strokeWidth = 3f
                )
            }

            // Polilínea de ruta
            routeInfo?.let { route ->
                Polyline(points = route.polylinePoints, color = Color(0xFF1565C0), width = 12f)
            }
        }

        // ── Botones top-right ─────────────────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Botón centrar cámara en mi posición
            FloatingActionButton(
                onClick = {
                    followCamera = true
                    userLocation?.let { loc ->
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                update = com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(loc, 16f),
                                durationMs = 600
                            )
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Centrar", modifier = Modifier.size(20.dp))
            }
            // Toggle modo entrada
            FloatingActionButton(
                onClick = {
                    inputMode = if (inputMode == InputMode.MAP) InputMode.TEXT else InputMode.MAP
                    clearRoute()
                },
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
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 4.dp) {
                Text(mapHint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        // ── Panel texto ───────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = inputMode == InputMode.TEXT,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 70.dp),
            enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()
        ) {
            Card(elevation = CardDefaults.cardElevation(6.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    waypointTexts.forEachIndexed { index, value ->
                        val label = when (index) { 0 -> "Origen"; waypointTexts.lastIndex -> "Destino"; else -> "Parada $index" }
                        val dotColor = MARKER_COLORS.getOrElse(index) { Color.Gray }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(10.dp).background(dotColor, shape = MaterialTheme.shapes.small))
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(
                                value = value, onValueChange = { waypointTexts[index] = it },
                                label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = if (index == waypointTexts.lastIndex) ImeAction.Search else ImeAction.Next),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { waypointTexts.add(waypointTexts.lastIndex, "") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Añadir parada")
                        }
                        Button(onClick = { geocodeAndRouteAll() }, enabled = !isLoadingRoute, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Buscar")
                        }
                    }
                }
            }
        }

        // ── Loading ───────────────────────────────────────────────────────────
        if (isLoadingRoute) {
            Surface(modifier = Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
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

        // ── Panel inferior: info de ruta + controles ───────────────────────────
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            routeInfo?.let { route ->
                Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Encabezado con badge de tracking
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Ruta con ${waypoints.size} puntos", style = MaterialTheme.typography.titleMedium)
                            if (isTracking) {
                                Surface(shape = MaterialTheme.shapes.extraLarge, color = Color(0xFF1B5E20)) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        // Punto parpadeante
                                        val blinkAnim by rememberInfiniteTransition(label = "blink")
                                            .animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blinkAlpha")
                                        Box(modifier = Modifier.size(8.dp).background(Color(0xFF69F0AE).copy(alpha = blinkAnim), shape = MaterialTheme.shapes.extraLarge))
                                        Text("EN VIVO", style = MaterialTheme.typography.labelSmall, color = Color(0xFF69F0AE))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RouteInfoItem("Distancia", route.distanceText)
                            RouteInfoItem("Duración", route.durationText)
                            // Mostrar velocidad si está trackeando
                            userLocation?.let {
                                RouteInfoItem("GPS", "Activo")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // ── Botón de seguimiento ──────────────────────────────────────────────
                        Button(
                            onClick = {
                                if (!hasLocationPermission) {
                                    permissionLauncher.launch(arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    ))
                                    return@Button
                                }
                                isTracking = !isTracking
                                if (isTracking) followCamera = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isTracking) Color(0xFFB71C1C) else Color(0xFF1B5E20)
                            )
                        ) {
                            Icon(
                                imageVector = if (isTracking) Icons.Default.Stop else Icons.Default.Navigation,
                                contentDescription = null, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (isTracking) "Detener seguimiento" else "Iniciar seguimiento")
                        }
                    }
                }
            }

            // Botones deshacer / limpiar
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (waypoints.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            if (waypoints.isNotEmpty()) {
                                waypoints.removeLast(); routeInfo = null
                                if (waypoints.size >= 2) calculateMultiRoute(waypoints.toList())
                            }
                        }, modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Undo, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Deshacer")
                    }
                    FilledTonalButton(onClick = { clearRoute() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Limpiar")
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
    val loc = json.getJSONArray("results").getJSONObject(0).getJSONObject("geometry").getJSONObject("location")
    LatLng(loc.getDouble("lat"), loc.getDouble("lng"))
}

// ── Directions API ────────────────────────────────────────────────────────────

suspend fun fetchRouteMulti(points: List<LatLng>): RouteInfo = withContext(Dispatchers.IO) {
    require(points.size >= 2)
    val origin = "${points.first().latitude},${points.first().longitude}"
    val destination = "${points.last().latitude},${points.last().longitude}"
    val waypointsParam = if (points.size > 2)
        "&waypoints=" + points.subList(1, points.lastIndex).joinToString("|") { "${it.latitude},${it.longitude}" }
    else ""
    val url = "https://maps.googleapis.com/maps/api/directions/json?origin=$origin&destination=$destination$waypointsParam&mode=driving&language=es&key=$GOOGLE_MAPS_API_KEY"
    val json = JSONObject(URL(url).readText())
    check(json.getString("status") == "OK") { "Directions: ${json.getString("status")}" }
    val route = json.getJSONArray("routes").getJSONObject(0)
    val legs = route.getJSONArray("legs")
    var totalDistanceM = 0L; var totalDurationS = 0L
    for (i in 0 until legs.length()) {
        val leg = legs.getJSONObject(i)
        totalDistanceM += leg.getJSONObject("distance").getLong("value")
        totalDurationS += leg.getJSONObject("duration").getLong("value")
    }
    val distText = if (totalDistanceM >= 1000) "${"%.1f".format(totalDistanceM / 1000.0)} km" else "$totalDistanceM m"
    val durText = buildString {
        val h = totalDurationS / 3600; val m = (totalDurationS % 3600) / 60
        if (h > 0) append("${h}h "); append("${m}min")
    }
    RouteInfo(decodePolyline(route.getJSONObject("overview_polyline").getString("points")), distText, durText)
}

// ── Decoder polilínea ─────────────────────────────────────────────────────────

fun decodePolyline(encoded: String): List<LatLng> {
    val poly = mutableListOf<LatLng>()
    var index = 0; val len = encoded.length; var lat = 0; var lng = 0
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