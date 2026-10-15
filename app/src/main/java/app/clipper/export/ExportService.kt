package app.clipper.export

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import app.clipper.ClipperApp
import app.clipper.R
import app.clipper.drive.DriveUploader
import app.clipper.media.ExactExporter
import app.clipper.media.OriginalCopyExporter
import app.clipper.model.*
import app.clipper.speech.ModelInstaller
import app.clipper.speech.Transcriber
import app.clipper.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

@UnstableApi
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var operation: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var projectId: String? = null
    private val store get() = (application as ClipperApp).store
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) { operation?.cancel(); return START_NOT_STICKY }
        if (operation?.isActive == true) return START_NOT_STICKY
        val action = intent?.action ?: return START_NOT_STICKY
        val id = intent.getStringExtra("project")
        projectId = id
        activeId.value = id
        activity.value = "Starting…"
        try { foreground("Preparing your video", action == INSTALL_MODEL) }
        catch (e: Exception) {
            if (id != null) store.update(id) { it.copy(status = JobStatus.PAUSED, message = "Open Clipper and tap resume to start this job.") }
            activity.value = ""; activeId.value = null; stopSelf(); return START_NOT_STICKY
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Clipper:Export").apply { acquire(6 * 60 * 60_000L) }
        operation = scope.launch {
            try {
                if (action == INSTALL_MODEL) {
                    ModelInstaller(this@ExportService).install { updateNotification(it) }
                    lastMessage.value = "Language model installed. Automatic subtitles are ready."
                } else {
                    requireNotNull(id)
                    store.update(id) { it.copy(status = JobStatus.RUNNING, message = "Preparing…") }
                    val project = store.get(id)
                    project.options.validate()
                    if (project.options.autoSubtitles && !project.subtitlesReady) {
                        status(id, "Listening to your video…", 0)
                        val cues = Transcriber(this@ExportService).transcribe(project.source) { progress -> status(id, "Generating subtitles · $progress%", progress) }
                        store.saveCues(id, cues)
                    }
                    if (action == TRANSCRIBE) {
                        store.update(id) { it.copy(status = JobStatus.PAUSED, message = "Subtitles ready. Review them before exporting.", progress = 0) }
                    } else export(id)
                }
            } catch (cancelled: CancellationException) {
                if (id != null) store.update(id) { it.copy(status = JobStatus.PAUSED, message = "Paused. Completed clips are saved; resume when ready.") }
                lastMessage.value = "Paused"
            } catch (error: Exception) {
                val message = error.message?.take(400) ?: "Export failed. Try again or choose another source video."
                if (id != null) store.update(id) { it.copy(status = JobStatus.FAILED, message = message) }
                lastMessage.value = message
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock = null
                withContext(NonCancellable + Dispatchers.Main) {
                    activity.value = ""; activeId.value = null
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun export(id: String) {
        var project = store.get(id)
        val copy = OriginalCopyExporter(this)
        if (project.options.mode == CutMode.ORIGINAL_COPY && project.clips.none { it.complete }) {
            val aligned = copy.alignedClips(project)
            store.update(id) { it.copy(clips = aligned) }
            project = store.get(id)
        }
        val cues = store.cues(id)
        val uploader = DriveUploader(this, store)
        for (initial in project.clips) {
            currentCoroutineContext().ensureActive()
            var clip = store.get(id).clips.first { it.index == initial.index }
            val finalFile = store.clipFile(id, clip)
            val temporary = File(finalFile.parentFile, finalFile.name + ".partial.mp4")
            if (!clip.complete || !finalFile.exists() || finalFile.length() == 0L) {
                status(id, "Exporting part ${clip.index} of ${project.clips.size}", (clip.index - 1) * 100 / project.clips.size)
                val expected = (maxOf(project.source.bitrate, 8_000_000).toLong() * (clip.endMs - clip.startMs) / 8000)
                check(StatFs(filesDir.path).availableBytes > expected + 200L * 1024 * 1024) { "Storage is running low. Free some space, then resume. Completed clips are safe." }
                temporary.delete()
                try {
                    if (project.options.mode == CutMode.ORIGINAL_COPY) copy.export(project, clip, temporary)
                    else ExactExporter(this).export(project, clip, Subtitles.forClip(cues, clip.startMs, clip.endMs), temporary)
                    currentCoroutineContext().ensureActive()
                    check(temporary.length() > 0) { "The encoder produced an empty clip." }
                    verifyDimensions(temporary, project)
                    if (finalFile.exists()) finalFile.delete()
                    check(temporary.renameTo(finalFile)) { "Could not save this clip. Check free storage." }
                    store.updateClip(id, clip.index) { it.copy(complete = true) }
                } finally { temporary.delete() }
            }
            val clipCues = Subtitles.forClip(cues, clip.startMs, clip.endMs)
            val sidecar = File(finalFile.parentFile, finalFile.nameWithoutExtension + ".srt")
            if (clipCues.isNotEmpty()) sidecar.writeText(Subtitles.srt(clipCues))
            clip = store.get(id).clips.first { it.index == clip.index }
            if (project.options.uploadToDrive && !clip.driveUploaded) {
                withContext(Dispatchers.Main) { foreground("Uploading part ${clip.index}", true) }
                uploader.uploadClip(id, clip.index, finalFile) { percent ->
                    status(id, "Uploading part ${clip.index} of ${project.clips.size} · $percent%", (clip.index - 1) * 100 / project.clips.size)
                }
                if (sidecar.exists()) uploader.uploadSubtitle(id, clip.index, sidecar)
                store.updateClip(id, clip.index) { it.copy(driveUploaded = true, uploadSession = null) }
                withContext(Dispatchers.Main) { foreground("Preparing the next clip", false) }
            }
        }
        store.update(id) { it.copy(status = JobStatus.COMPLETE, progress = 100, message = if (it.options.uploadToDrive) "All clips saved locally and uploaded to Drive." else "All clips ready. Save or share them from your library.") }
    }

    private fun verifyDimensions(file: File, project: Project) {
        android.media.MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(file.absolutePath)
            val w = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val expected = ClipPlanner.outputSize(project.source.width, project.source.height, project.options.shortEdge)
            check(minOf(w, h) >= minOf(expected.first, expected.second) && maxOf(w, h) >= maxOf(expected.first, expected.second)) {
                "The device returned a lower resolution than requested. The clip was not saved. Try Original copy."
            }
        }
    }
    private fun status(id: String, message: String, progress: Int) {
        store.update(id) { it.copy(message = message, progress = progress) }; updateNotification(message)
    }
    private fun updateNotification(message: String) {
        activity.value = message
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(message))
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_clipper).setContentTitle("Clipper")
            .setContentText(message).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(0, "Pause", pause).setProgress(0, 0, true).build()
    }
    private fun foreground(message: String, upload: Boolean) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL, "Video exports", NotificationManager.IMPORTANCE_LOW))
        val type = when {
            upload -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(message), type)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { operation?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); if (wakeLock?.isHeld == true) wakeLock?.release(); super.onDestroy() }

    companion object {
        const val EXPORT = "app.clipper.EXPORT"
        const val TRANSCRIBE = "app.clipper.TRANSCRIBE"
        const val INSTALL_MODEL = "app.clipper.INSTALL_MODEL"
        const val PAUSE = "app.clipper.PAUSE"
        private const val CHANNEL = "exports"
        private const val NOTIFICATION_ID = 7
        private val activity = MutableStateFlow("")
        val activityState = activity.asStateFlow()
        val activeId = MutableStateFlow<String?>(null)
        val lastMessage = MutableStateFlow("")
    }
}
