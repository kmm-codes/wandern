package de.wandern.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.MenuItem
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.wandern.app.BuildConfig
import de.wandern.app.R
import de.wandern.app.data.OfflineMapAvailability
import de.wandern.app.data.ElevationEnricher
import de.wandern.app.data.OfflineMapDownloadState
import de.wandern.app.data.OfflineMapDownloader
import de.wandern.app.data.OfflineMapStatus
import de.wandern.app.data.TrackStore
import de.wandern.app.databinding.ActivityTourLibraryBinding
import de.wandern.app.databinding.ItemTourBinding
import de.wandern.app.debug.DemoTourFactory
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.OfflineMapPlanner
import de.wandern.app.model.TrackAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale

class TourLibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTourLibraryBinding
    private lateinit var trackStore: TrackStore
    private lateinit var offlineMapDownloader: OfflineMapDownloader
    private var exportSource: File? = null
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

    private val multiImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) importGpxFiles(uris)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        val source = exportSource
        exportSource = null
        if (uri != null && source != null) exportTrack(source, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTourLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        trackStore = TrackStore(this)
        offlineMapDownloader = OfflineMapDownloader(this, MAP_STYLE_URL)
        binding.toolbar.setNavigationContentDescription(R.string.cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }
        setupDemoMenu()
        binding.importToursButton.setOnClickListener {
            multiImportLauncher.launch(
                arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"),
            )
        }
        refreshTours()
    }

    private fun setupDemoMenu() {
        if (!BuildConfig.DEBUG) return
        binding.toolbar.menu.add(0, MENU_CREATE_DEMO, 0, R.string.create_demo_tour)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId != MENU_CREATE_DEMO) return@setOnMenuItemClickListener false
            createDemoTour()
            true
        }
    }

    private fun createDemoTour() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val existed = trackStore.listStoredTours().any {
                        it.origin == TrackStore.StoredTourOrigin.RECORDED &&
                            it.name == DemoTourFactory.TOUR_NAME
                    }
                    if (!existed) trackStore.saveRecordedTrack(DemoTourFactory.create())
                    existed
                }
            }
            result.onSuccess { existed ->
                toast(getString(if (existed) R.string.demo_tour_exists else R.string.demo_tour_created))
                refreshTours()
            }.onFailure {
                toast(getString(R.string.demo_tour_error, it.localizedMessage ?: "Unbekannter Fehler"))
            }
        }
    }

    private fun importGpxFiles(uris: List<Uri>) {
        binding.importToursButton.isEnabled = false
        binding.importToursButton.setText(R.string.importing_gpx_files)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val knownReferences = trackStore.listStoredTours()
                    .mapTo(mutableSetOf()) { it.reference }
                var imported = 0
                var duplicates = 0
                val errors = mutableListOf<String>()
                uris.forEach { uri ->
                    runCatching {
                        val parsedTrack = contentResolver.openInputStream(uri)?.use { input ->
                            de.wandern.app.data.GpxCodec.parse(
                                input,
                                uri.lastPathSegment ?: "Importierte Route",
                            )
                        } ?: error("Datei konnte nicht geöffnet werden.")
                        val track = runCatching { ElevationEnricher().enrichIfMissing(parsedTrack) }
                            .onFailure { Log.w(LOG_TAG, "Elevation enrichment failed", it) }
                            .getOrDefault(parsedTrack)
                        trackStore.saveImportedTrack(track)
                    }.onSuccess { stored ->
                        if (knownReferences.add(stored.reference)) imported++ else duplicates++
                    }.onFailure { error ->
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "GPX-Datei"
                        errors += "$name: ${error.localizedMessage ?: "unbekannter Fehler"}"
                    }
                }
                ImportOutcome(imported, duplicates, errors)
            }
            binding.importToursButton.isEnabled = true
            binding.importToursButton.setText(R.string.import_gpx_files)
            refreshTours()
            showImportOutcome(outcome)
        }
    }

    private fun showImportOutcome(outcome: ImportOutcome) {
        val summary = getString(
            R.string.gpx_import_summary,
            outcome.imported,
            outcome.duplicates,
            outcome.errors.size,
        )
        if (outcome.errors.isEmpty()) {
            toast(summary)
            return
        }
        val errorDetails = outcome.errors.take(MAX_IMPORT_ERRORS_SHOWN).joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gpx_import_finished)
            .setMessage("$summary\n\n${getString(R.string.gpx_import_errors, errorDetails)}")
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun refreshTours() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                trackStore.listStoredTours().mapNotNull { stored ->
                    runCatching {
                        val track = stored.file.inputStream().use { input ->
                            de.wandern.app.data.GpxCodec.parse(input, stored.name)
                        }
                        TourRow(stored, track, TrackAnalyzer.calculate(track).distanceMeters)
                    }.getOrNull()
                }
            }
            renderTours(rows)
        }
    }

    private fun renderTours(rows: List<TourRow>) {
        binding.tourListContainer.removeAllViews()
        binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.tourScrollView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.forEach { row ->
            val item = ItemTourBinding.inflate(layoutInflater, binding.tourListContainer, false)
            item.tourNameText.text = row.stored.name
            val origin = getString(
                if (row.stored.origin == TrackStore.StoredTourOrigin.IMPORTED) {
                    R.string.tour_origin_imported
                } else {
                    R.string.tour_origin_recorded
                },
            )
            val activity = row.track.activityType ?: row.stored.activityType
            val sourceAndDate = buildList {
                add(origin)
                activity?.let { add(getString(it.labelRes())) }
                add(dateFormat.format(Date(row.stored.createdAtMillis)))
            }.joinToString(" · ")
            item.tourDetailsText.text = getString(
                R.string.tour_details,
                sourceAndDate,
                row.distanceMeters / 1000.0,
                row.track.points.size,
            )
            item.openTourButton.setOnClickListener { openTour(row.stored.reference) }
            item.tourMoreButton.setOnClickListener { showTourActions(it, row) }
            binding.tourListContainer.addView(item.root)
            queryOfflineStatus(row, item)
        }
    }

    private fun showTourActions(anchor: View, row: TourRow) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.rename_tour)
            menu.add(0, MENU_EXPORT, 1, R.string.export_gpx)
            menu.add(0, MENU_DELETE, 2, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RENAME -> {
                        showRenameDialog(row)
                        true
                    }
                    MENU_EXPORT -> {
                        exportSource = row.stored.file
                        exportLauncher.launch(exportFileName(row.stored.name))
                        true
                    }
                    MENU_DELETE -> {
                        confirmDeleteTour(row)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showRenameDialog(row: TourRow) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(row.stored.name)
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_tour_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> renameTour(row, input.text.toString()) }
            .show()
    }

    private fun renameTour(row: TourRow, requestedName: String) {
        if (requestedName.isBlank()) {
            toast(getString(R.string.tour_rename_error, "Name darf nicht leer sein"))
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    trackStore.renameStoredTour(row.stored.reference, requestedName)
                }
            }
            result.onSuccess { renamed ->
                if (renamed) {
                    toast(getString(R.string.tour_renamed))
                    refreshTours()
                } else {
                    toast(getString(R.string.tour_rename_error, "Tour nicht gefunden"))
                }
            }.onFailure {
                toast(getString(R.string.tour_rename_error, it.localizedMessage ?: "Unbekannter Fehler"))
            }
        }
    }

    private fun exportFileName(tourName: String): String {
        val safeName = tourName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "Wanderung" }
        return if (safeName.endsWith(".gpx", ignoreCase = true)) safeName else "$safeName.gpx"
    }

    private fun exportTrack(source: File, target: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(target, "w")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Zieldatei konnte nicht geöffnet werden.")
                }
            }
            result.onSuccess { toast(getString(R.string.gpx_exported)) }
                .onFailure {
                    toast(getString(R.string.gpx_export_error, it.localizedMessage ?: "Unbekannter Fehler"))
                }
        }
    }

    private fun openTour(reference: String) {
        startActivity(
            Intent(this, TourDetailActivity::class.java)
                .putExtra(TourDetailActivity.EXTRA_TOUR_REFERENCE, reference),
        )
    }

    private fun queryOfflineStatus(row: TourRow, item: ItemTourBinding) {
        item.offlineStatusText.setText(R.string.offline_map_checking)
        item.offlineMapButton.setText(R.string.offline_map_checking_short)
        item.offlineMapButton.isEnabled = false
        offlineMapDownloader.status(row.track) { status ->
            runOnUiThread {
                if (!isDestroyed) renderOfflineStatus(row, item, status)
            }
        }
    }

    private fun renderOfflineStatus(row: TourRow, item: ItemTourBinding, status: OfflineMapStatus) {
        item.offlineMapButton.setOnClickListener(null)
        when (status.availability) {
            OfflineMapAvailability.CHECKING -> {
                item.offlineStatusText.setText(R.string.offline_map_checking)
                item.offlineMapButton.setText(R.string.offline_map_checking_short)
                item.offlineMapButton.isEnabled = false
            }
            OfflineMapAvailability.NOT_DOWNLOADED -> {
                item.offlineStatusText.setText(R.string.offline_map_not_downloaded)
                item.offlineMapButton.setText(R.string.offline_map_save)
                item.offlineMapButton.isEnabled = true
                item.offlineMapButton.setOnClickListener { confirmDownload(row, item) }
            }
            OfflineMapAvailability.PARTIAL -> {
                val size = Formatter.formatShortFileSize(this, status.downloadedBytes)
                item.offlineStatusText.text = getString(R.string.offline_map_partial, size)
                item.offlineMapButton.setText(R.string.offline_map_continue)
                item.offlineMapButton.isEnabled = true
                item.offlineMapButton.setOnClickListener { confirmDownload(row, item) }
            }
            OfflineMapAvailability.DOWNLOADED -> {
                val size = Formatter.formatShortFileSize(this, status.downloadedBytes)
                item.offlineStatusText.text = getString(R.string.offline_map_stored, size)
                item.offlineMapButton.setText(R.string.offline_map_delete)
                item.offlineMapButton.isEnabled = true
                item.offlineMapButton.setOnClickListener { confirmDeleteOfflineMap(row, item) }
            }
            OfflineMapAvailability.ERROR -> {
                item.offlineStatusText.text = status.message?.let {
                    getString(R.string.offline_map_error, it)
                } ?: getString(R.string.offline_map_manage_error)
                item.offlineMapButton.setText(R.string.retry)
                item.offlineMapButton.isEnabled = true
                item.offlineMapButton.setOnClickListener { queryOfflineStatus(row, item) }
            }
        }
    }

    private fun confirmDownload(row: TourRow, item: ItemTourBinding) {
        val plan = runCatching { OfflineMapPlanner.plan(row.track) }.getOrElse {
            toast(getString(R.string.offline_map_error, it.localizedMessage ?: "Unbekannter Fehler"))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_map_question_title)
            .setMessage(
                getString(
                    R.string.offline_map_question_message,
                    plan.maxZoom,
                    plan.estimatedTileCount,
                ),
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.offline_map_download) { _, _ -> downloadOfflineMap(row, item) }
            .show()
    }

    private fun downloadOfflineMap(row: TourRow, item: ItemTourBinding) {
        item.offlineMapButton.isEnabled = false
        offlineMapDownloader.download(row.track) { state ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (state) {
                    is OfflineMapDownloadState.Planning -> {
                        item.offlineStatusText.text = getString(
                            R.string.offline_map_planning,
                            state.plan.maxZoom,
                        )
                    }
                    is OfflineMapDownloadState.Progress -> {
                        val size = Formatter.formatShortFileSize(this, state.downloadedBytes)
                        item.offlineStatusText.text = state.percent?.let {
                            getString(R.string.offline_map_progress_percent, it, size)
                        } ?: getString(R.string.offline_map_progress, size)
                    }
                    is OfflineMapDownloadState.Complete -> queryOfflineStatus(row, item)
                    is OfflineMapDownloadState.Error -> {
                        toast(getString(R.string.offline_map_error, state.message))
                        queryOfflineStatus(row, item)
                    }
                }
            }
        }
    }

    private fun confirmDeleteOfflineMap(row: TourRow, item: ItemTourBinding) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_map_delete_title)
            .setMessage(getString(R.string.offline_map_delete_message, row.stored.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.offline_map_delete) { _, _ ->
                item.offlineMapButton.isEnabled = false
                offlineMapDownloader.delete(row.track) { result ->
                    runOnUiThread {
                        result.onSuccess {
                            toast(getString(R.string.offline_map_deleted))
                            queryOfflineStatus(row, item)
                        }.onFailure {
                            toast(getString(R.string.offline_map_error, it.localizedMessage ?: "Unbekannter Fehler"))
                            queryOfflineStatus(row, item)
                        }
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteTour(row: TourRow) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tour_delete_title)
            .setMessage(getString(R.string.tour_delete_message, row.stored.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteTour(row) }
            .show()
    }

    private fun deleteTour(row: TourRow) {
        offlineMapDownloader.delete(row.track) { mapResult ->
            runOnUiThread {
                mapResult.onFailure {
                    toast(getString(R.string.tour_delete_error, it.localizedMessage ?: "Offline-Karte"))
                    return@runOnUiThread
                }
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        trackStore.deleteStoredTour(row.stored.reference)
                    }
                    if (deleted) {
                        toast(getString(R.string.tour_deleted))
                        refreshTours()
                    } else {
                        toast(getString(R.string.tour_delete_error, "Tour nicht gefunden"))
                    }
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private data class TourRow(
        val stored: TrackStore.StoredTour,
        val track: GpxTrack,
        val distanceMeters: Double,
    )

    private data class ImportOutcome(
        val imported: Int,
        val duplicates: Int,
        val errors: List<String>,
    )

    companion object {
        private const val LOG_TAG = "TourLibraryActivity"
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val MAX_IMPORT_ERRORS_SHOWN = 5
        private const val MENU_EXPORT = 1
        private const val MENU_RENAME = 3
        private const val MENU_DELETE = 4
        private const val MENU_CREATE_DEMO = 10
    }
}
