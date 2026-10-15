package app.clipper.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.clipper.ClipperApp
import app.clipper.R
import app.clipper.databinding.ActivityMainBinding
import app.clipper.databinding.ItemProjectBinding
import app.clipper.drive.DriveAuth
import app.clipper.export.ExportService
import app.clipper.media.VideoInspector
import app.clipper.model.*
import app.clipper.speech.ModelInstaller
import app.clipper.speech.SpeechLanguage
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var ui: ActivityMainBinding
    private val store get() = (application as ClipperApp).store
    private val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var selected: VideoInfo? = null
    private var player: ExoPlayer? = null
    private var pendingSave: File? = null
    private var pendingFolder: String? = null
    private var exportAfterAuth: (() -> Unit)? = null
    private var busySaving = false
    private var previewDialog: androidx.appcompat.app.AlertDialog? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            ui.chooseVideo.isEnabled = false
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val info = withContext(Dispatchers.IO) { VideoInspector.inspect(this@MainActivity, uri) }
                selected = info; showSource(); updateSummary()
            } catch (e: Exception) { message(e.message ?: "This video could not be opened.") }
            finally { ui.chooseVideo.isEnabled = true }
        }
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val authorize = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val authorization = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
                DriveAuth.accept(authorization.accessToken ?: error("No Drive access token was returned."))
                refreshSettings(); exportAfterAuth?.invoke()
            } catch (e: Exception) { message("Drive could not connect. Try again; local export is still available.") }
        } else message("Drive was not connected. You can still export locally.")
        exportAfterAuth = null
    }
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri -> savePending(uri) }
    private val saveSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri -> savePending(uri) }
    private val saveFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val id = pendingFolder
        pendingFolder = null
        if (uri != null && id != null) lifecycleScope.launch { saveProjectFolder(id, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater); setContentView(ui.root)
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        pendingSave = savedInstanceState?.getString("pendingSave")?.let { File(it) }
        pendingFolder = savedInstanceState?.getString("pendingFolder")
        selected = savedInstanceState?.getString("source")?.let { Gson().fromJson(it, VideoInfo::class.java) }
        spinner(ui.cutMode, listOf("Exact cuts · supports captions", "Original copy · keyframe cuts"))
        spinner(ui.resolution, listOf("Original resolution", "420p", "720p · HD", "1080p · Full HD", "2160p · 4K"))
        spinner(ui.speechLanguage, SpeechLanguage.entries.map { it.label })
        ui.speechLanguage.setSelection(ModelInstaller(this).language().ordinal)
        ui.wifiOnly.isChecked = preferences.getBoolean("wifiOnly", true)
        ui.wifiOnly.setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean("wifiOnly", checked).apply() }
        ui.speechLanguage.onItemSelectedListener = selectedListener {
            if (ExportService.activityState.value.isNotEmpty()) {
                ui.speechLanguage.setSelection(ModelInstaller(this).language().ordinal)
            } else ModelInstaller(this).select(SpeechLanguage.entries[ui.speechLanguage.selectedItemPosition])
            refreshSettings()
        }
        ui.cutMode.onItemSelectedListener = selectedListener {
            if (ui.cutMode.selectedItemPosition == 1) {
                ui.resolution.setSelection(0); ui.captionText.setText(""); ui.partLabels.isChecked = false; ui.burnSubtitles.isChecked = false
            }
            val editing = ui.cutMode.selectedItemPosition == 0
            ui.resolution.isEnabled = editing; ui.captionText.isEnabled = editing
            ui.partLabels.isEnabled = editing; ui.burnSubtitles.isEnabled = editing; ui.captionCommand.isEnabled = editing
            updateSummary()
        }
        ui.resolution.onItemSelectedListener = selectedListener { updateSummary() }
        ui.lengthGroup.setOnCheckedStateChangeListener { _, _ -> updateSummary() }
        ui.captionText.doAfterTextChanged { updateSummary() }
        ui.partTemplate.doAfterTextChanged { updateSummary() }
        ui.captionPosition.setOnCheckedChangeListener { _, _ -> updateSummary() }
        ui.partLabels.setOnCheckedChangeListener { _, _ -> updateSummary() }
        ui.autoSubtitles.setOnCheckedChangeListener { _, _ -> updateSummary() }
        ui.navigation.setOnItemSelectedListener { item -> showPage(item.itemId); true }
        ui.chooseVideo.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        ui.startExport.setOnClickListener { createProject(false) }
        ui.reviewSubtitles.setOnClickListener { ui.autoSubtitles.isChecked = true; createProject(true) }
        ui.captionCommand.setOnClickListener { captionCommandDialog() }
        ui.connectDrive.setOnClickListener { connectDrive() }
        ui.disconnectDrive.setOnClickListener { disconnectDrive() }
        ui.installModel.setOnClickListener {
            if (ExportService.activityState.value.isNotBlank()) { message("Pause the current job before installing a language."); return@setOnClickListener }
            MaterialAlertDialogBuilder(this).setTitle("Download ${ModelInstaller(this).language().label}?")
                .setMessage("Downloads about 40–45 MB from the Vosk model library. Speech recognition then works offline. Mobile data charges may apply.")
                .setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ -> startServiceTask(ExportService.INSTALL_MODEL) }.show()
        }
        ui.activeBanner.setOnClickListener {
            if (ExportService.activeId.value != null) ui.navigation.selectedItemId = R.id.nav_library
            else MaterialAlertDialogBuilder(this).setTitle("Language download").setMessage(ExportService.activityState.value)
                .setPositiveButton("Keep downloading", null).setNegativeButton("Pause") { _, _ -> pause() }.show()
        }
        ui.privacyInfo.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Your video stays yours")
                .setMessage("Clipper stores selected video access, export settings, subtitle text and output files on this device. It does not collect analytics or upload videos for transcription.\n\nDrive is optional and uses access limited to files created or selected through this app. Tokens stay in memory. Disconnect revokes the app's Drive access, but does not delete uploaded files.\n\nExact cuts, captions and resizing re-encode video. Original copy preserves compressed audio/video samples, with cuts aligned to keyframes. HDR editing depends on your device. Clipper never silently lowers the requested resolution.\n\nThe speech model is downloaded from alphacephei.com. Your network address is visible to that server during the download.\n\nOnly edit content you have permission to use.")
                .setPositiveButton("Got it", null).show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { store.projects.collect { renderLibrary(it); refreshSettings() } }
                launch { ExportService.activityState.collect { activity ->
                    ui.activeBanner.isVisible = activity.isNotBlank(); ui.activeBanner.text = activity
                    ui.startExport.isEnabled = activity.isBlank(); ui.reviewSubtitles.isEnabled = activity.isBlank()
                    ui.installModel.isEnabled = activity.isBlank() && !ModelInstaller(this@MainActivity).installed()
                    ui.speechLanguage.isEnabled = activity.isBlank()
                    refreshSettings()
                } }
                launch { ExportService.lastMessage.collect { if (it.isNotBlank()) { message(it); ExportService.lastMessage.value = "" } } }
            }
        }
        showPage(savedInstanceState?.getInt("page") ?: R.id.nav_home)
        showSource(); updateSummary(); refreshSettings()
        store.loadWarning?.let { warning -> ui.root.post { message(warning) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source", selected?.let { Gson().toJson(it) })
        outState.putString("pendingSave", pendingSave?.absolutePath); outState.putString("pendingFolder", pendingFolder)
        outState.putInt("page", ui.navigation.selectedItemId)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); if (selected != null && player == null) showSource() }
    override fun onStop() { previewDialog?.dismiss(); player?.release(); player = null; ui.sourcePlayer.player = null; super.onStop() }

    private fun showPage(id: Int) {
        ui.studioPage.isVisible = id == R.id.nav_home
        ui.libraryPage.isVisible = id == R.id.nav_library
        ui.settingsPage.isVisible = id == R.id.nav_settings
        ui.pageScroll.scrollTo(0, 0)
        if (id != R.id.nav_home) player?.pause()
        refreshSettings()
    }
    private fun showSource() {
        val source = selected ?: return
        ui.selectionCard.isVisible = true
        ui.sourceName.text = source.name
        ui.sourceInfo.text = "${time(source.durationMs)}  ·  ${source.width} × ${source.height}${if (source.hdr) "  ·  HDR" else ""}\n${bytes(source.sizeBytes)} · original file"
        ui.chooseVideo.text = "Choose a different video"
        player?.release()
        player = ExoPlayer.Builder(this).build().also { p ->
            ui.sourcePlayer.player = p; p.setMediaItem(MediaItem.fromUri(source.uri)); p.prepare()
        }
    }
    private fun options() = ExportOptions(
        segmentSeconds = when (ui.lengthGroup.checkedChipId) { R.id.length90 -> 90; R.id.length120 -> 120; R.id.length150 -> 150; R.id.length180 -> 180; else -> 60 },
        mode = if (ui.cutMode.selectedItemPosition == 1) CutMode.ORIGINAL_COPY else CutMode.EXACT,
        shortEdge = listOf(0, 420, 720, 1080, 2160)[ui.resolution.selectedItemPosition.coerceAtLeast(0)],
        caption = ui.captionText.text.toString().trim(),
        captionPosition = if (ui.positionTop.isChecked) Position.TOP else Position.BOTTOM,
        partLabels = ui.partLabels.isChecked, partTemplate = ui.partTemplate.text.toString().ifBlank { "Part {part}" },
        autoSubtitles = ui.autoSubtitles.isChecked, burnSubtitles = ui.burnSubtitles.isChecked,
        uploadToDrive = ui.driveExport.isChecked
    )
    private fun updateSummary() {
        val options = options()
        ui.qualityNote.text = if (options.mode == CutMode.ORIGINAL_COPY)
            "Original compressed samples, with no re-encoding. Clip lengths follow nearby keyframes and can vary. No burned-in text or resizing."
        else "Exact timing with high-quality encoding. Text and resizing change the video, so the output is not lossless. No upscaling or silent resolution fallback."
        ui.partTemplate.isEnabled = options.partLabels
        ui.burnSubtitles.isVisible = options.autoSubtitles
        val source = selected
        if (source != null) {
            val clips = ClipPlanner.plan(source.durationMs, options.segmentSeconds, source.name)
            val size = ClipPlanner.outputSize(source.width, source.height, options.shortEdge)
            ui.exportSummary.text = "${clips.size} ${if (options.mode == CutMode.ORIGINAL_COPY) "estimated " else ""}clips · ${size.first} × ${size.second}\nLast part: ${time(clips.last().endMs - clips.last().startMs)}${if (source.hdr && options.mode == CutMode.EXACT) "\nHDR encoding must be supported by your device." else ""}"
        }
        ui.captionPreview.text = listOf(options.caption, if (options.partLabels) options.partTemplate.replace("{part}", "1") else "").filter { it.isNotBlank() }.joinToString("\n")
        ui.captionPreview.isVisible = ui.captionPreview.text.isNotBlank()
        (ui.captionPreview.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            gravity = Gravity.CENTER_HORIZONTAL or if (options.captionPosition == Position.TOP) Gravity.TOP else Gravity.BOTTOM
            ui.captionPreview.layoutParams = this
        }
    }
    private fun createProject(transcribeOnly: Boolean) {
        val source = selected ?: run { message("Choose a video first."); return }
        if (ExportService.activityState.value.isNotBlank()) { message("Pause or finish the current job first."); return }
        val options = options()
        try { options.validate() } catch (e: Exception) { message(e.message ?: "Check export options."); return }
        if (options.autoSubtitles && !ModelInstaller(this).installed()) {
            message("Download the speech model in Settings, then return to your video."); ui.navigation.selectedItemId = R.id.nav_settings; return
        }
        val action = {
            val project = Project(source = source, options = options, clips = ClipPlanner.plan(source.durationMs, options.segmentSeconds, source.name))
            store.add(project)
            startServiceTask(if (transcribeOnly) ExportService.TRANSCRIBE else ExportService.EXPORT, project.id)
            ui.navigation.selectedItemId = R.id.nav_library
        }
        if (options.uploadToDrive && !DriveAuth.connected() && !transcribeOnly) connectDrive(action) else action()
    }
    private fun startServiceTask(action: String, id: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        try { ContextCompat.startForegroundService(this, Intent(this, ExportService::class.java).setAction(action).putExtra("project", id)) }
        catch (_: Exception) { message("Android could not start the job. Keep the app open and try again.") }
    }
    private fun pause() { startService(Intent(this, ExportService::class.java).setAction(ExportService.PAUSE)) }
    private fun connectDrive(after: (() -> Unit)? = null) {
        exportAfterAuth = after
        Identity.getAuthorizationClient(this).authorize(DriveAuth.request())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending != null) authorize.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    val token = result.accessToken
                    if (token != null) { DriveAuth.accept(token); refreshSettings(); exportAfterAuth?.invoke(); exportAfterAuth = null }
                    else message("Drive did not return an access token.")
                }
            }.addOnFailureListener { exportAfterAuth = null; message("Drive could not connect. This development build may need account setup. You can still export locally.") }
    }
    private fun disconnectDrive() {
        if (ExportService.activityState.value.isNotBlank()) { message("Pause the current job before disconnecting Drive."); return }
        Identity.getAuthorizationClient(this).revokeAccess(RevokeAccessRequest.builder().setScopes(listOf(Scope(DriveAuth.SCOPE))).build())
            .addOnSuccessListener { DriveAuth.clear(); refreshSettings(); message("Drive access revoked. Uploaded files were kept.") }
            .addOnFailureListener { message("Could not revoke access. Check your connection or remove Clipper from your Google account's connections.") }
    }
    private fun refreshSettings() {
        if (!::ui.isInitialized) return
        ui.driveStatus.text = if (DriveAuth.connected()) "Connected for this session. Only files created or selected through Clipper are accessible." else "Optional. Connect to save ordered clips in a separate project folder. Local exports work without an account."
        val model = ModelInstaller(this)
        ui.modelStatus.text = if (model.installed()) "${model.language().label} is installed. Your audio stays on this device." else "Download the ${model.language().label} model once (about 40–45 MB). Recognition quality depends on speech, music and language."
        ui.installModel.text = if (model.installed()) "Language installed" else "Download language model"
        ui.installModel.isEnabled = !model.installed() && ExportService.activityState.value.isBlank()
        ui.storageStatus.text = "${bytes(StatFs(filesDir.path).availableBytes)} available on this device.\nDelete local projects from the Library to reclaim space."
    }

    private fun renderLibrary(projects: List<Project>) {
        val scrollY = ui.pageScroll.scrollY
        ui.libraryStats.text = "${projects.size} projects  ·  ${projects.sumOf { it.clips.count { clip -> clip.complete } }} clips ready"
        ui.projectList.removeAllViews()
        if (projects.isEmpty()) {
            ui.projectList.addView(TextView(this).apply { text = "Your first story starts here.\nChoose a video in Studio to create your first set of clips."; setTextColor(getColor(R.color.muted)); textSize = 17f; setPadding(20, 60, 20, 60) })
        }
        projects.forEach { project ->
            val row = ItemProjectBinding.inflate(layoutInflater, ui.projectList, false)
            row.projectTitle.text = project.source.name
            row.projectMeta.text = "${project.clips.count { it.complete }} / ${project.clips.size} clips ready · ${project.options.segmentSeconds}s target\n${project.options.mode.name.lowercase().replace('_', ' ')} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(project.createdAt))}"
            row.projectStatus.text = project.message
            row.projectProgress.progress = project.progress
            row.projectProgress.isIndeterminate = project.status == JobStatus.RUNNING && project.progress == 0
            row.projectAction.text = when (project.status) { JobStatus.RUNNING -> "Pause"; JobStatus.COMPLETE -> "Save all"; JobStatus.READY -> "Export"; else -> "Resume" }
            row.projectAction.setOnClickListener {
                when (project.status) {
                    JobStatus.RUNNING -> pause()
                    JobStatus.COMPLETE -> { pendingFolder = project.id; saveFolder.launch(null) }
                    else -> {
                        if (ExportService.activityState.value.isNotBlank()) message("Pause the current job first.")
                        else {
                            val start = { startServiceTask(ExportService.EXPORT, project.id) }
                            if (project.options.uploadToDrive && !DriveAuth.connected()) connectDrive(start) else start()
                        }
                    }
                }
            }
            row.viewClips.setOnClickListener { showClips(project.id) }
            row.editSubtitles.isEnabled = project.subtitlesReady && project.status != JobStatus.RUNNING && project.clips.none { it.complete }
            row.editSubtitles.setOnClickListener { editSubtitles(project.id) }
            row.deleteProject.isEnabled = project.status != JobStatus.RUNNING && !busySaving
            row.deleteProject.setOnClickListener {
                MaterialAlertDialogBuilder(this).setTitle("Delete this local project?")
                    .setMessage("This removes its clips and subtitles from Clipper. The source video, saved downloads and Drive files are kept.")
                    .setNegativeButton("Keep", null).setPositiveButton("Delete local") { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                store.delete(project.id)
                                val stillUsed = store.projects.value.any { it.source.uri == project.source.uri }
                                if (!stillUsed && selected?.uri != project.source.uri) runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(project.source.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            }
                        }
                    }.show()
            }
            ui.projectList.addView(row.root)
        }
        if (ui.libraryPage.isVisible) ui.pageScroll.post { ui.pageScroll.scrollTo(0, scrollY) }
    }

    private fun showClips(id: String) {
        val project = store.get(id)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16)) }
        if (project.clips.none { it.complete }) content.addView(TextView(this).apply { text = "Clips appear here as each part finishes exporting." })
        project.clips.filter { it.complete }.forEach { clip ->
            val file = store.clipFile(id, clip)
            content.addView(TextView(this).apply { text = "${clip.fileName}\n${time(clip.startMs)} → ${time(clip.endMs)} · ${bytes(file.length())}${if (clip.driveUploaded) " · On Drive" else ""}"; textSize = 14f; setPadding(0, dp(20), 0, dp(8)) })
            val buttons = LinearLayout(this)
            fun button(label: String, action: () -> Unit) = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label; textSize = 12f; minimumWidth = 0; setPadding(dp(5), 0, dp(5), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f); setOnClickListener { action() }
            }
            buttons.addView(button("Play") { playClip(file) })
            buttons.addView(button("Save") { pendingSave = file; saveDocument.launch(file.name) })
            buttons.addView(button("Share") { share(file) })
            val sidecar = File(file.parentFile, file.nameWithoutExtension + ".srt")
            if (sidecar.exists()) buttons.addView(button("SRT") { pendingSave = sidecar; saveSrt.launch(sidecar.name) })
            content.addView(buttons)
        }
        MaterialAlertDialogBuilder(this).setTitle("Clips in order").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null).setNeutralButton("Save ready clips") { _, _ -> pendingFolder = id; saveFolder.launch(null) }.show()
    }
    private fun playClip(file: File) {
        if (!file.exists()) { message("This clip is no longer on the device."); return }
        player?.pause()
        val previewPlayer = ExoPlayer.Builder(this).build()
        val view = PlayerView(this).apply { player = previewPlayer; layoutParams = LinearLayout.LayoutParams(-1, dp(300)) }
        previewDialog = MaterialAlertDialogBuilder(this).setTitle(file.name).setView(view).setPositiveButton("Close", null).create().also { dialog ->
            dialog.setOnDismissListener { previewPlayer.release(); previewDialog = null }; dialog.show()
        }
        previewPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); previewPlayer.prepare(); previewPlayer.play()
    }
    private fun share(file: File) {
        if (!file.exists()) { message("This clip is no longer on the device."); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Clip", uri) }
        startActivity(Intent.createChooser(intent, "Share clip"))
    }
    private fun savePending(uri: Uri?) {
        val file = pendingSave; pendingSave = null
        if (uri == null || file == null) return
        lifecycleScope.launch {
            busySaving = true
            try {
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")!!.use { output -> file.inputStream().use { it.copyTo(output) } } }
                message("Saved ${file.name}")
            } catch (_: Exception) { runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }; message("Could not save this clip. Check storage and try again.") }
            finally { busySaving = false }
        }
    }
    private suspend fun saveProjectFolder(id: String, uri: Uri) {
        if (busySaving) { message("Wait for the current save to finish."); return }
        busySaving = true
        var progressDialog: androidx.appcompat.app.AlertDialog? = null
        try {
            val project = store.get(id)
            progressDialog = MaterialAlertDialogBuilder(this).setTitle("Saving clips").setMessage("Copying into a new project folder…").setCancelable(false).show()
            val count = withContext(Dispatchers.IO) {
                val parent = DocumentFile.fromTreeUri(this@MainActivity, uri) ?: error("Choose a writable folder.")
                val folder = parent.createDirectory("${ClipPlanner.safeName(project.source.name.substringBeforeLast('.'))}_${System.currentTimeMillis()}") ?: error("This folder is not writable.")
                var saved = 0
                for (clip in project.clips.filter { it.complete }) {
                    currentCoroutineContext().ensureActive()
                    val file = store.clipFile(id, clip)
                    val files = listOf(file, File(file.parentFile, file.nameWithoutExtension + ".srt")).filter { it.exists() }
                    for (source in files) {
                        val document = folder.createFile(if (source.extension == "srt") "application/x-subrip" else "video/mp4", source.name) ?: error("Cannot create file.")
                        try { contentResolver.openOutputStream(document.uri, "wt")!!.use { output -> source.inputStream().use { it.copyTo(output) } } }
                        catch (error: Exception) { document.delete(); throw error }
                    }
                    saved++
                    withContext(Dispatchers.Main) { progressDialog.setMessage("Saved $saved of ${project.clips.count { it.complete }} clips…") }
                }
                saved
            }
            message("Saved $count clips in a new project folder.")
        } catch (e: Exception) { message(e.message ?: "Could not save all clips. Completed files are kept in the chosen folder.") }
        finally { busySaving = false; progressDialog?.dismiss() }
    }
    private fun editSubtitles(id: String) {
        val project = store.get(id)
        val editor = EditText(this).apply {
            setText(Subtitles.srt(store.cues(id))); gravity = Gravity.TOP; textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 12; maxLines = 18; setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Review subtitles · SRT")
            .setMessage("Edit wording and timestamps. Save before exporting; burned-in captions cannot be changed later.")
            .setView(editor).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { store.saveCues(id, Subtitles.parseSrt(editor.text.toString(), project.source.durationMs)); dialog.dismiss(); message("Subtitles saved. Resume to export clips.") }
            catch (e: Exception) { editor.error = e.message ?: "Invalid subtitle format." }
        } }
        dialog.show()
    }
    private fun captionCommandDialog() {
        val input = EditText(this).apply { hint = "Put \"My travel diary\" at the top"; setPadding(dp(20), dp(12), dp(20), dp(12)); maxLines = 3 }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Tell Clipper")
            .setMessage("A simple offline text command. Try: Add \"Weekend moments\" at the bottom")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val result = CaptionCommand.parse(input.text.toString())
            if (result == null) input.error = "Use: Put \"your caption\" at the top (or bottom)."
            else { ui.captionText.setText(result.text); ui.captionPosition.check(if (result.position == Position.TOP) R.id.positionTop else R.id.positionBottom); dialog.dismiss() }
        } }
        dialog.show()
    }
    private fun spinner(view: Spinner, values: List<String>) { view.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values) }
    private fun selectedListener(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    private fun message(text: String) { Snackbar.make(ui.root, text, Snackbar.LENGTH_LONG).setTextMaxLines(5).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun time(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1000 % 60)
    private fun bytes(value: Long): String = if (value >= 1_073_741_824) String.format(Locale.ROOT, "%.1f GB", value / 1_073_741_824.0) else "${value / 1_048_576} MB"
}
