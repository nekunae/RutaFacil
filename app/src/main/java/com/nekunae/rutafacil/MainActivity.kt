package com.nekunae.rutafacil

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.*

const val GOOGLE_MAPS_API_KEY = "AIzaSyAW_rynJB10JvhhZb75mbZ6AEm3MdMrnmI"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RutaFacilTheme {
                var showSplash by remember { mutableStateOf(true) }

                // ── CAMBIO FIREBASE: autenticar de forma anónima al arrancar ──
                LaunchedEffect(Unit) {
                    LocationRepository.ensureSignedIn()
                }

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

// ── CAMBIO: rutas predefinidas seleccionables desde la pantalla de Lista ──────

data class PresetRoute(val id: String, val label: String, val addresses: List<String>)

val PRESET_IDA_ADDRESSES = listOf(
    "Cl. 13 #16-74, Bogotá",
    "Carrera 30 y Calle 57, Teusaquillo, Bogotá",
    "Tv. 3C. 49 - 02 Bogotá D.C."
)

val PRESET_ROUTES = listOf(
    PresetRoute(id = "ida", label = "Ida", addresses = PRESET_IDA_ADDRESSES),
    PresetRoute(id = "vuelta", label = "Vuelta", addresses = PRESET_IDA_ADDRESSES.reversed())
)

// ── Utilidad: distancia en metros entre dos LatLng (fórmula Haversine) ────────

fun haversineMeters(a: LatLng, b: LatLng): Double {
    val R = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLng = Math.toRadians(b.longitude - a.longitude)
    val sinDLat = sin(dLat / 2)
    val sinDLng = sin(dLng / 2)
    val h = sinDLat * sinDLat +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinDLng * sinDLng
    return 2 * R * asin(sqrt(h))
}

// ── CAMBIO: Recorta la polilínea eliminando los puntos ya recorridos ──────────
//
// Busca el punto de la polilínea más cercano a `userLoc` y devuelve
// solo los puntos desde ese índice en adelante.
// Si el punto más cercano está a más de `snapThresholdM` metros (señal
// de que el usuario se desvió mucho), devuelve la lista completa para
// que el llamador decida recalcular.
//
fun trimPolyline(
    polyline: List<LatLng>,
    userLoc: LatLng,
    snapThresholdM: Double = 50.0
): Pair<List<LatLng>, Boolean> {
    if (polyline.isEmpty()) return Pair(emptyList(), false)
    var minDist = Double.MAX_VALUE
    var minIdx = 0
    polyline.forEachIndexed { i, pt ->
        val d = haversineMeters(userLoc, pt)
        if (d < minDist) { minDist = d; minIdx = i }
    }
    val offRoute = minDist > snapThresholdM
    return Pair(polyline.subList(minIdx, polyline.size), offRoute)
}

// ── Pantalla principal ────────────────────────────────────────────────────────

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(0) }

    // ── CAMBIO: direcciones activas del mapa; empiezan con la ruta "Ida" ──────
    var activeAddresses by remember { mutableStateOf(PRESET_IDA_ADDRESSES) }

    Scaffold(
        bottomBar = {
            // Barra de navegación inferior: sin cambios respecto a la versión anterior.
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Map, contentDescription = "Mapa") },
                    label = { Text("Mapa") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Lista") },
                    label = { Text("Lista") }
                )
                // ── CAMBIO PERFIL: nueva pestaña de perfil ─────────────────────
                NavigationBarItem(
                    selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Perfil") },
                    label = { Text("Perfil") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                // key() fuerza que MapScreen se recree con las nuevas direcciones
                // cada vez que se elige una ruta desde la Lista.
                0 -> key(activeAddresses) { MapScreen(initialAddresses = activeAddresses) }
                1 -> ListScreen(
                    onSelectRoute = { addresses ->
                        activeAddresses = addresses
                        selectedTab = 0
                    }
                )
                2 -> ProfileScreen()
            }
        }
    }
}

