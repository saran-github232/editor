package app.clipper.speech

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class SpeechLanguage(val label: String, val folder: String) {
    ENGLISH("English (US)", "vosk-model-small-en-us-0.15"),
    HINDI("Hindi", "vosk-model-small-hi-0.22")
}

class ModelInstaller(private val context: Context) {
    fun language(): SpeechLanguage = SpeechLanguage.entries.firstOrNull {
        it.name == context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("speechLanguage", null)
    } ?: SpeechLanguage.ENGLISH
    fun select(language: SpeechLanguage) { context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("speechLanguage", language.name).apply() }
    fun modelDirectory(): File = File(context.noBackupFilesDir, "models/${language().folder}")
    fun installed(): Boolean = File(modelDirectory(), "am/final.mdl").exists() && File(modelDirectory(), ".complete").exists()

    suspend fun install(onProgress: (String) -> Unit) {
        val language = language()
        val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        val staging = File(root, "staging").apply { deleteRecursively(); mkdirs() }
        val archive = File(context.cacheDir, "speech-model.zip")
        val client = OkHttpClient.Builder().followRedirects(false).connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            val request = Request.Builder().url("https://alphacephei.com/vosk/models/${language.folder}.zip").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Language model download failed (${response.code}). Try again on Wi-Fi." }
                val body = response.body ?: error("Empty model download.")
                require(body.contentLength() <= 150L * 1024 * 1024) { "Model download exceeds the size limit." }
                body.byteStream().use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L; var lastMb = -1L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= 150L * 1024 * 1024) { "Model download exceeds the size limit." }
                        output.write(buffer, 0, count)
                        if (total / 1_048_576 != lastMb) { lastMb = total / 1_048_576; onProgress("Downloading ${language.label} model · $lastMb MB") }
                    }
                } }
            }
            onProgress("Installing language model…")
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var total = 0L; var count = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(++count <= 10_000) { "Too many files in language model." }
                    val target = File(staging, entry.name).canonicalFile
                    require(target.toPath().startsWith(staging.canonicalFile.toPath()) && target != staging.canonicalFile) { "Invalid model archive path." }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile!!.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(buffer); if (n < 0) break
                                total += n; require(total <= 400L * 1024 * 1024) { "Expanded model exceeds the size limit." }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val expanded = File(staging, language.folder)
            require(File(expanded, "am/final.mdl").isFile && File(expanded, "conf/model.conf").isFile) { "The downloaded model is incomplete." }
            val destination = File(root, language.folder)
            if (destination.exists()) destination.deleteRecursively()
            check(expanded.renameTo(destination)) { "Could not install the language model. Check free storage." }
            File(destination, ".complete").writeText(language.name)
        } finally { archive.delete(); staging.deleteRecursively(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
