package de.wandern.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.ActivityType
import de.wandern.app.model.RecordingState
import de.wandern.app.model.TrackPoint
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TrackStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = Database(appContext)

    @Synchronized
    fun createSession(
        routeName: String? = null,
        routeReference: String? = null,
        activityType: ActivityType = ActivityType.HIKING,
    ): Long {
        val now = System.currentTimeMillis()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        val name = routeName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "$it · $timestamp" }
            ?: "${defaultRecordingName(activityType)} $timestamp"
        return database.writableDatabase.insertOrThrow(
            "sessions",
            null,
            ContentValues().apply {
                put("name", name)
                put("started_at", now)
                put("state", RecordingState.RECORDING.name)
                put("segment_index", 0)
                put("activity_type", activityType.name)
                routeReference?.let { put("route_reference", it) }
            },
        )
    }

    @Synchronized
    fun activeSession(): SessionInfo? = database.readableDatabase.query(
        "sessions",
        arrayOf("id", "name", "state", "segment_index", "route_reference", "activity_type"),
        "state IN (?, ?)",
        arrayOf(RecordingState.RECORDING.name, RecordingState.PAUSED.name),
        null,
        null,
        "started_at DESC",
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        SessionInfo(
            id = cursor.getLong(0),
            name = cursor.getString(1),
            state = RecordingState.valueOf(cursor.getString(2)),
            segmentIndex = cursor.getInt(3),
            routeReference = if (cursor.isNull(4)) null else cursor.getString(4),
            activityType = ActivityType.fromStoredValue(if (cursor.isNull(5)) null else cursor.getString(5)),
        )
    }

    @Synchronized
    fun appendPoint(sessionId: Long, segmentIndex: Int, point: TrackPoint) {
        appendPoints(sessionId, segmentIndex, listOf(point))
    }

    @Synchronized
    fun appendPoints(sessionId: Long, segmentIndex: Int, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val firstSequence = db.rawQuery(
                "SELECT COALESCE(MAX(sequence), -1) + 1 FROM points WHERE session_id = ? AND segment_index = ?",
                arrayOf(sessionId.toString(), segmentIndex.toString()),
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            points.forEachIndexed { index, point ->
                db.insertOrThrow(
                    "points",
                    null,
                    ContentValues().apply {
                        put("session_id", sessionId)
                        put("segment_index", segmentIndex)
                        put("sequence", firstSequence + index)
                        put("latitude", point.latitude)
                        put("longitude", point.longitude)
                        point.elevationMeters?.let { put("elevation", it) }
                        point.timeMillis?.let { put("recorded_at", it) }
                        point.accuracyMeters?.let { put("accuracy", it) }
                        point.speedMetersPerSecond?.let { put("speed", it) }
                        put("interpolated", if (point.isInterpolated) 1 else 0)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateState(sessionId: Long, state: RecordingState, segmentIndex: Int? = null) {
        database.writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("state", state.name)
                segmentIndex?.let { put("segment_index", it) }
            },
            "id = ?",
            arrayOf(sessionId.toString()),
        )
    }

    @Synchronized
    fun discardSession(sessionId: Long): Boolean {
        val filePath = database.readableDatabase.query(
            "sessions",
            arrayOf("file_path"),
            "id = ?",
            arrayOf(sessionId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
        val deleted = database.writableDatabase.delete(
            "sessions",
            "id = ?",
            arrayOf(sessionId.toString()),
        ) > 0
        if (deleted && filePath != null) File(filePath).delete()
        return deleted
    }

    @Synchronized
    fun loadTrack(sessionId: Long): GpxTrack {
        val session = database.readableDatabase.query(
            "sessions",
            arrayOf("name", "activity_type"),
            "id = ?",
            arrayOf(sessionId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0) to ActivityType.fromStoredValue(
                    if (cursor.isNull(1)) null else cursor.getString(1),
                )
            } else {
                "Wanderung" to ActivityType.HIKING
            }
        }

        val segments = linkedMapOf<Int, MutableList<TrackPoint>>()
        database.readableDatabase.query(
            "points",
            arrayOf(
                "segment_index",
                "latitude",
                "longitude",
                "elevation",
                "recorded_at",
                "accuracy",
                "speed",
                "interpolated",
            ),
            "session_id = ?",
            arrayOf(sessionId.toString()),
            null,
            null,
            "segment_index, sequence",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val segment = segments.getOrPut(cursor.getInt(0)) { mutableListOf() }
                segment += TrackPoint(
                    latitude = cursor.getDouble(1),
                    longitude = cursor.getDouble(2),
                    elevationMeters = cursor.doubleOrNull(3),
                    timeMillis = cursor.longOrNull(4),
                    accuracyMeters = cursor.floatOrNull(5),
                    speedMetersPerSecond = cursor.floatOrNull(6),
                    isInterpolated = cursor.getInt(7) != 0,
                )
            }
        }
        return GpxTrack(
            name = session.first,
            segments = segments.values.filter { it.isNotEmpty() },
            activityType = session.second,
        )
    }

    @Synchronized
    fun finishSession(sessionId: Long): File {
        val track = loadTrack(sessionId)
        val tracksDirectory = File(appContext.filesDir, "tracks").apply { mkdirs() }
        val target = File(tracksDirectory, "wanderung-$sessionId.gpx")
        target.writeText(GpxCodec.encode(track), Charsets.UTF_8)
        database.writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("state", RecordingState.FINISHED.name)
                put("ended_at", System.currentTimeMillis())
                put("file_path", target.absolutePath)
                track.activityType?.let { put("activity_type", it.name) }
            },
            "id = ?",
            arrayOf(sessionId.toString()),
        )
        return target
    }

    @Synchronized
    fun saveImportedTrack(track: GpxTrack): StoredTour {
        val encoded = GpxCodec.encode(track)
        val trackKey = MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val tracksDirectory = File(appContext.filesDir, "tracks").apply { mkdirs() }
        val target = File(tracksDirectory, "import-${trackKey.take(20)}.gpx")
        if (!target.exists()) target.writeText(encoded, Charsets.UTF_8)

        val now = System.currentTimeMillis()
        database.writableDatabase.insertWithOnConflict(
            "imported_tracks",
            null,
            ContentValues().apply {
                put("track_key", trackKey)
                put("name", track.name)
                put("imported_at", now)
                track.activityType?.let { put("activity_type", it.name) }
                put("file_path", target.absolutePath)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return database.readableDatabase.query(
            "imported_tracks",
            arrayOf("id", "name", "imported_at", "file_path", "activity_type"),
            "track_key = ?",
            arrayOf(trackKey),
            null,
            null,
            null,
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Importierte Tour konnte nicht gespeichert werden." }
            val file = File(cursor.getString(3))
            if (!file.exists()) file.writeText(encoded, Charsets.UTF_8)
            StoredTour(
                reference = "imported:${cursor.getLong(0)}",
                name = cursor.getString(1),
                createdAtMillis = cursor.getLong(2),
                file = file,
                origin = StoredTourOrigin.IMPORTED,
                activityType = ActivityType.fromStoredValueOrNull(
                    if (cursor.isNull(4)) null else cursor.getString(4),
                ),
            )
        }
    }

    @Synchronized
    fun saveRecordedTrack(track: GpxTrack): StoredTour {
        require(track.points.isNotEmpty()) { "Die Aufzeichnung enthält keine Punkte." }
        listStoredTours().firstOrNull {
            it.origin == StoredTourOrigin.RECORDED && it.name == track.name
        }?.let { return it }

        val startedAt = track.points.first().timeMillis ?: System.currentTimeMillis()
        val endedAt = track.points.last().timeMillis ?: startedAt
        val db = database.writableDatabase
        val sessionId = db.insertOrThrow(
            "sessions",
            null,
            ContentValues().apply {
                put("name", track.name)
                put("started_at", startedAt)
                put("ended_at", endedAt)
                put("state", RecordingState.FINISHED.name)
                put("segment_index", track.segments.lastIndex.coerceAtLeast(0))
                put("activity_type", (track.activityType ?: ActivityType.HIKING).name)
            },
        )
        val tracksDirectory = File(appContext.filesDir, "tracks").apply { mkdirs() }
        val target = File(tracksDirectory, "wanderung-$sessionId.gpx")
        try {
            track.segments.forEachIndexed { index, points -> appendPoints(sessionId, index, points) }
            target.writeText(GpxCodec.encode(track), Charsets.UTF_8)
            db.update(
                "sessions",
                ContentValues().apply { put("file_path", target.absolutePath) },
                "id = ?",
                arrayOf(sessionId.toString()),
            )
        } catch (error: Throwable) {
            target.delete()
            db.delete("sessions", "id = ?", arrayOf(sessionId.toString()))
            throw error
        }
        return StoredTour(
            reference = "recorded:$sessionId",
            name = track.name,
            createdAtMillis = endedAt,
            file = target,
            origin = StoredTourOrigin.RECORDED,
            activityType = track.activityType ?: ActivityType.HIKING,
        )
    }

    @Synchronized
    fun listStoredTours(): List<StoredTour> {
        val tours = mutableListOf<StoredTour>()
        database.readableDatabase.query(
            "imported_tracks",
            arrayOf("id", "name", "imported_at", "file_path", "activity_type"),
            null,
            null,
            null,
            null,
            "imported_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val file = File(cursor.getString(3))
                if (file.exists()) {
                    tours += StoredTour(
                        reference = "imported:${cursor.getLong(0)}",
                        name = cursor.getString(1),
                        createdAtMillis = cursor.getLong(2),
                        file = file,
                        origin = StoredTourOrigin.IMPORTED,
                        activityType = ActivityType.fromStoredValueOrNull(
                            if (cursor.isNull(4)) null else cursor.getString(4),
                        ),
                    )
                }
            }
        }
        database.readableDatabase.query(
            "sessions",
            arrayOf("id", "name", "COALESCE(ended_at, started_at)", "file_path", "activity_type"),
            "state = ? AND file_path IS NOT NULL",
            arrayOf(RecordingState.FINISHED.name),
            null,
            null,
            "ended_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val file = File(cursor.getString(3))
                if (file.exists()) {
                    tours += StoredTour(
                        reference = "recorded:${cursor.getLong(0)}",
                        name = cursor.getString(1),
                        createdAtMillis = cursor.getLong(2),
                        file = file,
                        origin = StoredTourOrigin.RECORDED,
                        activityType = ActivityType.fromStoredValue(
                            if (cursor.isNull(4)) null else cursor.getString(4),
                        ),
                    )
                }
            }
        }
        return tours.sortedByDescending { it.createdAtMillis }
    }

    @Synchronized
    fun loadStoredTrack(reference: String): GpxTrack {
        val storedTour = listStoredTours().firstOrNull { it.reference == reference }
            ?: error("Die gespeicherte Tour wurde nicht gefunden.")
        return storedTour.file.inputStream().use { GpxCodec.parse(it, storedTour.name) }
    }

    @Synchronized
    fun renameStoredTour(reference: String, requestedName: String): Boolean {
        val newName = requestedName.trim().take(MAX_TOUR_NAME_LENGTH)
        require(newName.isNotEmpty()) { "Der Tourname darf nicht leer sein." }
        val storedTour = listStoredTours().firstOrNull { it.reference == reference } ?: return false
        val parts = reference.split(':', limit = 2)
        val id = parts.getOrNull(1)?.toLongOrNull() ?: return false
        val table = when (parts.firstOrNull()) {
            "imported" -> "imported_tracks"
            "recorded" -> "sessions"
            else -> return false
        }
        val updated = database.writableDatabase.update(
            table,
            ContentValues().apply { put("name", newName) },
            "id = ?",
            arrayOf(id.toString()),
        ) > 0
        if (!updated) return false

        runCatching {
            val track = storedTour.file.inputStream().use { GpxCodec.parse(it, storedTour.name) }
            storedTour.file.writeText(GpxCodec.encode(track.copy(name = newName)), Charsets.UTF_8)
        }.onFailure {
            database.writableDatabase.update(
                table,
                ContentValues().apply { put("name", storedTour.name) },
                "id = ?",
                arrayOf(id.toString()),
            )
            throw it
        }
        return true
    }

    @Synchronized
    fun deleteStoredTour(reference: String): Boolean {
        val parts = reference.split(':', limit = 2)
        val id = parts.getOrNull(1)?.toLongOrNull() ?: return false
        val table = when (parts.firstOrNull()) {
            "imported" -> "imported_tracks"
            "recorded" -> "sessions"
            else -> return false
        }
        val path = database.readableDatabase.query(
            table,
            arrayOf("file_path"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val deleted = database.writableDatabase.delete(table, "id = ?", arrayOf(id.toString())) > 0
        if (deleted && path != null) File(path).delete()
        return deleted
    }

    @Synchronized
    fun latestSavedTrack(): File? = database.readableDatabase.query(
        "sessions",
        arrayOf("file_path"),
        "state = ? AND file_path IS NOT NULL",
        arrayOf(RecordingState.FINISHED.name),
        null,
        null,
        "ended_at DESC",
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        File(cursor.getString(0)).takeIf { it.exists() }
    }

    data class SessionInfo(
        val id: Long,
        val name: String,
        val state: RecordingState,
        val segmentIndex: Int,
        val routeReference: String?,
        val activityType: ActivityType,
    )

    data class StoredTour(
        val reference: String,
        val name: String,
        val createdAtMillis: Long,
        val file: File,
        val origin: StoredTourOrigin,
        val activityType: ActivityType?,
    )

    enum class StoredTourOrigin { IMPORTED, RECORDED }

    private fun android.database.Cursor.doubleOrNull(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.floatOrNull(index: Int): Float? =
        if (isNull(index)) null else getFloat(index)

    private class Database(context: Context) : SQLiteOpenHelper(context, "wandern.db", null, 5) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    state TEXT NOT NULL,
                    segment_index INTEGER NOT NULL DEFAULT 0,
                    route_reference TEXT,
                    activity_type TEXT NOT NULL DEFAULT 'HIKING',
                    file_path TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE points (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    segment_index INTEGER NOT NULL,
                    sequence INTEGER NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    elevation REAL,
                    recorded_at INTEGER,
                    accuracy REAL,
                    speed REAL,
                    interpolated INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(session_id, segment_index, sequence)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX points_session_index ON points(session_id, segment_index, sequence)")
            createImportedTracksTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createImportedTracksTable(db)
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE points ADD COLUMN interpolated INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN route_reference TEXT")
            }
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN activity_type TEXT NOT NULL DEFAULT 'HIKING'")
                if (oldVersion >= 2) {
                    db.execSQL("ALTER TABLE imported_tracks ADD COLUMN activity_type TEXT")
                }
            }
        }

        private fun createImportedTracksTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS imported_tracks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    track_key TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    imported_at INTEGER NOT NULL,
                    activity_type TEXT,
                    file_path TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS imported_tracks_date_index ON imported_tracks(imported_at DESC)")
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    companion object {
        private const val MAX_TOUR_NAME_LENGTH = 120

        private fun defaultRecordingName(activityType: ActivityType): String = when (activityType) {
            ActivityType.HIKING -> "Wanderung"
            ActivityType.CYCLING -> "Radtour"
            ActivityType.E_BIKE -> "E-Bike-Tour"
            ActivityType.RUNNING -> "Lauf"
        }
    }
}