// ── Pantalla de Mapa ──────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(initialAddresses: List<String> = PRESET_IDA_ADDRESSES) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val bogota = LatLng(4.60971, -74.08175)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bogota, 12f)
    }

    // ── CAMBIO UI: colores de las paradas tomados del tema del sistema ────────
    // En vez de una paleta fija en hexadecimal, se arma con los roles del
    // Material theme del teléfono (primary/tertiary/secondary/error + sus
    // contenedores), así se adapta automáticamente si el usuario tiene Material
    // You / color dinámico activado.
    val stopColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer
    )

    var inputMode by remember { mutableStateOf(InputMode.TEXT) }
    val waypoints = remember { mutableStateListOf<LatLng>() }
    // ── CAMBIO: ahora arranca con las direcciones que llegan por parámetro ────
    val waypointTexts = remember { mutableStateListOf(*initialAddresses.toTypedArray()) }
    var routeInfo by remember { mutableStateOf<RouteInfo?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasLocationPermission by remember { mutableStateOf(false) }

    // ── Estados de seguimiento ────────────────────────────────────────────────
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var followCamera by remember { mutableStateOf(true) }

    // ── CAMBIO FIREBASE: estado de guardado de ruta ───────────────────────────
    var isSavingRoute by remember { mutableStateOf(false) }
    var saveConfirmation by remember { mutableStateOf<String?>(null) }

    // ── CAMBIO: polilínea "viva" que se va recortando durante el seguimiento ──
    // `livePolyline` es una copia recortada de routeInfo.polylinePoints.
    // Cuando NO hay seguimiento activo se muestra la ruta completa.
    var livePolyline by remember { mutableStateOf<List<LatLng>>(emptyList()) }

    // ── CAMBIO: flag para saber si el usuario se desvió y hay que recalcular ─
    var isRecalculating by remember { mutableStateOf(false) }

    // ── CAMBIO BUGFIX: índice de la próxima parada aún no alcanzada.
    // 0 = origen (ya "alcanzado" al arrancar), así que empieza en 1.
    // Se usa para que, al recalcular por desvío, la ruta nueva siga
    // pasando por las paradas que faltan en vez de saltar directo al
    // destino final.
    var nextWaypointIndex by remember { mutableStateOf(1) }

    // ── CAMBIO UI: el formulario de direcciones arranca abierto, pero se
    // colapsa solo en cuanto hay una ruta calculada (ver geocodeAndRouteAll).
    // Tocar la ficha resumen lo vuelve a abrir.
    var showRouteEditor by remember { mutableStateOf(true) }

    // ── CAMBIO UI: menú "más opciones" de la barra lateral ─────────────────
    var moreMenuExpanded by remember { mutableStateOf(false) }

    // Animación pulso GPS
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseRadius by pulseAnim.animateFloat(
        initialValue = 20f, targetValue = 60f, label = "pulseRadius",
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart)
    )
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue = 0.5f, targetValue = 0f, label = "pulseAlpha",
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart)
    )

    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val ll = LatLng(loc.latitude, loc.longitude)
                    userLocation = ll

                    // ── CAMBIO: recortar polilínea al avanzar ─────────────────
                    if (isTracking) {
                        val currentPolyline = routeInfo?.polylinePoints ?: emptyList()
                        if (currentPolyline.isNotEmpty()) {
                            val (trimmed, offRoute) = trimPolyline(currentPolyline, ll)
                            livePolyline = trimmed

                            // ── CAMBIO BUGFIX: si ya llegamos cerca de la próxima
                            // parada (y no es la última, que es el destino final),
                            // avanzamos el índice de progreso. Así, si más adelante
                            // hay que recalcular, esa parada ya no se vuelve a pedir.
                            if (nextWaypointIndex < waypoints.lastIndex &&
                                haversineMeters(ll, waypoints[nextWaypointIndex]) < 40.0
                            ) {
                                nextWaypointIndex++
                            }

                            // Si se desvió más de 50 m, recalcular desde posición actual
                            if (offRoute && !isRecalculating && waypoints.isNotEmpty()) {
                                isRecalculating = true
                                coroutineScope.launch {
                                    try {
                                        // ── CAMBIO BUGFIX: nueva ruta = posición actual →
                                        // TODAS las paradas restantes (no solo el destino).
                                        // Antes esto se saltaba y por eso el recálculo
                                        // iba derecho al final sin pasar por las paradas.
                                        val startIdx = nextWaypointIndex.coerceIn(1, waypoints.lastIndex)
                                        val remainingStops = waypoints.subList(startIdx, waypoints.size)
                                        val newPoints = mutableListOf(ll)
                                        newPoints.addAll(remainingStops)
                                        val newRoute = fetchRouteMulti(newPoints)
                                        routeInfo = newRoute
                                        livePolyline = newRoute.polylinePoints
                                    } catch (_: Exception) {
                                        // Silenciar error de recálculo; mantiene la ruta anterior
                                    } finally {
                                        isRecalculating = false
                                    }
                                }
                            }
                        }
                    }

                    // Mover cámara
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

    val locationRequest = remember {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
    }

    // ── CAMBIO: helper para evitar repetir la misma línea de reseteo tres veces ──
    fun resetLivePolyline() {
        livePolyline = routeInfo?.polylinePoints ?: emptyList()
    }

    LaunchedEffect(isTracking, hasLocationPermission) {
        if (isTracking && hasLocationPermission) {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } else {
            fusedClient.removeLocationUpdates(locationCallback)
            // ── CAMBIO: al detener, restaurar polilínea completa ─────────────
            if (!isTracking) resetLivePolyline()
        }
    }

    // ── CAMBIO: sincronizar livePolyline cuando llega una ruta nueva ──────────
    LaunchedEffect(routeInfo) {
        if (!isTracking) resetLivePolyline()
    }

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
        routeInfo = null
        livePolyline = emptyList()   // ── CAMBIO
        errorMessage = null
        isTracking = false
        showRouteEditor = true       // ── CAMBIO UI: reabre el formulario al limpiar
        nextWaypointIndex = 1         // ── CAMBIO BUGFIX: reinicia el progreso
    }

    fun calculateMultiRoute(points: List<LatLng>) {
        if (points.size < 2) return
        nextWaypointIndex = 1   // ── CAMBIO BUGFIX: ruta nueva, progreso desde cero
        coroutineScope.launch {
            isLoadingRoute = true; errorMessage = null
            try {
                val info = fetchRouteMulti(points)
                routeInfo = info
                livePolyline = info.polylinePoints   // ── CAMBIO
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
                nextWaypointIndex = 1   // ── CAMBIO BUGFIX: ruta nueva, progreso desde cero
                val info = fetchRouteMulti(resolved)
                routeInfo = info
                livePolyline = info.polylinePoints   // ── CAMBIO
                cameraPositionState.position = CameraPosition.fromLatLngZoom(resolved.first(), 13f)
                showRouteEditor = false   // ── CAMBIO UI: colapsa el formulario tras encontrar la ruta
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoadingRoute = false
            }
        }
    }

    // ── CAMBIO FIREBASE: guarda la ruta actual en Firestore ───────────────────
    fun saveCurrentRoute() {
        val route = routeInfo ?: return
        coroutineScope.launch {
            isSavingRoute = true
            try {
                LocationRepository.saveRoute(
                    waypointLabels = waypointTexts.toList(),
                    waypoints = waypoints.toList(),
                    distanceText = route.distanceText,
                    durationText = route.durationText
                )
                saveConfirmation = "Ruta guardada"
            } catch (e: Exception) {
                errorMessage = "No se pudo guardar: ${e.message}"
            } finally {
                isSavingRoute = false
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
            properties = MapProperties(isMyLocationEnabled = false),
            uiSettings = MapUiSettings(myLocationButtonEnabled = false),
            onMapClick = { latLng ->
                if (inputMode == InputMode.MAP && !isLoadingRoute) {
                    waypoints.add(latLng); routeInfo = null; livePolyline = emptyList()
                    if (waypoints.size >= 2) calculateMultiRoute(waypoints.toList())
                }
                followCamera = false
            }
        ) {
            // Marcadores de waypoints (íconos por defecto de Google Maps)
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
                if (isTracking) {
                    Circle(
                        center = loc,
                        radius = pulseRadius.toDouble(),
                        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha * 0.25f),
                        strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha * 0.5f),
                        strokeWidth = 2f
                    )
                }
                Circle(
                    center = loc,
                    radius = 12.0,
                    fillColor = if (isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    strokeColor = Color.White,
                    strokeWidth = 3f
                )
            }

            // ── CAMBIO: dibuja livePolyline en lugar de routeInfo directamente ─
            // Cuando hay seguimiento activo, livePolyline es la ruta recortada.
            // Cuando no, es la ruta completa (mismos puntos que antes).
            if (livePolyline.size >= 2) {
                Polyline(points = livePolyline, color = MaterialTheme.colorScheme.primary, width = 12f)
            }
        }

        // ── Hint modo mapa ────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = inputMode == InputMode.MAP,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 12.dp, end = 12.dp),
            enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 4.dp) {
                Text(mapHint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        // ── Panel texto (formulario completo) ───────────────────────────────
        // ── CAMBIO UI: solo visible mientras no hay ruta calculada, o mientras
        // el usuario decide editarla explícitamente (showRouteEditor = true).
        // Una vez hay ruta, se colapsa en la ficha resumen de abajo, dejando
        // el mapa como protagonista de la pantalla.
        AnimatedVisibility(
            visible = inputMode == InputMode.TEXT && showRouteEditor,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp),
            enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()
        ) {
            Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(6.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {

                    // ── CAMBIO UI: solo aparece cuando ya hay una ruta, para poder
                    // colapsar el formulario manualmente sin recalcular nada.
                    if (routeInfo != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Editar ruta",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                            IconButton(onClick = { showRouteEditor = false }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ExpandLess, contentDescription = "Colapsar formulario", modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // ── CAMBIO UI: diagrama de línea de ruta ───────────────────
                    // Los puntos se conectan con una línea vertical, como un mapa
                    // de línea de transporte: el orden de las paradas es
                    // información real, así que tiene sentido mostrarlo como
                    // una secuencia.
                    waypointTexts.forEachIndexed { index, value ->
                        val label = when (index) { 0 -> "Origen"; waypointTexts.lastIndex -> "Destino"; else -> "Parada $index" }
                        val dotColor = stopColors.getOrElse(index) { MaterialTheme.colorScheme.outline }
                        val isLast = index == waypointTexts.lastIndex
                        Row(verticalAlignment = Alignment.Top) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(top = 16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(dotColor, shape = CircleShape)
                                )
                                if (!isLast) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(38.dp)
                                            .background(dotColor.copy(alpha = 0.3f))
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            OutlinedTextField(
                                value = value, onValueChange = { waypointTexts[index] = it },
                                label = { Text(label) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(bottom = if (!isLast) 6.dp else 0.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = if (isLast) ImeAction.Search else ImeAction.Next),
                                keyboardActions = KeyboardActions(onSearch = { geocodeAndRouteAll() }),
                                trailingIcon = {
                                    if (waypointTexts.size > 2 && index != 0 && !isLast) {
                                        IconButton(onClick = { waypointTexts.removeAt(index) }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Quitar parada")
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { waypointTexts.add(waypointTexts.lastIndex, "") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Añadir parada")
                        }
                        Button(
                            onClick = { geocodeAndRouteAll() },
                            enabled = !isLoadingRoute,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Buscar")
                        }
                    }
                }
            }
        }

        // ── CAMBIO UI: ficha resumen colapsada ──────────────────────────────
        // Reemplaza al formulario completo una vez que ya hay una ruta: solo
        // muestra origen → destino en una línea. Tocarla reabre la edición.
        AnimatedVisibility(
            visible = inputMode == InputMode.TEXT && !showRouteEditor && routeInfo != null,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp),
            enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()
        ) {
            RouteSummaryChip(
                origin = waypointTexts.firstOrNull().orEmpty(),
                destination = waypointTexts.lastOrNull().orEmpty(),
                stopCount = waypointTexts.size,
                onClick = { showRouteEditor = true }
            )
        }

        // ── CAMBIO UI: barra lateral de acciones ────────────────────────────
        // Antes había varios íconos sueltos (ubicación, modo, guardar, deshacer,
        // limpiar). Ahora solo queda "centrar en mi ubicación" (la acción más
        // usada) más un único botón de "más opciones" que despliega el resto
        // en un submenú — menos objetos visibles en todo momento.
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(onClick = {
                    followCamera = true
                    userLocation?.let { loc ->
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                update = com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(loc, 16f),
                                durationMs = 600
                            )
                        }
                    }
                }) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Centrar en mi ubicación", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Box {
                    IconButton(onClick = { moreMenuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Más opciones", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(if (inputMode == InputMode.MAP) "Cambiar a modo texto" else "Cambiar a modo mapa") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (inputMode == InputMode.MAP) Icons.Default.Edit else Icons.Default.Map,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                inputMode = if (inputMode == InputMode.MAP) InputMode.TEXT else InputMode.MAP
                                clearRoute()
                                moreMenuExpanded = false
                            }
                        )
                        if (routeInfo != null) {
                            DropdownMenuItem(
                                text = { Text(if (isSavingRoute) "Guardando…" else "Guardar ruta") },
                                leadingIcon = {
                                    if (isSavingRoute) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                    }
                                },
                                enabled = !isSavingRoute,
                                onClick = {
                                    saveCurrentRoute()
                                    moreMenuExpanded = false
                                }
                            )
                        }
                        if (waypoints.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Deshacer último punto") },
                                leadingIcon = { Icon(Icons.Default.Undo, contentDescription = null) },
                                onClick = {
                                    waypoints.removeLast(); routeInfo = null; livePolyline = emptyList()
                                    if (waypoints.size >= 2) calculateMultiRoute(waypoints.toList())
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Limpiar ruta") },
                                leadingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    clearRoute()
                                    moreMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Loading ───────────────────────────────────────────────────────────
        if (isLoadingRoute || isRecalculating) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    // ── CAMBIO: mensaje diferente si es recálculo ─────────────
                    Text(if (isRecalculating) "Recalculando ruta…" else "Calculando ruta…", fontWeight = FontWeight.Medium)
                }
            }
        }

        // ── Error ─────────────────────────────────────────────────────────────
        errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                action = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg)
                }
            }
        }

        // ── CAMBIO FIREBASE: confirmación de guardado ─────────────────────────
        saveConfirmation?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                saveConfirmation = null
            }
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg)
                }
            }
        }

        // ── Panel inferior ────────────────────────────────────────────────────
        // Solo muestra lo esencial (estado + acción principal). Guardar/
        // Deshacer/Limpiar/Modo viven en el menú "más opciones" de la barra lateral.
        routeInfo?.let { route ->
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(6.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Tu ruta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${waypoints.size} puntos en el recorrido",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (isTracking) {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiary) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val blinkAnim by rememberInfiniteTransition(label = "blink")
                                        .animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blinkAlpha")
                                    Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.onTertiary.copy(alpha = blinkAnim), shape = CircleShape))
                                    Text("EN VIVO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiary)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // ── CAMBIO UI: se quitó el indicador "GPS: Activo"; el punto
                    // pulsante ya visible en el mapa comunica lo mismo sin sumar
                    // otro elemento a esta tarjeta.
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RouteInfoItem(Icons.Default.Straighten, "Distancia", route.distanceText)
                        RouteInfoItem(Icons.Default.Schedule, "Duración", route.durationText)
                        if (isTracking && livePolyline.size >= 2) {
                            val remainingKm = estimateRemainingKm(livePolyline)
                            RouteInfoItem(
                                Icons.Default.Flag, "Restante",
                                if (remainingKm >= 1.0) "${"%.1f".format(remainingKm)} km" else "${(remainingKm * 1000).toInt()} m"
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))

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
                            // ── CAMBIO: al reactivar, resetear livePolyline ─
                            else resetLivePolyline()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTracking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isTracking) Icons.Default.Stop else Icons.Default.Navigation,
                            contentDescription = null, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isTracking) "Detener seguimiento" else "Iniciar seguimiento", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ── CAMBIO UI: ficha compacta de resumen de ruta (origen → destino) ───────────
