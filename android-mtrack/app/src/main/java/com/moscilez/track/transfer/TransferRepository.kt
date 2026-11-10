package com.moscilez.track.transfer

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.moscilez.track.BuildConfig
import com.moscilez.track.data.db.ActivityPointEntity
import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.db.ExportMetadataEntity
import com.moscilez.track.data.db.MTrackDatabase
import com.moscilez.track.data.repo.ActivityRepository
import com.moscilez.track.data.repo.ProfileRepository
import com.moscilez.track.domain.track.TrackMath
import com.moscilez.track.transfer.crypto.Checksums
import com.moscilez.track.transfer.crypto.PasswordCrypto
import com.moscilez.track.transfer.crypto.WrongPasswordException
import com.moscilez.track.transfer.qr.QrCodec
import com.moscilez.track.transfer.qr.QrImportCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey

/** Decrypted, validated archive kept in memory between preview and apply. */
class ArchiveInspection internal constructor(
    val uri: Uri,
    val manifest: ArchiveManifest,
    val bundle: TransferBundle,
    val preview: ImportPreview,
    internal val key: SecretKey,
)

/** Pure import conflict planning, shared by archive and QR imports. */
object ImportPlanner {
    data class Plan(val insertSessions: List<SessionBundle>, val skippedSessions: Int, val insertGoals: List<GoalBundle>, val skippedGoals: Int, val importProfile: Boolean, val importSettings: Boolean)

    fun plan(bundle: TransferBundle, mode: ImportMode, existingSessionUuids: Set<String>, existingGoalUuids: Set<String>, hasLocalProfile: Boolean): Plan {
        val seen = HashSet<String>()
        val sessions = bundle.sessions.filter { sb ->
            val u = sb.session.uuid
            u.isNotBlank() && seen.add(u) && (mode == ImportMode.REPLACE || u !in existingSessionUuids)
        }
        val seenG = HashSet<String>()
        val goals = bundle.goals.filter { gb ->
            val u = gb.goal.uuid
            u.isNotBlank() && seenG.add(u) && (mode == ImportMode.REPLACE || u !in existingGoalUuids)
        }
        return Plan(
            insertSessions = sessions,
            skippedSessions = bundle.sessions.size - sessions.size,
            insertGoals = goals,
            skippedGoals = bundle.goals.size - goals.size,
            importProfile = bundle.profile != null && (mode == ImportMode.REPLACE || !hasLocalProfile),
            importSettings = bundle.settings != null && mode == ImportMode.REPLACE,
        )
    }
}

/**
 * Encrypted archive export/import, QR transfer packaging, and full data wipe.
 * All IO runs on Dispatchers.IO. Archive entries are processed in memory and never extracted.
 */
