package com.nekunae.rutafacil

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: () -> Unit) {

    // Animaciones
    val iconScale = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val dotAlpha1 = remember { Animatable(0f) }
    val dotAlpha2 = remember { Animatable(0f) }
    val dotAlpha3 = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Icono aparece con rebote
        iconScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
        delay(200)
        // Título fade-in
        textAlpha.animateTo(1f, animationSpec = tween(500))
        delay(150)
        // Subtítulo fade-in
        subtitleAlpha.animateTo(1f, animationSpec = tween(400))
        delay(400)
        // Puntos de carga animados
        repeat(2) {
            dotAlpha1.animateTo(1f, tween(200)); delay(150)
            dotAlpha2.animateTo(1f, tween(200)); delay(150)
            dotAlpha3.animateTo(1f, tween(200)); delay(300)
            dotAlpha1.animateTo(0.2f, tween(200))
            dotAlpha2.animateTo(0.2f, tween(200))
            dotAlpha3.animateTo(0.2f, tween(200))
            delay(200)
        }
        delay(300)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D47A1), // Azul oscuro
                        Color(0xFF1565C0),
                        Color(0xFF1976D2)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Ícono principal
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .scale(iconScale.value)
                    .background(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraLarge
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Título
            Text(
                text = "RutaFácil",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.alpha(textAlpha.value),
                letterSpacing = (-0.5).sp
            )

            // Subtítulo
            Text(
                text = "Aplicacion de rutas escolares",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.alpha(subtitleAlpha.value)
            )

            Spacer(Modifier.height(48.dp))

            // Puntos de carga
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(dotAlpha1.value, dotAlpha2.value, dotAlpha3.value).forEach { alpha ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .alpha(alpha)
                            .background(Color.White, shape = MaterialTheme.shapes.extraLarge)
                    )
                }
            }
        }

        // Versión abajo
        Text(
            text = "v1.0.0",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .alpha(subtitleAlpha.value)
        )
    }
}