package app.clipper.drive

import android.content.Context
import app.clipper.data.ProjectStore
import app.clipper.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Sequential resumable uploads with durable IDs and session offsets queried from Drive. */
class DriveUploader(private val context: Context, private val store: ProjectStore) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun execute(build: (String) -> Request): Response {
        var last: IOException? = null
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            checkNetworkPolicy()
            try {
                val response = client.newCall(build(DriveAuth.accessToken(context))).execute()
                if (response.code == 401) {
                    response.close(); DriveAuth.clear()
                    error("Drive authorization expired. Reconnect in Settings and resume. Your local clips are safe.")
                }
                if ((response.code == 429 || response.code in 500..599) && attempt < 3) {
                    response.close(); delay(1000L shl attempt); continue
                }
                return response
            } catch (e: IOException) { last = e; if (attempt < 3) delay(1000L shl attempt) }
        }
        throw last ?: IOException("Drive is unavailable. Check your connection and resume.")
    }
    private suspend fun generateId(): String = execute { token ->
        Request.Builder().url("https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files")
            .header("Authorization", "Bearer $token").build()
    }.use { response -> checkSuccess(response); JSONObject(response.body!!.string()).getJSONArray("ids").getString(0) }

    suspend fun ensureFolder(id: String): String {
        var project = store.get(id)
        if (project.driveFolderCreated) return project.driveFolderId!!
        if (project.driveFolderId == null) {
            val folderId = generateId()
            store.update(id) { it.copy(driveFolderId = folderId) }
            project = store.get(id)
        }
        val folderId = project.driveFolderId!!
        val body = JSONObject().put("id", folderId).put("name", "${ClipPlanner.safeName(project.source.name.substringBeforeLast('.'))} · ${project.id.take(8)}")
            .put("mimeType", "application/vnd.google-apps.folder").toString().toRequestBody(jsonType)
        execute { token -> Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $token").post(body).build() }.use { response ->
            // Pre-generated IDs make a repeated creation safe after a lost response.
            if (response.code != 409) checkSuccess(response)
        }
        store.update(id) { it.copy(driveFolderCreated = true) }
        return folderId
    }

    suspend fun uploadClip(projectId: String, index: Int, file: File, onProgress: (Int) -> Unit) {
        val folder = ensureFolder(projectId)
        var clip = store.get(projectId).clips.first { it.index == index }
        if (clip.driveId == null) {
            val driveId = generateId()
            store.updateClip(projectId, index) { it.copy(driveId = driveId) }
            clip = store.get(projectId).clips.first { it.index == index }
        }
        val driveId = clip.driveId!!
        if (fileExists(driveId, file.length())) return
        var session = clip.uploadSession
        var offset = 0L
        if (session != null) {
            val state = querySession(session, file.length())
            if (state == -2L) return
            if (state < 0) session = null else offset = state
        }
        if (session == null) {
            val metadata = JSONObject().put("id", driveId).put("name", clip.fileName)
                .put("parents", org.json.JSONArray().put(folder))
                .put("description", "Clip ${clip.index}: ${clip.startMs}–${clip.endMs} ms. Created with Clipper.")
            execute { token -> Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id")
                .header("Authorization", "Bearer $token").header("X-Upload-Content-Type", "video/mp4")
                .header("X-Upload-Content-Length", file.length().toString()).post(metadata.toString().toRequestBody(jsonType)).build()
            }.use { response ->
                if (response.code == 409 && fileExists(driveId, file.length())) return
                checkSuccess(response)
                session = response.header("Location") ?: error("Drive did not provide an upload session.")
                validateSession(session!!)
            }
            store.updateClip(projectId, index) { it.copy(uploadSession = session) }
        }
        val url = session!!
        validateSession(url)
        RandomAccessFile(file, "r").use { input ->
            while (offset < file.length()) {
                currentCoroutineContext().ensureActive()
                checkNetworkPolicy()
                val bytes = ByteArray(minOf(8L * 1024 * 1024, file.length() - offset).toInt())
                input.seek(offset); input.readFully(bytes)
                val start = offset
                // Do not blindly retry chunk PUTs after a lost response: ask Drive for its offset.
                val request = Request.Builder().url(url).header("Authorization", "Bearer ${DriveAuth.accessToken(context)}")
                    .header("Content-Range", "bytes $start-${start + bytes.size - 1}/${file.length()}")
                    .put(bytes.toRequestBody("video/mp4".toMediaType())).build()
                var recover = false
                try {
                    client.newCall(request).execute().use { response ->
                        when {
                            response.code in 200..299 -> offset = file.length()
                            response.code == 308 -> {
                                val next = nextOffset(response)
                                check(next > offset && next <= file.length()) { "Drive returned an invalid upload offset. Resume to retry." }
                                offset = next
                            }
                            response.code == 429 || response.code >= 500 -> recover = true
                            else -> checkSuccess(response)
                        }
                    }
                } catch (_: IOException) { recover = true }
                if (recover) {
                    delay(2000)
                    val next = querySession(url, file.length())
                    if (next == -2L) offset = file.length()
                    else if (next > offset && next <= file.length()) offset = next
                    else error("Upload interrupted. Resume to continue from Drive's saved position.")
                }
                onProgress((offset * 100 / file.length()).toInt())
            }
        }
        store.updateClip(projectId, index) { it.copy(uploadSession = null) }
    }

    suspend fun uploadSubtitle(projectId: String, index: Int, file: File) {
        val clip = store.get(projectId).clips.first { it.index == index }
        var id = clip.subtitleDriveId
        if (id == null) { id = generateId(); store.updateClip(projectId, index) { it.copy(subtitleDriveId = id) } }
        if (fileExists(id, file.length())) return
        val metadata = JSONObject().put("id", id).put("name", file.name).put("parents", org.json.JSONArray().put(ensureFolder(projectId)))
        val body = MultipartBody.Builder("clipper-subtitle-${projectId}").setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(jsonType))
            .addPart(file.readBytes().toRequestBody("application/x-subrip".toMediaType())).build()
        execute { token -> Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .header("Authorization", "Bearer $token").post(body).build() }.use { if (it.code != 409) checkSuccess(it) }
    }

    private suspend fun fileExists(id: String, size: Long): Boolean = execute { token ->
        Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id?fields=id,size,trashed")
            .header("Authorization", "Bearer $token").build()
    }.use { response ->
        if (response.code == 404) false else {
            checkSuccess(response)
            val result = JSONObject(response.body!!.string())
            check(!result.optBoolean("trashed")) { "A destination file was moved to Drive trash. Restore it before resuming." }
            check(result.optLong("size", -1) == size) { "A Drive file has an unexpected size. Start a new project to avoid overwriting it." }
            true
        }
    }
    private suspend fun querySession(url: String, size: Long): Long {
        validateSession(url)
        return execute { token -> Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$size").put(ByteArray(0).toRequestBody(null)).build() }.use { response ->
            when (response.code) {
                200, 201 -> -2L
                308 -> nextOffset(response)
                404, 410 -> -1L
                else -> { checkSuccess(response); -1L }
            }
        }
    }
    private fun nextOffset(response: Response): Long = response.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0
    private fun checkSuccess(response: Response) {
        if (response.isSuccessful) return
        if (response.code == 401) { DriveAuth.clear(); error("Reconnect Google Drive in Settings, then resume.") }
        if (response.code == 403) error("Drive refused the upload. Check available storage and account permission, then resume.")
        error("Drive request failed (${response.code}). Your clips are kept locally; resume to retry.")
    }
    private fun validateSession(url: String) {
        val parsed = java.net.URI(url)
        require(parsed.scheme == "https" && parsed.host == "www.googleapis.com" && parsed.port in listOf(-1, 443)
            && parsed.userInfo == null && parsed.path.startsWith("/upload/drive/v3/files")) { "Rejected an unexpected Drive upload URL." }
    }
    private fun checkNetworkPolicy() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = manager.activeNetwork ?: error("No connection. Resume after reconnecting; local clips are safe.")
        val capabilities = manager.getNetworkCapabilities(network)
        check(capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) { "This network has no internet connection. Resume when connected." }
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("wifiOnly", true)) {
            check(!manager.isActiveNetworkMetered) { "Uploads are limited to unmetered networks. Connect to Wi-Fi, or change this setting, then resume." }
        }
    }
}
