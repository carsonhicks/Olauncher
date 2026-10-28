package app.olauncher.helper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.olauncher.data.Prefs
import kotlinx.coroutines.coroutineScope

class WeatherWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val prefs = Prefs(applicationContext)

    override suspend fun doWork(): Result = coroutineScope {
        if (applicationContext.hasLocationPermission().not())
            return@coroutineScope Result.success()

        val location = getDeviceLocation(applicationContext) ?: return@coroutineScope Result.retry()
        val weather = fetchWeather(location.latitude, location.longitude) ?: return@coroutineScope Result.retry()
        val city = getCityName(applicationContext, location.latitude, location.longitude)

        prefs.weatherTempF = weather.tempF
        prefs.weatherCode = weather.code
        prefs.weatherIsDay = weather.isDay
        prefs.weatherHighF = weather.highF
        prefs.weatherLowF = weather.lowF
        if (city != null) prefs.weatherCity = city
        prefs.weatherUpdatedAt = System.currentTimeMillis()

        Result.success()
    }
}
