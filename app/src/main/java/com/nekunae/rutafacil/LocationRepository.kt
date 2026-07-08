package com.nekunae.rutafacil

import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.toObject
import kotlinx.coroutines.tasks.await

// ── Modelos de datos para Firestore ────────────────────────────────────────

data class FavoritePlace(
    val id: String = "",
    val label: String = "",      // "Casa", "Trabajo", etc.
    val address: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)

data class SavedRoute(
    val id: String = "",
    val waypointLabels: List<String> = emptyList(),
    val waypoints: List<Map<String, Double>> = emptyList(), // [{lat:.., lng:..}, ...]
    val distanceText: String = "",
    val durationText: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// ── Repositorio ─────────────────────────────────────────────────────────────
// Guarda to do bajo /users/{uid}/... para que cada usuario solo vea lo suyo

object LocationRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    // Llama esto una vez al iniciar la app (ej. en MainActivity.onCreate)
    suspend fun ensureSignedIn() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    private fun userCollection(name: String) =
        db.collection("users").document(requireNotNull(auth.currentUser?.uid) {
            "Usuario no autenticado; llama a ensureSignedIn() primero"
        }).collection(name)

    // ── Lugares favoritos ────────────────────────────────────────────────────

    suspend fun saveFavoritePlace(label: String, address: String, point: LatLng): String {
        val doc = userCollection("favorites").document()
        val place = FavoritePlace(
            id = doc.id,
            label = label,
            address = address,
            lat = point.latitude,
            lng = point.longitude
        )
        doc.set(place).await()
        return doc.id
    }

    suspend fun getFavoritePlaces(): List<FavoritePlace> {
        val snapshot = userCollection("favorites")
            .orderBy("createdAt")
            .get()
            .await()
        return snapshot.documents.mapNotNull { it.toObject<FavoritePlace>() }
    }

    suspend fun deleteFavoritePlace(id: String) {
        userCollection("favorites").document(id).delete().await()
    }

    // ── Rutas recientes ──────────────────────────────────────────────────────

    suspend fun saveRoute(
        waypointLabels: List<String>,
        waypoints: List<LatLng>,
        distanceText: String,
        durationText: String
    ): String {
        val doc = userCollection("routes").document()
        val route = SavedRoute(
            id = doc.id,
            waypointLabels = waypointLabels,
            waypoints = waypoints.map { mapOf("lat" to it.latitude, "lng" to it.longitude) },
            distanceText = distanceText,
            durationText = durationText
        )
        doc.set(route).await()
        return doc.id
    }

    suspend fun getRecentRoutes(limit: Long = 20): List<SavedRoute> {
        val snapshot = userCollection("routes")
            .orderBy("createdAt")
            .limitToLast(limit)
            .get()
            .await()
        return snapshot.documents.mapNotNull { it.toObject<SavedRoute>() }.reversed()
    }

    suspend fun deleteRoute(id: String) {
        userCollection("routes").document(id).delete().await()
    }

    // Convierte un SavedRoute de vuelta a puntos LatLng, útil para recalcular
    fun SavedRoute.toLatLngList(): List<LatLng> =
        waypoints.map { LatLng(it.getValue("lat"), it.getValue("lng")) }
}
