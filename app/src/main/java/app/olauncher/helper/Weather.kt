package app.olauncher.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import app.olauncher.data.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

data class WeatherData(val tempF: Int, val code: Int, val isDay: Boolean, val highF: Int, val lowF: Int)

// What HomeFragment actually renders: an emoji glyph, a temperature, today's high/low, and a city name.
data class WeatherDisplay(val emoji: String, val tempF: Int, val highF: Int, val lowF: Int, val city: String?)

data class WeatherAppInfo(val label: String, val packageName: String, val activityClassName: String)

// Apps that declare themselves as weather apps via the standard "default apps" category,
// the same mechanism Android's own Settings > Default apps screen uses.
fun Context.getWeatherApps(): List<WeatherAppInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_WEATHER)
    return packageManager.queryIntentActivities(intent, 0)
        .mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            WeatherAppInfo(
                label = resolveInfo.loadLabel(packageManager).toString(),
                packageName = activityInfo.packageName,
                activityClassName = activityInfo.name
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

// Monochrome (text-presentation) weather glyphs, matching the launcher's black & white look.
// Each symbol is followed by U+FE0E (VARIATION SELECTOR-15) to request the outline/text
// presentation instead of a full-color emoji glyph.
// WMO weather codes: https://open-meteo.com/en/docs
fun weatherEmoji(code: Int, isDay: Boolean): String {
    val vs = "︎"
    val sun = "☀$vs"
    val moon = "☾$vs"
    val sunBehindCloud = "⛅$vs"
    val cloud = "☁$vs"
    val umbrella = "☔$vs"
    val snowflake = "❄$vs"
    val thunderCloud = "⛈$vs"

    return when (code) {
        0, 1 -> if (isDay) sun else moon
        2 -> sunBehindCloud
        3, 45, 48 -> cloud
        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        80, 81, 82 -> umbrella
        71, 73, 75, 77, 85, 86 -> snowflake
        95, 96, 99 -> thunderCloud
        else -> cloud
    }
}

suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherData? = withContext(Dispatchers.IO) {
    try {
        val url = URL(
            "${Constants.URL_OPEN_METEO_FORECAST}?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                "&temperature_unit=fahrenheit&timezone=auto"
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.doInput = true
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        WeatherData(
            tempF = current.getDouble("temperature_2m").toInt(),
            code = current.getInt("weather_code"),
            isDay = current.getInt("is_day") == 1,
            highF = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt(),
            lowF = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// Reverse-geocodes a location into a city name using the platform's Geocoder, which needs
// no extra dependency or API key. Falls back through broader regions if no locality is found.
suspend fun getCityName(context: Context, latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
    try {
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
        val address = addresses?.firstOrNull() ?: return@withContext null
        address.locality ?: address.subAdminArea ?: address.adminArea
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@SuppressLint("MissingPermission")
suspend fun getDeviceLocation(context: Context): Location? {
    if (context.hasLocationPermission().not()) return null

    return withContext(Dispatchers.IO) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        val lastKnown = providers
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        // A recent fix is good enough; no need to wait on a fresh GPS/network round trip.
        if (lastKnown != null && lastKnown.time.hasBeenMinutes(30).not())
            return@withContext lastKnown

        val freshLocation = withTimeoutOrNull(10_000) {
            requestSingleLocationUpdate(locationManager, providers)
        }

        freshLocation ?: lastKnown
    }
}

@SuppressLint("MissingPermission")
private suspend fun requestSingleLocationUpdate(
    locationManager: LocationManager,
    providers: List<String>
): Location? = suspendCancellableCoroutine { continuation ->
    val provider = providers.firstOrNull()
    if (provider == null) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
    }

    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            locationManager.removeUpdates(this)
            if (continuation.isActive) continuation.resume(location)
        }
    }

    continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
}