// Sustituye al formulario completo una vez que ya se calculó una ruta, para
// que el mapa ocupe la mayor parte de la pantalla. Un toque la reabre.
@Composable
private fun RouteSummaryChip(
    origin: String,
    destination: String,
    stopCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$origin  →  $destination",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (stopCount > 2) {
                    Text(
                        "$stopCount puntos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Icon(Icons.Default.Edit, contentDescription = "Editar ruta", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

// ── CAMBIO: calcula km restantes sumando segmentos de la polilínea recortada ──

fun estimateRemainingKm(polyline: List<LatLng>): Double {
    var total = 0.0
    for (i in 0 until polyline.size - 1) total += haversineMeters(polyline[i], polyline[i + 1])
    return total / 1000.0
}

// ── CAMBIO UI: ahora cada dato lleva un ícono, y el valor pesa más que la
// etiqueta, como un tablero de instrucciones legible de un vistazo.
@Composable
private fun RouteInfoItem(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
// ── CAMBIO FIREBASE: ahora muestra favoritos y rutas guardadas en Firestore ──

@Composable
fun ListScreen(onSelectRoute: (List<String>) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var favorites by remember { mutableStateOf<List<FavoritePlace>>(emptyList()) }
    var routes by remember { mutableStateOf<List<SavedRoute>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ── CAMBIO: ruta rápida seleccionada (Ida / Vuelta) ───────────────────────
    var selectedPresetId by remember { mutableStateOf(PRESET_ROUTES.first().id) }

    suspend fun reload() {
        try {
            favorites = LocationRepository.getFavoritePlaces()
            routes = LocationRepository.getRecentRoutes()
        } catch (e: Exception) {
            errorMessage = "No se pudo cargar: ${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        reload()
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ── CAMBIO: sección de rutas rápidas Ida / Vuelta ─────────────
                item {
                    Text("Rutas rápidas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Elige un sentido para cargar los puntos automáticamente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(PRESET_ROUTES, key = { it.id }) { preset ->
                    val isSelected = selectedPresetId == preset.id
                    Card(
                        onClick = { selectedPresetId = preset.id },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = if (preset.id == "ida") Icons.Default.ArrowForward else Icons.Default.ArrowBack,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(preset.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    preset.addresses.joinToString(" → "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Ruta seleccionada",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            PRESET_ROUTES.find { it.id == selectedPresetId }?.let { onSelectRoute(it.addresses) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Usar esta ruta")
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Favoritos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { coroutineScope.launch { reload() } }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualizar favoritos")
                        }
                    }
                }
                if (favorites.isEmpty()) {
                    item { EmptyStateHint(Icons.Default.StarBorder, "Guarda tus lugares frecuentes para encontrarlos aquí en un toque") }
                }
                items(favorites, key = { it.id }) { place ->
                    ListItem(
                        headlineContent = { Text(place.label.ifBlank { "Sin nombre" }) },
                        supportingContent = { Text(place.address) },
                        leadingContent = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    LocationRepository.deleteFavoritePlace(place.id)
                                    reload()
                                }
                            }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar favorito", tint = MaterialTheme.colorScheme.error) }
                        }
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Rutas recientes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (routes.isEmpty()) {
                    item { EmptyStateHint(Icons.Default.History, "Cuando guardes una ruta calculada, aparecerá en esta lista") }
                }
                items(routes, key = { it.id }) { r ->
                    ListItem(
                        headlineContent = { Text(r.waypointLabels.filter { it.isNotBlank() }.joinToString(" → ")) },
                        supportingContent = { Text("${r.distanceText} · ${r.durationText}") },
                        leadingContent = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    LocationRepository.deleteRoute(r.id)
                                    reload()
                                }
                            }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar ruta", tint = MaterialTheme.colorScheme.error) }
                        }
                    )
                }
            }
        }

        errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                action = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
            ) { Text(msg) }
        }
    }
}

