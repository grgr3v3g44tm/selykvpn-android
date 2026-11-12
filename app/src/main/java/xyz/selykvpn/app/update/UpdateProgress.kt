package xyz.selykvpn.app.update

/** Download state for an in-app APK update; [fraction] is null when Content-Length is unknown. */
data class UpdateProgress(
    val fraction: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
)