class TransferRepository(
    private val context: Context,
    private val db: MTrackDatabase,
    private val profiles: ProfileRepository,
) {
    val qrCache = QrImportCache(context)
    private val json = TransferFormat.json
    private val activities = ActivityRepository(db)

    val recentOperations: Flow<List<ExportMetadataEntity>> = db.exportDao().observeRecent()

    // region bundle building

    suspend fun buildBundle(sel: ExportSelection, qrRoutes: Boolean): TransferBundle {
        val sessions = if (!sel.activities) emptyList() else
            (sel.sessionIds?.let { activities.getByIds(it) } ?: activities.getAllFinished())
                .filter { it.status == "FINISHED" }.sortedBy { it.startTime }
        val privacy = profiles.getSettings().privacyTrimRouteEnds
        return TransferBundle(
            createdAt = System.currentTimeMillis(),
            profile = if (sel.profile) profiles.getProfile() else null,
            settings = if (sel.settings) profiles.getSettings() else null,
            sessions = sessions.map { s ->
                SessionBundle(
                    session = s,
                    segments = activities.segments(s.id),
                    stats = activities.statistics(s.id),
                    route = if (qrRoutes && sel.routes) qrRoute(s, privacy) else null,
                )
            },
            goals = if (!sel.goals) emptyList() else db.goalDao().getAll().let { goals ->
                val progress = db.goalDao().allProgress().groupBy { it.goalId }
                goals.map { GoalBundle(it, progress[it.id].orEmpty()) }
            },
        )
    }

    /** Streams a route applying the optional privacy trim (first/last 200 m removed). */
    private suspend fun forEachExportPoint(s: ActivitySessionEntity, privacy: Boolean, block: suspend (ActivityPointEntity) -> Unit) {
        if (!privacy) {
            activities.forEachPoint(s.id) { if (ActivityRepository.isValid(it)) block(it) }
            return
        }
        val last = activities.lastPoint(s.id) ?: return
        var first: ActivityPointEntity? = null
        var leaving = true
        val tail = ArrayList<ActivityPointEntity>()
        activities.forEachPoint(s.id) { p ->
            if (!ActivityRepository.isValid(p)) return@forEachPoint
            val f = first ?: p.also { first = it }
            if (leaving) {
                if (TrackMath.distance(f.latitude, f.longitude, p.latitude, p.longitude) < PRIVACY_RADIUS) return@forEachPoint
                leaving = false
            }
            if (TrackMath.distance(last.latitude, last.longitude, p.latitude, p.longitude) < PRIVACY_RADIUS) {
                tail += p
            } else {
                tail.forEach { block(it) }
                tail.clear()
                block(p)
            }
        }
    }

    private suspend fun qrRoute(s: ActivitySessionEntity, privacy: Boolean): List<QrRoutePoint> {
        val stride = ((s.pointCount + QR_ROUTE_POINTS - 1) / QR_ROUTE_POINTS).coerceAtLeast(1)
        val out = ArrayList<QrRoutePoint>(QR_ROUTE_POINTS + 2)
        var i = 0
        var lastAdded: ActivityPointEntity? = null
        var lastSeen: ActivityPointEntity? = null
        forEachExportPoint(s, privacy) { p ->
            if (i % stride == 0) { out += p.toQr(); lastAdded = p }
            lastSeen = p
            i++
        }
        if (lastSeen != null && lastSeen !== lastAdded) out += lastSeen!!.toQr()
        return out
    }

    private fun ActivityPointEntity.toQr() = QrRoutePoint(latitude, longitude, altitude, timestamp, elapsedMs, speed, segment)

    // endregion

    // region archive export

    suspend fun exportArchive(uri: Uri, password: CharArray, sel: ExportSelection): ExportResult = withContext(Dispatchers.IO) {
        if (password.size < TransferFormat.MIN_PASSWORD) throw TransferException.WrongPassword()
        val bundle = buildBundle(sel, qrRoutes = false)
        val salt = PasswordCrypto.randomBytes(PasswordCrypto.SALT_BYTES)
        val key = PasswordCrypto.deriveKey(password, salt)
        val files = ArrayList<ManifestFile>()
        val privacy = profiles.getSettings().privacyTrimRouteEnds
        var routes = 0
        var bytes = 0L
        try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw TransferException.Storage("cannot open output")
            ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                fun put(name: String, plain: ByteArray) {
                    val enc = PasswordCrypto.encrypt(key, plain, name.toByteArray())
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(enc)
                    zip.closeEntry()
                    bytes += enc.size
                    files += ManifestFile(name, Checksums.sha256Hex(plain), plain.size.toLong())
                }
                bundle.profile?.let { put(TransferFormat.PROFILE, json.encodeToString(it).toByteArray()) }
                bundle.settings?.let { put(TransferFormat.SETTINGS, json.encodeToString(it).toByteArray()) }
                if (sel.activities) put(TransferFormat.ACTIVITIES, json.encodeToString(bundle.sessions).toByteArray())
                if (sel.goals) put(TransferFormat.GOALS, json.encodeToString(bundle.goals).toByteArray())
                if (sel.statistics && sel.activities) put(TransferFormat.STATISTICS, statisticsCsv(bundle.sessions.map { it.session }))
                if (sel.routes && sel.activities) {
                    for (sb in bundle.sessions) {
                        val gpx = routeGpx(sb.session, privacy) ?: continue
                        put(TransferFormat.ROUTE_PREFIX + sb.session.uuid + TransferFormat.ROUTE_SUFFIX, gpx)
                        routes++
                    }
                }
                val manifest = ArchiveManifest(
                    formatVersion = TransferFormat.VERSION,
                    appVersion = BuildConfig.VERSION_NAME,
                    createdAt = bundle.createdAt,
                    salt = b64(salt),
                    iterations = PasswordCrypto.ITERATIONS,
                    verifier = b64(PasswordCrypto.encrypt(key, TransferFormat.VERIFIER.toByteArray())),
                    contents = ManifestContents(
                        profile = bundle.profile != null, settings = bundle.settings != null,
                        sessions = bundle.sessions.size, goals = bundle.goals.size, routes = routes,
                        statistics = sel.statistics && sel.activities,
                    ),
                    files = files,
                )
                zip.putNextEntry(ZipEntry(TransferFormat.MANIFEST))
                zip.write(json.encodeToString(manifest).toByteArray())
                zip.closeEntry()
            }
            val checksum = Checksums.sha256Hex(files.joinToString("\n") { it.sha256 }.toByteArray())
            record("ARCHIVE_EXPORT", bundle.sessions.size, checksum, true)
            ExportResult(bundle.sessions.size, routes, bytes, checksum)
        } catch (e: TransferException) {
            record("ARCHIVE_EXPORT", 0, "", false, e.message.orEmpty()); throw e
        } catch (e: Exception) {
            record("ARCHIVE_EXPORT", 0, "", false, e.javaClass.simpleName)
            throw TransferException.Storage(e.message ?: "write failed")
        }
    }

    private suspend fun routeGpx(s: ActivitySessionEntity, privacy: Boolean): ByteArray? {
        if (s.pointCount == 0) return null
        val bos = ByteArrayOutputStream()
        val w = OutputStreamWriter(bos, Charsets.UTF_8)
        val gpx = GpxWriter(w)
        gpx.begin(s.title, s.type, s.startTime)
        forEachExportPoint(s, privacy) { p ->
            gpx.point(GpxPoint(p.latitude, p.longitude, p.altitude, p.timestamp, p.elapsedMs, p.speed, p.accuracy, p.bearing, p.verticalAccuracy, p.segment))
        }
        gpx.end()
        return bos.toByteArray()
    }

    /** Single activity as a plain, unencrypted GPX file (user-initiated share/export). */
    suspend fun exportGpx(uri: Uri, sessionId: Long) = withContext(Dispatchers.IO) {
        val s = activities.get(sessionId) ?: throw TransferException.Corrupted("missing")
        val privacy = profiles.getSettings().privacyTrimRouteEnds
        val bytes = routeGpx(s, privacy) ?: throw TransferException.Corrupted("empty route")
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw TransferException.Storage("cannot open output")
    }

    fun statisticsCsv(sessions: List<ActivitySessionEntity>): ByteArray {
        val sb = StringBuilder("uuid,type,title,start_iso,duration_s,moving_s,distance_m,avg_speed_ms,max_speed_ms,elevation_gain_m,calories,stops\n")
        for (s in sessions) {
            sb.append(s.uuid).append(',').append(s.type).append(',').append(csv(s.title)).append(',')
                .append(Instant.ofEpochMilli(s.startTime)).append(',').append(s.durationMs / 1000).append(',')
                .append(s.movingMs / 1000).append(',').append("%.1f".format(java.util.Locale.US, s.distanceM)).append(',')
                .append("%.3f".format(java.util.Locale.US, s.avgSpeed)).append(',').append("%.3f".format(java.util.Locale.US, s.maxSpeed)).append(',')
                .append("%.1f".format(java.util.Locale.US, s.elevationGain)).append(',').append(s.calories?.let { "%.0f".format(java.util.Locale.US, it) } ?: "")
                .append(',').append(s.stopsCount).append('\n')
        }
        return sb.toString().toByteArray()
    }

    private fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    // endregion

    // region archive import

    /** Pass 1: reads manifest and data entries, verifies password and checksums. Routes are only counted. */
    suspend fun inspectArchive(uri: Uri, password: CharArray): ArchiveInspection = withContext(Dispatchers.IO) {
        val data = HashMap<String, ByteArray>()
        var manifestBytes: ByteArray? = null
        var routes = 0
        try {
            readZip(uri) { name, input ->
                when {
                    name == TransferFormat.MANIFEST -> manifestBytes = readLimited(input, 4L * 1024 * 1024)
                    name.startsWith("data/") -> data[name] = readLimited(input, TransferFormat.MAX_ENTRY_BYTES)
                    name.startsWith(TransferFormat.ROUTE_PREFIX) -> routes++
                }
            }
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) {
            throw TransferException.NotAnArchive()
        }
        val manifest = manifestBytes?.let { runCatching { json.decodeFromString<ArchiveManifest>(String(it)) }.getOrNull() }
            ?: throw TransferException.NotAnArchive()
        if (manifest.format != TransferFormat.ARCHIVE_FORMAT) throw TransferException.NotAnArchive()
        if (manifest.formatVersion > TransferFormat.VERSION || manifest.formatVersion < 1) throw TransferException.UnsupportedVersion(manifest.formatVersion)
        if (manifest.iterations !in 10_000..5_000_000) throw TransferException.Corrupted("iterations")
        val key = PasswordCrypto.deriveKey(password, unb64(manifest.salt), manifest.iterations)
        try {
            PasswordCrypto.decrypt(key, unb64(manifest.verifier)).let {
                if (String(it) != TransferFormat.VERIFIER) throw TransferException.WrongPassword()
            }
        } catch (e: WrongPasswordException) {
            throw TransferException.WrongPassword()
        }
        val checks = manifest.files.associateBy { it.name }
        fun open(name: String): ByteArray? {
            val enc = data[name] ?: return null
            val plain = try { PasswordCrypto.decrypt(key, enc, name.toByteArray()) } catch (e: WrongPasswordException) {
                throw TransferException.Corrupted(name)
            }
            val expected = checks[name] ?: throw TransferException.Corrupted("$name not in manifest")
            if (Checksums.sha256Hex(plain) != expected.sha256) throw TransferException.Corrupted("$name checksum")
            return plain
        }
        val bundle = try {
            TransferBundle(
                formatVersion = manifest.formatVersion,
                createdAt = manifest.createdAt,
                profile = open(TransferFormat.PROFILE)?.let { json.decodeFromString(String(it)) },
                settings = open(TransferFormat.SETTINGS)?.let { json.decodeFromString(String(it)) },
                sessions = open(TransferFormat.ACTIVITIES)?.let { json.decodeFromString<List<SessionBundle>>(String(it)) }.orEmpty(),
                goals = open(TransferFormat.GOALS)?.let { json.decodeFromString<List<GoalBundle>>(String(it)) }.orEmpty(),
            )
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) {
            throw TransferException.Corrupted("data: ${e.javaClass.simpleName}")
        }
        if (manifest.contents.sessions != bundle.sessions.size) throw TransferException.Corrupted("session count")
        ArchiveInspection(uri, manifest, bundle, preview(bundle, routes), key)
    }

    suspend fun preview(bundle: TransferBundle, routes: Int): ImportPreview {
        val existing = db.sessionDao().allUuids().toHashSet()
        return ImportPreview(
            createdAt = bundle.createdAt,
            formatVersion = bundle.formatVersion,
            hasProfile = bundle.profile != null,
            hasSettings = bundle.settings != null,
            sessions = bundle.sessions.size,
            goals = bundle.goals.size,
            routes = routes,
            duplicates = bundle.sessions.count { it.session.uuid in existing },
            totalDistanceM = bundle.sessions.sumOf { it.session.distanceM },
            firstDate = bundle.sessions.minOfOrNull { it.session.startTime },
            lastDate = bundle.sessions.maxOfOrNull { it.session.startTime },
        )
    }

    /** Pass 2: applies everything in one transaction, then streams routes from the archive. */
    suspend fun importArchive(inspection: ArchiveInspection, mode: ImportMode): ImportReport = withContext(Dispatchers.IO) {
        val checks = inspection.manifest.files.associateBy { it.name }
        try {
            val report = db.withTransaction {
                val (base, idMap) = applyBundle(inspection.bundle, mode)
                var ok = 0
                var errors = 0
                readZip(inspection.uri) { name, input ->
                    if (!name.startsWith(TransferFormat.ROUTE_PREFIX)) return@readZip
                    val uuid = name.removePrefix(TransferFormat.ROUTE_PREFIX).removeSuffix(TransferFormat.ROUTE_SUFFIX)
                    val sessionId = idMap[uuid] ?: return@readZip
                    try {
                        val plain = PasswordCrypto.decrypt(inspection.key, readLimited(input, TransferFormat.MAX_ENTRY_BYTES), name.toByteArray())
                        if (Checksums.sha256Hex(plain) != checks[name]?.sha256) throw IllegalStateException("checksum")
                        insertGpx(sessionId, plain)
                        ok++
                    } catch (e: Exception) {
                        db.pointDao().deleteForSession(sessionId)
                        errors++
                    }
                }
                base.copy(routesImported = ok, routeErrors = errors)
            }
            record("ARCHIVE_IMPORT", report.sessionsAdded, inspection.manifest.files.size.toString(), true,
                if (report.routeErrors > 0) "route errors: ${report.routeErrors}" else "")
            report
        } catch (e: Exception) {
            record("ARCHIVE_IMPORT", 0, "", false, e.javaClass.simpleName)
            throw if (e is TransferException) e else TransferException.Corrupted(e.message ?: "import failed")
        }
    }

    private suspend fun insertGpx(sessionId: Long, plain: ByteArray) {
        val batch = ArrayList<ActivityPointEntity>(BATCH)
        var start: Long? = null
        val pending = ArrayList<GpxPoint>()
        GpxParser.parse(plain.inputStream()) { p -> pending += p }
        // Parsing is synchronous; inserts happen in batches afterwards to keep the parser simple.
        for (p in pending) {
            val s = start ?: p.time.also { start = it }
            batch += ActivityPointEntity(
                sessionId = sessionId, latitude = p.lat, longitude = p.lon, altitude = p.ele, timestamp = p.time,
                elapsedMs = p.elapsedMs ?: (p.time - s), speed = p.speed, accuracy = p.accuracy, bearing = p.bearing,
                verticalAccuracy = p.verticalAccuracy, segment = p.segment,
            )
            if (batch.size >= BATCH) { db.pointDao().insertAll(batch); batch.clear() }
        }
        if (batch.isNotEmpty()) db.pointDao().insertAll(batch)
    }

    // endregion

    // region shared apply

    /** Must run inside a transaction. Returns the report and uuid → new local session id map. */
    private suspend fun applyBundle(bundle: TransferBundle, mode: ImportMode): Pair<ImportReport, Map<String, Long>> {
        if (mode == ImportMode.REPLACE) clearTables()
        val plan = ImportPlanner.plan(
            bundle, mode,
            existingSessionUuids = db.sessionDao().allUuids().toHashSet(),
            existingGoalUuids = db.goalDao().allUuids().toHashSet(),
            hasLocalProfile = db.profileDao().get() != null,
        )
        if (plan.importProfile) bundle.profile?.let { db.profileDao().upsert(it.copy(id = 1)) }
        if (plan.importSettings) bundle.settings?.let { profiles.saveSettings(it.copy(id = 1, onboardingDone = true)) }
        val ids = HashMap<String, Long>()
        for (sb in plan.insertSessions) {
            val s = sb.session
            val newId = db.sessionDao().insert(s.copy(id = 0, status = "FINISHED", resumedAt = null))
            ids[s.uuid] = newId
            if (sb.segments.isNotEmpty()) db.segmentDao().insertAll(sb.segments.map { it.copy(id = 0, sessionId = newId) })
            if (sb.stats.isNotEmpty()) db.statisticDao().insertAll(sb.stats.map { it.copy(id = 0, sessionId = newId) })
            sb.route?.takeIf { it.isNotEmpty() }?.let { route ->
                route.chunked(BATCH).forEach { chunk ->
                    db.pointDao().insertAll(chunk.map {
                        ActivityPointEntity(
                            sessionId = newId, latitude = it.a, longitude = it.o, altitude = it.h, timestamp = it.t,
                            elapsedMs = it.e, speed = it.s, accuracy = null, bearing = null, verticalAccuracy = null, segment = it.g,
                        )
                    })
                }
            }
        }
        for (gb in plan.insertGoals) {
            val gid = db.goalDao().insert(gb.goal.copy(id = 0))
            gb.progress.forEach { db.goalDao().upsertProgress(it.copy(id = 0, goalId = gid)) }
        }
        return ImportReport(
            sessionsAdded = plan.insertSessions.size,
            sessionsSkipped = plan.skippedSessions,
            goalsAdded = plan.insertGoals.size,
            goalsSkipped = plan.skippedGoals,
            routesImported = plan.insertSessions.count { !it.route.isNullOrEmpty() },
            profileImported = plan.importProfile,
            settingsImported = plan.importSettings,
        ) to ids
    }

    private suspend fun clearTables() {
        db.sessionDao().clear()
        db.goalDao().clear()
        db.profileDao().clear()
        db.settingsDao().clear()
    }

    // endregion

    // region QR

    data class QrPackage(val code: String, val frames: List<String>, val sessions: Int, val bytes: Int)

    suspend fun createQrPackage(sel: ExportSelection): QrPackage = withContext(Dispatchers.Default) {
        val bundle = buildBundle(sel, qrRoutes = true)
        val code = QrCodec.generateCode()
        val blob = QrCodec.pack(json.encodeToString(bundle).toByteArray(), code)
        val frames = QrCodec.frames(blob)
        record("QR_EXPORT", bundle.sessions.size, QrCodec.transferId(blob), true, "frames: ${frames.size}")
        QrPackage(code, frames, bundle.sessions.size, blob.size)
    }

    /** Decrypts a fully assembled QR package. Throws [TransferException.WrongPassword] on a wrong code. */
    suspend fun openQrPackage(blob: ByteArray, code: String): Pair<TransferBundle, ImportPreview> = withContext(Dispatchers.Default) {
        val plain = try { QrCodec.unpack(blob, code) } catch (e: WrongPasswordException) {
            throw TransferException.WrongPassword()
        } catch (e: Exception) {
            throw TransferException.Corrupted(e.message ?: "qr")
        }
        val bundle = try { json.decodeFromString<TransferBundle>(String(plain)) } catch (e: Exception) {
            throw TransferException.Corrupted("qr json")
        }
        if (bundle.formatVersion > TransferFormat.VERSION) throw TransferException.UnsupportedVersion(bundle.formatVersion)
        bundle to preview(bundle, bundle.sessions.count { !it.route.isNullOrEmpty() })
    }

    suspend fun importQrBundle(bundle: TransferBundle, mode: ImportMode): ImportReport = withContext(Dispatchers.IO) {
        try {
            val report = db.withTransaction { applyBundle(bundle, mode).first }
            qrCache.clear()
            record("QR_IMPORT", report.sessionsAdded, "", true)
            report
        } catch (e: Exception) {
            record("QR_IMPORT", 0, "", false, e.javaClass.simpleName)
            throw TransferException.Corrupted(e.message ?: "qr import")
        }
    }

    // endregion

    /** Irreversibly wipes every table, preferences, caches and temporary files. */
    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        com.moscilez.track.data.LocalePrefs(context).clear()
        qrCache.clear()
        File(context.cacheDir, "export").deleteRecursively()
    }

    private suspend fun record(kind: String, count: Int, checksum: String, ok: Boolean, message: String = "") {
        runCatching {
            db.exportDao().insert(
                ExportMetadataEntity(kind = kind, timestamp = System.currentTimeMillis(), formatVersion = TransferFormat.VERSION,
                    itemCount = count, checksum = checksum, success = ok, message = message.take(200))
            )
        }
    }

    /** Sequential zip reader with entry-name whitelist and size/count limits. */
    private suspend fun readZip(uri: Uri, onEntry: suspend (String, InputStream) -> Unit) {
        val input = context.contentResolver.openInputStream(uri) ?: throw TransferException.NotAnArchive()
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > TransferFormat.MAX_ENTRIES) throw TransferException.TooLarge()
                val name = entry.name
                if (!entry.isDirectory && TransferFormat.ENTRY_NAME.matches(name)) onEntry(name, zip)
                zip.closeEntry()
            }
        }
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw TransferException.TooLarge()
        }
        return out.toByteArray()
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String): ByteArray = try { Base64.getDecoder().decode(s) } catch (e: Exception) { throw TransferException.Corrupted("base64") }

    companion object {
        const val PRIVACY_RADIUS = 200.0
        const val QR_ROUTE_POINTS = 300
        private const val BATCH = 1000

        fun newUuid(): String = UUID.randomUUID().toString()
    }
}
