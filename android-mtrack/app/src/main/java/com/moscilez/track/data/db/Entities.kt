package com.moscilez.track.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val birthDate: Long? = null,
    val sex: String? = null,
    val timezone: String,
    val preferredType: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val units: String = "METRIC",
    /** "system", "ru" or "en". */
    val language: String = "system",
    val theme: String = "SYSTEM",
    val gpsAccuracy: String = "HIGH",
    val gpsIntervalMs: Long = 2000,
    val minDistanceM: Double = 2.0,
    val maxAccuracyM: Double = 35.0,
    val autoPause: Boolean = false,
    val notifications: Boolean = true,
    val sounds: Boolean = false,
    val vibration: Boolean = true,
    val backgroundTracking: Boolean = true,
    val mapGrid: Boolean = true,
    val showPace: Boolean = true,
    val showAltitude: Boolean = true,
    val showCalories: Boolean = true,
    val autoStops: Boolean = true,
    val carSpeedThresholdKmh: Double = 90.0,
    val carFuelLPer100: Double? = null,
    val carWarningAccepted: Boolean = false,
    /** "SYSTEM", "DMY", "YMD", "MDY". */
    val dateFormat: String = "SYSTEM",
    val use24h: Boolean = true,
    val recommendationsEnabled: Boolean = true,
    /** Comma-separated recommendation kinds the user muted. */
    val mutedRecommendations: String = "",
    /** Trim the first/last 200 m of routes in exports to hide home/work locations. */
    val privacyTrimRouteEnds: Boolean = false,
    val onboardingDone: Boolean = false,
)

@Serializable
@Entity(
    tableName = "activity_session",
    indices = [Index(value = ["uuid"], unique = true), Index("startTime"), Index("type"), Index("status")],
)
data class ActivitySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable identity across devices; used to de-duplicate imports. */
    val uuid: String,
    val type: String,
    val title: String,
    val note: String = "",
    /** Comma-separated user tags. */
    val tags: String = "",
    val tripType: String? = null,
    val speedLimitKmh: Double? = null,
    val status: String,
    val startTime: Long,
    val endTime: Long? = null,
    /** Accumulated active time before the current running interval, ms. */
    val accumulatedActiveMs: Long = 0,
    /** Wall-clock time the current running interval started; null while paused/finished. */
    val resumedAt: Long? = null,
    val durationMs: Long = 0,
    val movingMs: Long = 0,
    val stoppedMs: Long = 0,
    val distanceM: Double = 0.0,
    val avgSpeed: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val avgPaceSecPerKm: Double? = null,
    val bestPaceSecPerKm: Double? = null,
    val elevationGain: Double = 0.0,
    val elevationLoss: Double = 0.0,
    val maxAltitude: Double? = null,
    val minAltitude: Double? = null,
    val maxClimbGrade: Double = 0.0,
    val maxDescentGrade: Double = 0.0,
    val calories: Double? = null,
    val stopsCount: Int = 0,
    val overSpeedMs: Long = 0,
    val maxAccel: Double = 0.0,
    val maxDecel: Double = 0.0,
    val fuelLiters: Double? = null,
    val pointCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "activity_point",
    foreignKeys = [ForeignKey(
        entity = ActivitySessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId", "timestamp"])],
)
data class ActivityPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timestamp: Long,
    val elapsedMs: Long,
    val speed: Float?,
    val accuracy: Float?,
    val bearing: Float?,
    val verticalAccuracy: Float?,
    @ColumnInfo(defaultValue = "0") val segment: Int = 0,
)

@Serializable
@Entity(
    tableName = "activity_segment",
    foreignKeys = [ForeignKey(
        entity = ActivitySessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class ActivitySegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** STOP, PAUSE or SPLIT. */
    val kind: String,
    val idx: Int,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val distanceM: Double = 0.0,
    val elevationDelta: Double = 0.0,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** Peak moments (max speed, highest point, steepest climb...) for a session. */
@Serializable
@Entity(
    tableName = "activity_statistic",
    foreignKeys = [ForeignKey(
        entity = ActivitySessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class ActivityStatisticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val kind: String,
    val value: Double,
    val elapsedMs: Long,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
@Entity(tableName = "goal", indices = [Index(value = ["uuid"], unique = true)])
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    /** GoalType name. */
    val type: String,
    val title: String,
    val target: Double,
    val activityType: String? = null,
    /** WEEK, MONTH or DEADLINE. */
    val period: String,
    val startDate: Long,
    val deadline: Long? = null,
    /** Manual progress for custom goals. */
    val manualProgress: Double = 0.0,
    val archived: Boolean = false,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "goal_progress",
    foreignKeys = [ForeignKey(
        entity = GoalEntity::class,
        parentColumns = ["id"],
        childColumns = ["goalId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["goalId", "periodStart"], unique = true)],
)
data class GoalProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val periodStart: Long,
    val periodEnd: Long,
    val value: Double,
    val target: Double,
    val achieved: Boolean,
    val recordedAt: Long,
)

@Serializable
@Entity(tableName = "export_metadata")
data class ExportMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ARCHIVE_EXPORT, ARCHIVE_IMPORT, QR_EXPORT, QR_IMPORT. */
    val kind: String,
    val timestamp: Long,
    val formatVersion: Int,
    val itemCount: Int,
    val checksum: String,
    val success: Boolean,
    val message: String = "",
)