// ── CAMBIO PERFIL: pantalla de perfil (nombre + foto) ─────────────────────────
// Se guarda localmente en el dispositivo con SharedPreferences: no depende de
// LocationRepository (ese archivo no está disponible aquí), así que si más
// adelante quieres sincronizar el perfil entre dispositivos vía Firebase,
// habría que sumarle a LocationRepository algo como
// updateUserProfile(displayName, photoUrl) y llamarlo desde aquí también.

private const val PROFILE_PREFS = "rutafacil_profile"
private const val PREF_DISPLAY_NAME = "display_name"
private const val PREF_PHOTO_URI = "photo_uri"

@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE) }

    var displayName by remember { mutableStateOf(prefs.getString(PREF_DISPLAY_NAME, "") ?: "") }
    var isEditingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(displayName) }

    var photoUriString by remember { mutableStateOf(prefs.getString(PREF_PHOTO_URI, null)) }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saveConfirmation by remember { mutableStateOf<String?>(null) }

    // Carga la imagen guardada (si hay) cada vez que cambia la URI.
    LaunchedEffect(photoUriString) {
        photoBitmap = photoUriString?.let { uriStr ->
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                // Permiso persistente para poder seguir leyendo la foto
                // después de cerrar la app. No todos los proveedores lo
                // soportan, por eso va en try/catch.
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            photoUriString = uri.toString()
            prefs.edit().putString(PREF_PHOTO_URI, uri.toString()).apply()
            saveConfirmation = "Foto actualizada"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // ── Avatar con botón de cámara superpuesto ─────────────────────
            Box(contentAlignment = Alignment.BottomEnd) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(112.dp)
                ) {
                    if (photoBitmap != null) {
                        Image(
                            bitmap = photoBitmap!!.asImageBitmap(),
                            contentDescription = "Foto de perfil",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                ) {
                    IconButton(onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "Cambiar foto",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Nombre de usuario, editable ──────────────────────────────────
            if (isEditingName) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Nombre de usuario") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        nameDraft = displayName
                        isEditingName = false
                    }) { Text("Cancelar") }
                    Button(onClick = {
                        displayName = nameDraft.trim()
                        prefs.edit().putString(PREF_DISPLAY_NAME, displayName).apply()
                        isEditingName = false
                        saveConfirmation = "Nombre actualizado"
                    }) { Text("Guardar") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName.ifBlank { "Añadir nombre" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (displayName.isBlank()) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = {
                        nameDraft = displayName
                        isEditingName = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Editar nombre", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Este perfil se guarda en este dispositivo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        saveConfirmation?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                saveConfirmation = null
            }
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg)
                }
            }
        }
    }
}

// ── CAMBIO UI: mensaje vacío reutilizable ─────────────────────────────────────
// Una lista vacía es una invitación a actuar, no solo un aviso; por eso lleva
// un ícono y explica qué hacer para que deje de estar vacía.
@Composable
private fun EmptyStateHint(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}