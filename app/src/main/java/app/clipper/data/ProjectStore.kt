package app.clipper.data

import android.content.Context
import android.util.AtomicFile
import app.clipper.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "exports").apply { mkdirs() }
    private val manifest = AtomicFile(File(context.noBackupFilesDir, "projects-v1.json"))
    private val gson = Gson()
    var loadWarning: String? = null
        private set
    private val mutable = MutableStateFlow(load())
    val projects = mutable.asStateFlow()

    private fun load(): List<Project> = try {
        if (!manifest.baseFile.exists()) emptyList() else manifest.openRead().bufferedReader().use {
            gson.fromJson<List<Project>>(it, object : TypeToken<List<Project>>() {}.type).orEmpty()
                .map { project -> if (project.status == JobStatus.RUNNING) project.copy(status = JobStatus.PAUSED, message = "Interrupted. Tap resume to continue.") else project }
        }
    } catch (_: Exception) {
        // Preserve the damaged manifest for recovery instead of silently overwriting it.
        runCatching { manifest.baseFile.copyTo(File(manifest.baseFile.parentFile, "projects-recovery-${System.currentTimeMillis()}.json"), overwrite = false) }
        loadWarning = "The saved project list could not be read. Existing exported files were kept for recovery."
        emptyList()
    }

    @Synchronized fun add(project: Project) = save(listOf(project) + mutable.value)
    @Synchronized fun get(id: String): Project = mutable.value.first { it.id == id }
    @Synchronized fun update(id: String, change: (Project) -> Project) {
        save(mutable.value.map { if (it.id == id) change(it) else it })
    }
    @Synchronized fun updateClip(id: String, index: Int, change: (Clip) -> Clip) = update(id) { project ->
        project.copy(clips = project.clips.map { if (it.index == index) change(it) else it })
    }
    @Synchronized fun delete(id: String) {
        require(get(id).status != JobStatus.RUNNING) { "Pause the project before deleting it." }
        val directory = directory(id)
        save(mutable.value.filterNot { it.id == id })
        directory.deleteRecursively()
    }
    private fun save(projects: List<Project>) {
        val stream = manifest.startWrite()
        try {
            stream.write(gson.toJson(projects).toByteArray(Charsets.UTF_8))
            manifest.finishWrite(stream)
            mutable.value = projects
        } catch (error: Exception) { manifest.failWrite(stream); throw error }
    }
    fun directory(id: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id))
        return File(root, id).apply { mkdirs() }
    }
    fun clipFile(id: String, clip: Clip): File {
        val directory = directory(id)
        val file = File(directory, clip.fileName)
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        return file
    }
    fun cues(id: String): List<Cue> {
        val file = File(directory(id), "subtitles.srt")
        return if (file.exists()) Subtitles.parseSrt(file.readText(), get(id).source.durationMs) else emptyList()
    }
    fun saveCues(id: String, cues: List<Cue>) {
        val file = AtomicFile(File(directory(id), "subtitles.srt"))
        val stream = file.startWrite()
        try { stream.write(Subtitles.srt(cues).toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        update(id) { it.copy(subtitlesReady = true) }
    }
}
