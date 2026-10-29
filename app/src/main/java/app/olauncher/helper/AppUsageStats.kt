package app.olauncher.helper

class AppUsageStats(
    val lastTimeUsedMillis: Long,
    val totalTimeInForegroundMillis: Long,
    val lastTimeForegroundServiceUsedMillis: Long,
    val totalTimeForegroundServiceUsedMillis: Long,
)

class AppUsageStatsBucket {
    var startMillis: Long = 0L
    var endMillis: Long = 0L
    var totalTime: Long = 0L

    fun addTotalTime() {
        this.totalTime += endMillis - startMillis
    }
}