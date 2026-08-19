## RutaFácil ##

RutaFácil es una aplicación Android para planificar y seguir rutas en Bogotá, Colombia. Está desarrollada con Kotlin y Jetpack Compose y utiliza Google Maps, GPS y las APIs de Google para calcular recorridos.

Permite crear rutas con varias paradas, buscarlas por dirección o seleccionarlas directamente en el mapa. También puede seguir la ubicación del usuario y recalcular la ruta si se detecta un desvío.

![Android](https://img.shields.io/badge/Android-3DDC84?style=flat\&logo=android\&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat\&logo=kotlin\&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat\&logo=jetpackcompose\&logoColor=white)
![Google Maps](https://img.shields.io/badge/Google%20Maps%20SDK-4285F4?style=flat\&logo=google-maps\&logoColor=white)
![Firebase](https://img.shields.io/badge/Firebase-FFCA28?style=flat\&logo=firebase\&logoColor=black)
![Estado](https://img.shields.io/badge/Estado-Alpha-purple)

---

## Funcionalidades

| Función                      | Descripción                                                    |
| ---------------------------- | -------------------------------------------------------------- |
| **Mapa interactivo**         | Visualiza la ruta y sus puntos directamente sobre Google Maps. |
| **Múltiples paradas**        | Permite definir origen, paradas intermedias y destino.         |
| **Búsqueda por dirección**   | Convierte direcciones en coordenadas y calcula la ruta.        |
| **Selección en el mapa**     | Permite añadir puntos tocando directamente el mapa.            |
| **Seguimiento GPS**          | Muestra la ubicación del usuario mientras sigue una ruta.      |
| **Recálculo de ruta**        | Recalcula el recorrido cuando el usuario se desvía.            |
| **Información del trayecto** | Muestra distancia, duración y distancia restante.              |
| **Deshacer y limpiar**       | Permite eliminar el último punto o reiniciar la ruta.          |
| **Rutas guardadas**          | Guarda rutas para consultarlas posteriormente.                 |
| **Favoritos**                | Guarda lugares frecuentes.                                     |
| **Rutas rápidas**            | Incluye rutas predefinidas de Ida y Vuelta.                    |

---

## Tecnologías

* **Kotlin** — lenguaje principal.
* **Jetpack Compose** — interfaz de usuario.
* **Android SDK** — plataforma de destino.
* **Google Maps SDK for Android** — mapa y visualización de rutas.
* **Google Geocoding API** — conversión de direcciones a coordenadas.
* **Google Directions API** — cálculo de rutas y paradas.
* **Fused Location Provider** — ubicación GPS en tiempo real.
* **Firebase Authentication** — autenticación anónima.
* **Cloud Firestore** — almacenamiento de rutas y favoritos.

---

## Cómo funciona

RutaFácil tiene dos formas de crear una ruta.

En el **modo mapa**, puedes tocar diferentes puntos para crear el recorrido. Cuando hay suficientes puntos, la aplicación calcula la ruta y la muestra sobre el mapa.

En el **modo texto**, puedes introducir las direcciones de cada punto. La aplicación las convierte en coordenadas y calcula el recorrido completo.

Al iniciar el seguimiento, la aplicación muestra la posición actual y el progreso de la ruta. Si detecta que el usuario se ha alejado del recorrido, puede calcular una nueva ruta desde su posición.

---

## Rutas y favoritos

La sección de lista permite consultar rutas guardadas y lugares favoritos.

También incluye rutas rápidas de **Ida** y **Vuelta**, que permiten cargar determinados puntos sin tener que introducirlos nuevamente.

Los datos se almacenan mediante Firebase Authentication y Cloud Firestore.

---

## Galería

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/11b19284-2d10-45e1-8208-4ba5b1e5e717" width="220"></td>
    <td><img src="https://github.com/user-attachments/assets/6667f0af-e1e5-43e8-b9ca-29146e4f723f" width="220"></td>
    <td><img src="https://github.com/user-attachments/assets/6d78f476-b497-45fd-9c2d-3aca33ff4103" width="220"></td>
  </tr>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/a6a56883-ff6a-477c-a82a-947532dcd6c2" width="220"></td>
    <td><img src="https://github.com/user-attachments/assets/02160d3f-83eb-4eaa-89cd-55a34358d36e" width="220"></td>
    <td><img src="https://github.com/user-attachments/assets/8db6e5cf-3c29-4106-8471-8c19f0004ce2" width="220"></td>
  </tr>
</table>

---

## Requisitos

* Android 7.0 (API 24) o superior.
* Google Play Services instalado.

---

## Autor

Proyecto desarrollado como una aplicación Android para explorar navegación, geolocalización, APIs de Google, Jetpack Compose y Firebase.


