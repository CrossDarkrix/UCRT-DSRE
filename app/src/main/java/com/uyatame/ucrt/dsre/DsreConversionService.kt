package com.uyatame.ucrt.dsre

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.uyatame.ucrt.MainActivity
import com.uyatame.ucrt.R
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DsreServiceJob(val uri: String, val name: String)
data class DsreServiceRequest(
    val jobs: List<DsreServiceJob>, val outputTree: String,
    val parameters: DsreParameters, val format: String,
)
data class DsreServiceJobState(
    val uri: String, val name: String, val state: String = "WAITING",
    val progress: Float = 0f, val stage: String = "Waiting", val error: String? = null,
)
data class DsreServiceState(
    val running: Boolean = false, val jobs: List<DsreServiceJobState> = emptyList(),
    val currentIndex: Int = -1, val message: String? = null,
)

object DsreConversionCoordinator {
    private val stateMutable = MutableStateFlow(DsreServiceState())
    val state: StateFlow<DsreServiceState> = stateMutable.asStateFlow()
    @Volatile internal var pendingRequest: DsreServiceRequest? = null
    internal val cancelRequested = AtomicBoolean(false)

    fun start(context: Context, request: DsreServiceRequest): Boolean {
        if (stateMutable.value.running) return false
        pendingRequest = request
        cancelRequested.set(false)
        stateMutable.value = DsreServiceState(
            running = true,
            jobs = request.jobs.map { DsreServiceJobState(it.uri, it.name) },
            message = "Batch conversion started",
        )
        ContextCompat.startForegroundService(
            context,
            Intent(context, DsreConversionService::class.java).setAction(DsreConversionService.ACTION_START),
        )
        return true
    }

    fun cancel(context: Context) {
        cancelRequested.set(true)
        DsreFileConverter(context.applicationContext).cancel()
        context.startService(Intent(context, DsreConversionService::class.java).setAction(DsreConversionService.ACTION_CANCEL))
    }

    internal fun update(transform: (DsreServiceState) -> DsreServiceState) {
        stateMutable.value = transform(stateMutable.value)
    }
}

class DsreConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var converter: DsreFileConverter? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                DsreConversionCoordinator.cancelRequested.set(true)
                converter?.cancel()
                updateNotification("Cancelling DSRE conversion", 0, 0, true)
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification("Preparing DSRE conversion", 0, 0, false))
                val request = DsreConversionCoordinator.pendingRequest
                if (request == null) stopCompleted("No pending conversion request")
                else scope.launch { runBatch(request) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        converter?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runBatch(request: DsreServiceRequest) {
        val localConverter = DsreFileConverter(applicationContext)
        converter = localConverter
        val tree = Uri.parse(request.outputTree)
        try {
            request.jobs.forEachIndexed { index, job ->
                if (DsreConversionCoordinator.cancelRequested.get()) throw CancellationException("Cancelled")
                updateJob(index, "PROCESSING", 0f, "Preparing", null)
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val directory = DocumentFile.fromTreeUri(applicationContext, tree)
                            ?: throw IOException("Unable to open output folder")
                        if (!directory.canWrite()) throw IOException("Output folder is not writable")
                        val output = createUniqueOutput(directory, job.name, request.format)
                        localConverter.convert(Uri.parse(job.uri), output.uri, request.parameters, request.format) { progress ->
                            updateJob(index, "PROCESSING", progress.fraction, progress.stage, null)
                            updateNotification(
                                "${index + 1}/${request.jobs.size} ${job.name}",
                                (progress.fraction * 100).toInt(), 100, false,
                            )
                        }
                    }
                }
                result.fold(
                    onSuccess = { updateJob(index, "COMPLETED", 1f, "Completed", null) },
                    onFailure = { error ->
                        val cancelled = DsreConversionCoordinator.cancelRequested.get() || error is CancellationException ||
                            error.message?.contains("cancel", true) == true
                        updateJob(index, if (cancelled) "CANCELLED" else "FAILED", 0f,
                            if (cancelled) "Cancelled" else "Failed", if (cancelled) null else error.message)
                        if (cancelled) throw CancellationException("Cancelled")
                    },
                )
            }
            stopCompleted("DSRE conversion completed")
        } catch (_: CancellationException) {
            stopCompleted("DSRE conversion cancelled")
        } catch (error: Throwable) {
            stopCompleted("DSRE conversion failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            converter = null
            DsreConversionCoordinator.pendingRequest = null
        }
    }

    private fun updateJob(index: Int, state: String, progress: Float, stage: String, error: String?) {
        DsreConversionCoordinator.update { current ->
            val jobs = current.jobs.toMutableList()
            if (index in jobs.indices) jobs[index] = jobs[index].copy(
                state = state, progress = progress.coerceIn(0f, 1f), stage = stage, error = error,
            )
            current.copy(running = true, jobs = jobs, currentIndex = index)
        }
    }

    private fun stopCompleted(message: String) {
        DsreConversionCoordinator.update { it.copy(running = false, message = message) }
        updateNotification(message, 100, 100, true)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createUniqueOutput(directory: DocumentFile, sourceName: String, format: String): DocumentFile {
        val extension = when (format) { "ALAC" -> "m4a"; "FLAC" -> "flac"; "MP3" -> "mp3"; else -> "wav" }
        val mime = when (format) { "ALAC" -> "audio/mp4"; "FLAC" -> "audio/flac"; "MP3" -> "audio/mpeg"; else -> "audio/wav" }
        val stem = sourceName.substringBeforeLast('.').ifBlank { "audio" }
        var candidate = "$stem-DSRE.$extension"
        var suffix = 2
        val existing = directory.listFiles().mapNotNull { it.name?.lowercase(Locale.ROOT) }.toHashSet()
        while (candidate.lowercase(Locale.ROOT) in existing) candidate = "$stem-DSRE-$suffix.${extension}".also { suffix++ }
        return directory.createFile(mime, candidate) ?: throw IOException("Unable to create output: $candidate")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "DSRE conversion", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String, progress: Int, max: Int, completed: Boolean): android.app.Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancelIntent = PendingIntent.getService(this, 1, Intent(this, DsreConversionService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ucrt2_light)
            .setContentTitle("UCRT DSRE")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(!completed)
            .setProgress(max, progress.coerceIn(0, max.coerceAtLeast(0)), max <= 0)
            .apply { if (!completed) addAction(0, "Cancel", cancelIntent) }
            .build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int, completed: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, progress, max, completed))
    }

    companion object {
        const val ACTION_START = "com.uyatame.ucrt.dsre.START"
        const val ACTION_CANCEL = "com.uyatame.ucrt.dsre.CANCEL"
        private const val CHANNEL_ID = "dsre_conversion"
        private const val NOTIFICATION_ID = 7302
    }
}
