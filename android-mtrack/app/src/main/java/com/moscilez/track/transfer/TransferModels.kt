package com.moscilez.track.transfer

import com.moscilez.track.data.db.ActivitySegmentEntity
import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.db.ActivityStatisticEntity
import com.moscilez.track.data.db.GoalEntity
import com.moscilez.track.data.db.GoalProgressEntity
import com.moscilez.track.data.db.UserProfileEntity
import com.moscilez.track.data.db.UserSettingsEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Compact route point for QR transfers (short keys keep payloads small). */
@Serializable
data class QrRoutePoint(
    val a: Double,
    val o: Double,
    val h: Double? = null,
    val t: Long,
    val e: Long,
    val s: Float? = null,
    val g: Int = 0,
)

@Serializable
data class SessionBundle(
    val session: ActivitySessionEntity,
    val segments: List<ActivitySegmentEntity> = emptyList(),
    val stats: List<ActivityStatisticEntity> = emptyList(),
    /** Only used by QR transfers; archives carry routes as separate GPX entries. */
    val route: List<QrRoutePoint>? = null,
)

@Serializable
data class GoalBundle(val goal: GoalEntity, val progress: List<GoalProgressEntity> = emptyList())

@Serializable
data class TransferBundle(
    val formatVersion: Int = TransferFormat.VERSION,
    val createdAt: Long,
    val profile: UserProfileEntity? = null,
    val settings: UserSettingsEntity? = null,
    val sessions: List<SessionBundle> = emptyList(),
    val goals: List<GoalBundle> = emptyList(),
)

@Serializable
data class ManifestFile(val name: String, val sha256: String, val size: Long)

@Serializable
data class ManifestContents(
    val profile: Boolean,
    val settings: Boolean,
    val sessions: Int,
    val goals: Int,
    val routes: Int,
    val statistics: Boolean,
)

@Serializable
data class ArchiveManifest(
    val format: String = TransferFormat.ARCHIVE_FORMAT,
    val formatVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val cipher: String = "AES-256-GCM",
    val salt: String,
    val iterations: Int,
    /** Encrypted known constant; lets import reject a wrong password before touching data. */
    val verifier: String,
    val contents: ManifestContents,
    val files: List<ManifestFile>,
)

data class ExportSelection(
    val profile: Boolean = true,
    val settings: Boolean = true,
    val activities: Boolean = true,
    val routes: Boolean = true,
    val goals: Boolean = true,
    val statistics: Boolean = true,
    /** Restrict to these sessions; null = all finished activities. */
    val sessionIds: List<Long>? = null,
)

enum class ImportMode { MERGE, REPLACE }

data class ImportPreview(
    val createdAt: Long,
    val formatVersion: Int,
    val hasProfile: Boolean,
    val hasSettings: Boolean,
    val sessions: Int,
    val goals: Int,
    val routes: Int,
    val duplicates: Int,
    val totalDistanceM: Double,
    val firstDate: Long?,
    val lastDate: Long?,
)

data class ImportReport(
    val sessionsAdded: Int = 0,
    val sessionsSkipped: Int = 0,
    val goalsAdded: Int = 0,
    val goalsSkipped: Int = 0,
    val routesImported: Int = 0,
    val routeErrors: Int = 0,
    val profileImported: Boolean = false,
    val settingsImported: Boolean = false,
)

data class ExportResult(val sessions: Int, val routes: Int, val bytes: Long, val checksum: String)

sealed class TransferException(message: String) : Exception(message) {
    class WrongPassword : TransferException("wrong password")
    class NotAnArchive : TransferException("not an MTrack archive")
    class UnsupportedVersion(val version: Int) : TransferException("unsupported version $version")
    class Corrupted(detail: String) : TransferException(detail)
    class TooLarge : TransferException("archive exceeds limits")
    class Storage(detail: String) : TransferException(detail)
}

object TransferFormat {
    const val VERSION = 1
    const val ARCHIVE_FORMAT = "mtrack-archive"
    const val VERIFIER = "MTRACK-ARCHIVE-OK"
    const val MANIFEST = "manifest.json"
    const val PROFILE = "data/profile.json.enc"
    const val SETTINGS = "data/settings.json.enc"
    const val ACTIVITIES = "data/activities.json.enc"
    const val GOALS = "data/goals.json.enc"
    const val STATISTICS = "data/statistics.csv.enc"
    const val ROUTE_PREFIX = "routes/"
    const val ROUTE_SUFFIX = ".gpx.enc"

    /** Whitelist of entry names; anything else is ignored. Entries are never written to disk. */
    val ENTRY_NAME = Regex("^(manifest\\.json|data/[a-z_]{1,32}\\.(json|csv)\\.enc|routes/[0-9a-f\\-]{36}\\.gpx\\.enc)$")

    const val MAX_ENTRIES = 100_000
    const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    const val MIN_PASSWORD = 6

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
