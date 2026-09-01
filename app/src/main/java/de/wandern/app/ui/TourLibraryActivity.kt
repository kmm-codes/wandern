package de.wandern.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.wandern.app.R
import de.wandern.app.data.OfflineMapAvailability
import de.wandern.app.data.ElevationEnricher
import de.wandern.app.data.OfflineMapDownloadState
import de.wandern.app.data.OfflineMapDownloader
import de.wandern.app.data.OfflineMapStatus
import de.wandern.app.data.TrackStore
import de.wandern.app.databinding.ActivityTourLibraryBinding
import de.wandern.app.databinding.ItemTourBinding
import de.wandern.app.localization.AppLanguage
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.OfflineMapPlanner
import de.wandern.app.model.TrackAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.DateFormat
import java.io.File
import java.util.ArrayDeque
import java.util.Date
import kotlin.math.max

class TourLibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTourLibraryBinding
    private lateinit var trackStore: TrackStore
    private lateinit var offlineMapDownloader: OfflineMapDownloader
    private var exportSource: File? = null
    private val dateFormat by lazy {
        DateFormat.getDateInstance(DateFormat.MEDIUM, AppLanguage.forContext(this).locale)
    }
    private var allRows: List<TourRow> = emptyList()
    private var selectedOrigin = TrackStore.StoredTourOrigin.IMPORTED
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTimeMillis = 0L
    private val thumbnailQueue = ArrayDeque<ThumbnailRequest>()
    private var activeThumbnailSnapshotter: MapSnapshotter? = null
    private var thumbnailGeneration = 0

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
        MapLibre.getInstance(this)
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
        binding.tourCategoryToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedOrigin = if (checkedId == R.id.completedToursButton) {
                TrackStore.StoredTourOrigin.RECORDED
            } else {
                TrackStore.StoredTourOrigin.IMPORTED
            }
            renderTours()
        }
        binding.planRouteButton.setOnClickListener {
            startActivity(Intent(this, RoutePlannerActivity::class.java))
        }
        binding.importToursButton.setOnClickListener {
            multiImportLauncher.launch(
                arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTours()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (detectCategorySwipe(event)) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun detectCategorySwipe(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeStartX = event.x
            swipeStartY = event.y
            swipeStartTimeMillis = event.eventTime
            return false
        }
        if (event.actionMasked != MotionEvent.ACTION_UP) return false
        val deltaX = event.x - swipeStartX
        val deltaY = event.y - swipeStartY
        val durationMillis = event.eventTime - swipeStartTimeMillis
        val minimumDistance = SWIPE_MIN_DISTANCE_DP * resources.displayMetrics.density
        val direction = HorizontalSwipeClassifier.classify(
            deltaX = deltaX,
            deltaY = deltaY,
            durationMillis = durationMillis,
            minimumDistance = minimumDistance,
            horizontalRatio = SWIPE_HORIZONTAL_RATIO,
            maximumDurationMillis = SWIPE_MAX_DURATION_MILLIS,
        ) ?: return false
        return switchCategory(
            if (direction == HorizontalSwipeDirection.LEFT) TrackStore.StoredTourOrigin.RECORDED
            else TrackStore.StoredTourOrigin.IMPORTED,
        )
    }

    private fun switchCategory(origin: TrackStore.StoredTourOrigin): Boolean {
        if (origin == selectedOrigin) return false
        binding.tourCategoryToggle.check(
            if (origin == TrackStore.StoredTourOrigin.IMPORTED) {
                R.id.plannedToursButton
            } else {
                R.id.completedToursButton
            },
        )
        binding.tourScrollView.scrollTo(0, 0)
        return true
    }

    private fun importGpxFiles(uris: List<Uri>) {
        binding.importToursButton.isEnabled = false
        binding.importToursButton.setText(R.string.importing_gpx_files_short)
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
                                uri.lastPathSegment ?: getString(R.string.imported_route_fallback),
                            )
                        } ?: error(getString(R.string.file_open_error))
                        val track = runCatching { ElevationEnricher().enrichIfMissing(parsedTrack) }
                            .onFailure { Log.w(LOG_TAG, "Elevation enrichment failed", it) }
                            .getOrDefault(parsedTrack)
                        trackStore.saveImportedTrack(track)
                    }.onSuccess { stored ->
                        if (knownReferences.add(stored.reference)) imported++ else duplicates++
                    }.onFailure { error ->
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "GPX-Datei"
                        errors += "$name: ${error.localizedMessage ?: getString(R.string.unknown_error)}"
                    }
                }
                ImportOutcome(imported, duplicates, errors)
            }
            binding.importToursButton.isEnabled = true
            binding.importToursButton.setText(R.string.import_gpx_files_short)
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
                val baseRows = trackStore.listStoredTours().mapNotNull { stored ->
                    runCatching {
                        val track = stored.file.inputStream().use { input ->
                            de.wandern.app.data.GpxCodec.parse(input, stored.name)
                        }
                        TourRow(stored, track, TrackAnalyzer.calculate(track).distanceMeters)
                    }.getOrNull()
                }
                val rowsByReference = baseRows.associateBy { it.stored.reference }
                baseRows.map { row ->
                    val source = row.stored.sourceReference?.let(rowsByReference::get)
                    row.copy(
                        sourceRecordedAtMillis = source?.track?.points
                            ?.firstNotNullOfOrNull { it.timeMillis }
                            ?: source?.stored?.createdAtMillis,
                        relatedRouteName = row.stored.routeReference
                            ?.let(rowsByReference::get)
                            ?.stored
                            ?.name,
                    )
                }
            }
            allRows = rows
            renderTours()
        }
    }

    private fun renderTours() {
        val rows = allRows.filter { it.stored.origin == selectedOrigin }
        val planned = selectedOrigin == TrackStore.StoredTourOrigin.IMPORTED
        binding.plannedTourActions.visibility = if (planned) View.VISIBLE else View.GONE
        binding.emptyText.setText(
            if (planned) R.string.no_planned_tours else R.string.no_completed_tours,
        )
        resetThumbnailQueue()
        binding.tourListContainer.removeAllViews()
        binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.tourScrollView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.forEach { row ->
            val item = ItemTourBinding.inflate(layoutInflater, binding.tourListContainer, false)
            item.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (planned) R.color.planned_tour_card else R.color.recorded_tour_card,
                ),
            )
            item.tourNameText.text = row.stored.name
            item.root.contentDescription = getString(R.string.open_tour_accessibility, row.stored.name)
            item.mapThumbnail.contentDescription = getString(R.string.tour_map_thumbnail, row.stored.name)
            item.mapThumbnail.tag = row.stored.reference
            val activity = row.track.activityType ?: row.stored.activityType
            val sourceAndDate = buildList {
                if (row.stored.origin == TrackStore.StoredTourOrigin.IMPORTED) {
                    val sourceDate = row.sourceRecordedAtMillis
                    add(
                        if (sourceDate != null) {
                            getString(
                                R.string.tour_origin_from_recording,
                                dateFormat.format(Date(sourceDate)),
                            )
                        } else {
                            when (row.stored.plannedSource) {
                                TrackStore.PlannedTourSource.ROUTER -> getString(R.string.tour_origin_planned_in_app)
                                TrackStore.PlannedTourSource.RECORDING -> getString(R.string.tour_origin_from_recording_unknown_date)
                                else -> getString(R.string.tour_origin_imported)
                            }
                        },
                    )
                    if (sourceDate == null) add(dateFormat.format(Date(row.stored.createdAtMillis)))
                } else {
                    row.relatedRouteName?.let {
                        add(getString(R.string.completed_from_planned_route, it))
                    }
                    activity?.let { add(getString(it.labelRes())) }
                    val recordedAt = row.track.points.firstNotNullOfOrNull { it.timeMillis }
                        ?: row.stored.createdAtMillis
                    add(dateFormat.format(Date(recordedAt)))
                }
            }.joinToString(" · ")
            item.tourDetailsText.text = getString(
                R.string.tour_details,
                sourceAndDate,
                row.distanceMeters / 1000.0,
            )
            item.root.setOnClickListener { openTour(row.stored.reference) }
            item.tourMoreButton.setOnClickListener { showTourActions(it, row, item) }
            binding.tourListContainer.addView(item.root)
            item.mapThumbnail.doOnLayout {
                enqueueThumbnail(
                    reference = row.stored.reference,
                    cacheKey = thumbnailCacheKey(row.stored),
                    track = row.track,
                    imageView = item.mapThumbnail,
                    progress = item.thumbnailProgress,
                )
            }
            queryOfflineStatus(row, item)
        }
    }

    private fun enqueueThumbnail(
        reference: String,
        cacheKey: String,
        track: GpxTrack,
        imageView: ImageView,
        progress: ProgressBar,
    ) {
        if (imageView.tag != reference || isDestroyed) return
        THUMBNAIL_CACHE.get(cacheKey)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            progress.visibility = View.GONE
            return
        }
        thumbnailQueue.addLast(
            ThumbnailRequest(
                generation = thumbnailGeneration,
                reference = reference,
                cacheKey = cacheKey,
                track = track,
                imageView = imageView,
                progress = progress,
            ),
        )
        startNextThumbnail()
    }

    private fun startNextThumbnail() {
        if (activeThumbnailSnapshotter != null) return
        val request = thumbnailQueue.pollFirst() ?: return
        if (request.generation != thumbnailGeneration || request.imageView.tag != request.reference) {
            startNextThumbnail()
            return
        }
        val bounds = thumbnailBounds(request.track) ?: run {
            request.progress.visibility = View.GONE
            startNextThumbnail()
            return
        }
        val features = request.track.segments.filter { it.size >= 2 }.map { segment ->
            Feature.fromGeometry(
                LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        }
        val style = Style.Builder()
            .fromUri(MAP_STYLE_URL)
            .withSource(
                GeoJsonSource(
                    THUMBNAIL_ROUTE_SOURCE,
                    FeatureCollection.fromFeatures(features),
                ),
            )
            .withLayer(
                LineLayer(THUMBNAIL_ROUTE_LAYER, THUMBNAIL_ROUTE_SOURCE).withProperties(
                    lineColor(Color.parseColor("#1677FF")),
                    lineWidth(5f),
                    lineOpacity(0.95f),
                    lineCap(LINE_CAP_ROUND),
                    lineJoin(LINE_JOIN_ROUND),
                ),
            )
        val snapshotter = MapSnapshotter(
            applicationContext,
            MapSnapshotter.Options(
                request.imageView.width.coerceAtLeast(1),
                request.imageView.height.coerceAtLeast(1),
            ).withStyleBuilder(style)
                .withRegion(bounds)
                .withPixelRatio(1f)
                .withLogo(false),
        )
        activeThumbnailSnapshotter = snapshotter
        snapshotter.setObserver(object : MapSnapshotter.Observer {
            override fun onDidFinishLoadingStyle() {
                MapStyleLocalizer.localizeKnownLayers(
                    layerLookup = snapshotter::getLayer,
                    language = AppLanguage.forContext(this@TourLibraryActivity),
                )
            }

            override fun onStyleImageMissing(imageName: String) = Unit
        })
        snapshotter.start(
            { snapshot ->
                runOnUiThread {
                    finishThumbnail(request, snapshotter, snapshot.bitmap)
                }
            },
            {
                runOnUiThread {
                    finishThumbnail(request, snapshotter, null)
                }
            },
        )
    }

    private fun finishThumbnail(
        request: ThumbnailRequest,
        snapshotter: MapSnapshotter,
        bitmap: Bitmap?,
    ) {
        if (activeThumbnailSnapshotter !== snapshotter) return
        activeThumbnailSnapshotter = null
        if (request.generation == thumbnailGeneration && request.imageView.tag == request.reference) {
            request.progress.visibility = View.GONE
            bitmap?.let {
                THUMBNAIL_CACHE.put(request.cacheKey, it)
                request.imageView.setImageBitmap(it)
            }
        }
        startNextThumbnail()
    }

    private fun thumbnailBounds(track: GpxTrack): LatLngBounds? {
        val points = track.points
        if (points.isEmpty()) return null
        val north = points.maxOf { it.latitude }
        val south = points.minOf { it.latitude }
        val east = points.maxOf { it.longitude }
        val west = points.minOf { it.longitude }
        val latitudePadding = max((north - south) * THUMBNAIL_BOUNDS_PADDING, MIN_THUMBNAIL_PADDING_DEGREES)
        val longitudePadding = max((east - west) * THUMBNAIL_BOUNDS_PADDING, MIN_THUMBNAIL_PADDING_DEGREES)
        return LatLngBounds.from(
            north + latitudePadding,
            east + longitudePadding,
            south - latitudePadding,
            west - longitudePadding,
        )
    }

    private fun resetThumbnailQueue() {
        thumbnailGeneration++
        thumbnailQueue.clear()
        activeThumbnailSnapshotter?.cancel()
        activeThumbnailSnapshotter = null
    }

    private fun thumbnailCacheKey(stored: TrackStore.StoredTour): String = buildString {
        append(stored.reference)
        append(':')
        append(stored.file.absolutePath)
        append(':')
        append(stored.file.length())
        append(':')
        append(stored.file.lastModified())
    }

    private fun showTourActions(anchor: View, row: TourRow, itemBinding: ItemTourBinding) {
        PopupMenu(this, anchor).apply {
            var order = 0
            menu.add(0, MENU_OPEN, order++, R.string.open_tour)
            if (row.stored.origin == TrackStore.StoredTourOrigin.RECORDED) {
                menu.add(0, MENU_PLAN_FROM_RECORDING, order++, R.string.plan_from_recording)
            } else {
                menu.add(0, MENU_EDIT, order++, R.string.edit_tour)
                menu.add(0, MENU_DUPLICATE, order++, R.string.duplicate_tour)
            }
            menu.add(0, MENU_RENAME, order++, R.string.rename_tour)
            menu.add(0, MENU_EXPORT, order++, R.string.export_gpx)
            if (
                row.offlineMapStatus.availability == OfflineMapAvailability.DOWNLOADED ||
                row.offlineMapStatus.availability == OfflineMapAvailability.PARTIAL
            ) {
                menu.add(0, MENU_DELETE_OFFLINE_MAP, order++, R.string.offline_map_delete)
            }
            menu.add(0, MENU_DELETE, order, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_OPEN -> {
                        openTour(row.stored.reference)
                        true
                    }
                    MENU_EDIT -> {
                        editTour(row.stored.reference)
                        true
                    }
                    MENU_DUPLICATE -> {
                        duplicateTour(row)
                        true
                    }
                    MENU_PLAN_FROM_RECORDING -> {
                        planFromRecording(row)
                        true
                    }
                    MENU_RENAME -> {
                        showRenameDialog(row)
                        true
                    }
                    MENU_EXPORT -> {
                        exportSource = row.stored.file
                        exportLauncher.launch(exportFileName(row.stored.name))
                        true
                    }
                    MENU_DELETE_OFFLINE_MAP -> {
                        confirmDeleteOfflineMap(row, itemBinding)
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

    private fun planFromRecording(row: TourRow) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    trackStore.saveRouteDefinitionFromRecording(row.stored.reference)
                }
            }
            result.onSuccess {
                selectedOrigin = TrackStore.StoredTourOrigin.IMPORTED
                binding.tourCategoryToggle.check(R.id.plannedToursButton)
                toast(getString(R.string.recording_added_to_planned))
                refreshTours()
            }.onFailure {
                toast(getString(R.string.plan_from_recording_error, it.localizedMessage ?: getString(R.string.unknown_error)))
            }
        }
    }

    private fun editTour(reference: String) {
        startActivity(
            Intent(this, RoutePlannerActivity::class.java)
                .putExtra(RoutePlannerActivity.EXTRA_EDIT_TOUR_REFERENCE, reference),
        )
    }

    private fun duplicateTour(row: TourRow) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    trackStore.duplicateImportedTrack(row.stored.reference)
                }
            }
            result.onSuccess { copy ->
                toast(getString(R.string.tour_duplicated))
                editTour(copy.reference)
            }.onFailure { error ->
                toast(
                    getString(
                        R.string.tour_duplicate_error,
                        error.localizedMessage ?: getString(R.string.not_available),
                    ),
                )
            }
        }
    }

    private fun showRenameDialog(row: TourRow) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(row.stored.name)
            selectAll()
        }
        val inputContainer = FrameLayout(this).apply {
            setPadding(dp(DIALOG_FIELD_HORIZONTAL_PADDING_DP), 0, dp(DIALOG_FIELD_HORIZONTAL_PADDING_DP), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_tour_title)
            .setView(inputContainer)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> renameTour(row, input.text.toString()) }
            .show()
    }

    private fun renameTour(row: TourRow, requestedName: String) {
        if (requestedName.isBlank()) {
            toast(getString(R.string.tour_rename_error, getString(R.string.tour_name_empty_error)))
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
                    toast(getString(R.string.tour_rename_error, getString(R.string.tour_not_found)))
                }
            }.onFailure {
                toast(getString(R.string.tour_rename_error, it.localizedMessage ?: getString(R.string.unknown_error)))
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun exportTrack(source: File, target: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(target, "w")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error(getString(R.string.target_file_open_error))
                }
            }
            result.onSuccess { toast(getString(R.string.gpx_exported)) }
                .onFailure {
                    toast(getString(R.string.gpx_export_error, it.localizedMessage ?: getString(R.string.unknown_error)))
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
        row.offlineMapStatus = OfflineMapStatus(OfflineMapAvailability.CHECKING)
        item.offlineStatusText.setText(R.string.offline_map_checking)
        item.offlineMapButton.visibility = View.VISIBLE
        item.offlineMapButton.setIconResource(R.drawable.ic_offline_download)
        item.offlineMapButton.alpha = OFFLINE_ICON_DISABLED_ALPHA
        item.offlineMapButton.contentDescription = getString(R.string.offline_map_checking)
        item.offlineMapButton.isEnabled = false
        offlineMapDownloader.status(row.track) { status ->
            runOnUiThread {
                if (!isDestroyed) renderOfflineStatus(row, item, status)
            }
        }
    }

    private fun renderOfflineStatus(row: TourRow, item: ItemTourBinding, status: OfflineMapStatus) {
        row.offlineMapStatus = status
        item.offlineMapButton.visibility = View.VISIBLE
        item.offlineMapButton.setOnClickListener(null)
        item.offlineMapButton.setOnLongClickListener(null)
        when (status.availability) {
            OfflineMapAvailability.CHECKING -> {
                item.offlineStatusText.setText(R.string.offline_map_checking)
                item.offlineMapButton.setIconResource(R.drawable.ic_offline_download)
                item.offlineMapButton.alpha = OFFLINE_ICON_DISABLED_ALPHA
                item.offlineMapButton.contentDescription = getString(R.string.offline_map_checking)
                item.offlineMapButton.isEnabled = false
            }
            OfflineMapAvailability.NOT_DOWNLOADED -> {
                item.offlineStatusText.setText(R.string.offline_map_not_downloaded)
                item.offlineMapButton.setIconResource(R.drawable.ic_offline_download)
                item.offlineMapButton.alpha = OFFLINE_ICON_ALPHA
                item.offlineMapButton.contentDescription = getString(R.string.offline_map_save)
                item.offlineMapButton.isEnabled = true
                item.offlineMapButton.setOnClickListener { confirmDownload(row, item) }
            }
            OfflineMapAvailability.PARTIAL -> {
                val size = Formatter.formatShortFileSize(this, status.downloadedBytes)
                item.offlineStatusText.text = getString(R.string.offline_map_partial, size)
                item.offlineMapButton.setIconResource(R.drawable.ic_offline_download)
                item.offlineMapButton.alpha = OFFLINE_ICON_ALPHA
                item.offlineMapButton.contentDescription = getString(R.string.offline_map_continue)
                item.offlineMapButton.isEnabled = true
                item.offlineMapButton.setOnClickListener { confirmDownload(row, item) }
                bindOfflineSizeLongPress(item, size)
            }
            OfflineMapAvailability.DOWNLOADED -> {
                val size = Formatter.formatShortFileSize(this, status.downloadedBytes)
                item.offlineStatusText.text = getString(R.string.offline_map_stored, size)
                item.offlineMapButton.setIconResource(R.drawable.ic_offline_downloaded)
                item.offlineMapButton.alpha = OFFLINE_ICON_ALPHA
                item.offlineMapButton.contentDescription = getString(R.string.offline_map_stored_action, size)
                item.offlineMapButton.isEnabled = true
                item.offlineMapButton.setOnClickListener { confirmDeleteOfflineMap(row, item) }
                bindOfflineSizeLongPress(item, size)
            }
            OfflineMapAvailability.ERROR -> {
                item.offlineStatusText.text = status.message?.let {
                    getString(R.string.offline_map_error, it)
                } ?: getString(R.string.offline_map_manage_error)
                item.offlineMapButton.setIconResource(R.drawable.ic_refresh)
                item.offlineMapButton.alpha = OFFLINE_ICON_ALPHA
                item.offlineMapButton.contentDescription = getString(R.string.retry)
                item.offlineMapButton.isEnabled = true
                item.offlineMapButton.setOnClickListener { queryOfflineStatus(row, item) }
            }
        }
        TooltipCompat.setTooltipText(
            item.offlineMapButton,
            item.offlineMapButton.contentDescription,
        )
    }

    private fun bindOfflineSizeLongPress(item: ItemTourBinding, size: String) {
        item.offlineMapButton.setOnLongClickListener {
            toast(getString(R.string.offline_map_size, size))
            true
        }
    }

    private fun confirmDownload(row: TourRow, item: ItemTourBinding) {
        val plan = runCatching { OfflineMapPlanner.plan(row.track) }.getOrElse {
            toast(getString(R.string.offline_map_error, it.localizedMessage ?: getString(R.string.unknown_error)))
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
        item.offlineMapButton.alpha = OFFLINE_ICON_DISABLED_ALPHA
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
                            toast(getString(R.string.offline_map_error, it.localizedMessage ?: getString(R.string.unknown_error)))
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
                    toast(getString(R.string.tour_delete_error, it.localizedMessage ?: getString(R.string.offline_map_label)))
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
                        toast(getString(R.string.tour_delete_error, getString(R.string.tour_not_found)))
                    }
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        resetThumbnailQueue()
        super.onDestroy()
    }

    private data class TourRow(
        val stored: TrackStore.StoredTour,
        val track: GpxTrack,
        val distanceMeters: Double,
        val sourceRecordedAtMillis: Long? = null,
        val relatedRouteName: String? = null,
        var offlineMapStatus: OfflineMapStatus = OfflineMapStatus(OfflineMapAvailability.CHECKING),
    )

    private data class ImportOutcome(
        val imported: Int,
        val duplicates: Int,
        val errors: List<String>,
    )

    private data class ThumbnailRequest(
        val generation: Int,
        val reference: String,
        val cacheKey: String,
        val track: GpxTrack,
        val imageView: ImageView,
        val progress: ProgressBar,
    )

    companion object {
        private const val LOG_TAG = "TourLibraryActivity"
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val MAX_IMPORT_ERRORS_SHOWN = 5
        private const val SWIPE_MIN_DISTANCE_DP = 72f
        private const val SWIPE_HORIZONTAL_RATIO = 1.25f
        private const val SWIPE_MAX_DURATION_MILLIS = 1_200L
        private const val MENU_EXPORT = 1
        private const val MENU_RENAME = 3
        private const val MENU_DELETE = 4
        private const val MENU_PLAN_FROM_RECORDING = 5
        private const val MENU_DELETE_OFFLINE_MAP = 6
        private const val MENU_EDIT = 7
        private const val MENU_DUPLICATE = 8
        private const val MENU_OPEN = 9
        private const val DIALOG_FIELD_HORIZONTAL_PADDING_DP = 24
        private const val THUMBNAIL_ROUTE_SOURCE = "tour-thumbnail-route-source"
        private const val THUMBNAIL_ROUTE_LAYER = "tour-thumbnail-route-layer"
        private const val THUMBNAIL_BOUNDS_PADDING = 0.12
        private const val MIN_THUMBNAIL_PADDING_DEGREES = 0.0008
        private const val OFFLINE_ICON_ALPHA = 0.68f
        private const val OFFLINE_ICON_DISABLED_ALPHA = 0.38f
        private val THUMBNAIL_CACHE = object : LruCache<String, Bitmap>(12 * 1_024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1_024
        }
    }
}
