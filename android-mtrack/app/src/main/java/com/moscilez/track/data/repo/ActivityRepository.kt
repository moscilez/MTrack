package com.moscilez.track.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.moscilez.track.data.db.ActivityPointEntity
import com.moscilez.track.data.db.ActivitySegmentEntity
import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.db.ActivityStatisticEntity
import com.moscilez.track.data.db.MTrackDatabase
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.domain.model.SegmentKind
import com.moscilez.track.domain.model.SessionStatus
import com.moscilez.track.domain.track.CalorieEstimator
import com.moscilez.track.domain.track.SessionAccumulator
import com.moscilez.track.domain.track.TrackMath
import com.moscilez.track.domain.track.TrackPoint
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class HistorySort { DATE_DESC, DATE_ASC, DISTANCE_DESC, DURATION_DESC, SPEED_DESC }

data class HistoryFilter(
    val types: Set<ActivityType> = emptySet(),
    val from: Long? = null,
    val to: Long? = null,
    val search: String = "",
    val sort: HistorySort = HistorySort.DATE_DESC,
)

fun ActivityPointEntity.toTrackPoint() = TrackPoint(
    lat = latitude, lon = longitude, altitude = altitude, timestamp = timestamp, elapsedMs = elapsedMs,
    speed = speed?.toDouble(), accuracy = accuracy?.toDouble(), bearing = bearing?.toDouble(),
    verticalAccuracy = verticalAccuracy?.toDouble(), segment = segment,
)

/** All reads and writes of recorded activities. UI layers never touch DAOs directly. */
class ActivityRepository(private val db: MTrackDatabase) {
    private val sessions = db.sessionDao()
    private val points = db.pointDao()

    val finished: Flow<List<ActivitySessionEntity>> = sessions.observeFinished()
    fun recent(limit: Int): Flow<List<ActivitySessionEntity>> = sessions.observeRecent(limit)
    val unfinished: Flow<ActivitySessionEntity?> = sessions.observeUnfinished()

    fun observe(id: Long): Flow<ActivitySessionEntity?> = sessions.observe(id)
    suspend fun get(id: Long): ActivitySessionEntity? = sessions.get(id)
    suspend fun getUnfinished(): ActivitySessionEntity? = sessions.getUnfinished()
    suspend fun getAllFinished(): List<ActivitySessionEntity> = sessions.getAllFinished()
    suspend fun getByIds(ids: List<Long>) = sessions.getByIds(ids)
    suspend fun sessionCount(): Int = sessions.count()
    suspend fun pointCount(): Int = points.countAll()

    suspend fun create(type: ActivityType, title: String, tripType: String?, speedLimitKmh: Double?): Long {
        val now = System.currentTimeMillis()
        return sessions.insert(
            ActivitySessionEntity(
                uuid = UUID.randomUUID().toString(),
                type = type.name,
                title = title,
                tripType = tripType,
                speedLimitKmh = speedLimitKmh,
                status = SessionStatus.RECORDING.name,
                startTime = now,
                resumedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun update(session: ActivitySessionEntity) = sessions.update(session.copy(updatedAt = System.currentTimeMillis()))

    /** Batched, transactional append so a crash never leaves half a batch. */
    suspend fun appendPoints(batch: List<ActivityPointEntity>) {
        if (batch.isEmpty()) return
        db.withTransaction { points.insertAll(batch) }
    }

    suspend fun lastPoint(sessionId: Long): ActivityPointEntity? = points.last(sessionId)

    /** Streams a route in fixed-size chunks; memory stays bounded for very long routes. */
    suspend fun forEachPoint(sessionId: Long, chunkSize: Int = 2000, block: suspend (ActivityPointEntity) -> Unit) {
        var after = 0L
        while (true) {
            val chunk = points.chunk(sessionId, after, chunkSize)
            if (chunk.isEmpty()) break
            for (p in chunk) block(p)
            after = chunk.last().id
            if (chunk.size < chunkSize) break
        }
    }

    suspend fun segments(sessionId: Long) = db.segmentDao().forSession(sessionId)
    suspend fun statistics(sessionId: Long) = db.statisticDao().forSession(sessionId)
    suspend fun stopsBetween(from: Long, to: Long) = db.segmentDao().stopsBetween(from, to)

    /**
     * Recomputes all derived metrics from stored points and marks the session finished.
     * Corrupted points (NaN / out-of-range coordinates) are skipped instead of failing.
     */
    suspend fun finalize(
        sessionId: Long,
        activeMs: Long,
        weightKg: Double?,
        overSpeedThresholdKmh: Double?,
        fuelLPer100: Double?,
    ): ActivitySessionEntity? {
        val s = sessions.get(sessionId) ?: return null
        val type = ActivityType.from(s.type)
        val threshold = (s.speedLimitKmh ?: overSpeedThresholdKmh)?.takeIf { type.isVehicle }?.div(3.6)
        val acc = SessionAccumulator(type, overSpeedThreshold = threshold)
        forEachPoint(sessionId) { p ->
            if (isValid(p)) acc.add(p.toTrackPoint())
        }
        val sum = acc.summary()
        val duration = activeMs.coerceAtLeast(0)
        val moving = (duration - sum.stoppedMs).coerceAtLeast(0)
        val avg = TrackMath.averageSpeed(sum.distanceM, duration)
        val movingAvg = TrackMath.averageSpeed(sum.distanceM, moving)
        val now = System.currentTimeMillis()
        val updated = s.copy(
            status = SessionStatus.FINISHED.name,
            endTime = s.endTime ?: now,
            accumulatedActiveMs = duration,
            resumedAt = null,
            durationMs = duration,
            movingMs = moving,
            stoppedMs = sum.stoppedMs,
            distanceM = sum.distanceM,
            avgSpeed = avg,
            maxSpeed = sum.maxSpeed,
            avgPaceSecPerKm = if (type.showsPace) TrackMath.paceSecPerKm(movingAvg) else null,
            bestPaceSecPerKm = if (type.showsPace) sum.bestSplitPace else null,
            elevationGain = sum.elevationGain,
            elevationLoss = sum.elevationLoss,
            maxAltitude = sum.maxAltitude,
            minAltitude = sum.minAltitude,
            maxClimbGrade = sum.maxClimbGrade,
            maxDescentGrade = sum.maxDescentGrade,
            calories = CalorieEstimator.estimate(type, movingAvg, moving, weightKg),
            stopsCount = sum.stops.size,
            overSpeedMs = sum.overSpeedMs,
            maxAccel = sum.maxAccel,
            maxDecel = sum.maxDecel,
            fuelLiters = if (type.isVehicle && fuelLPer100 != null) sum.distanceM / 100_000.0 * fuelLPer100 else null,
            pointCount = sum.pointCount,
            updatedAt = now,
        )
        db.withTransaction {
            sessions.update(updated)
            db.segmentDao().deleteDerived(sessionId)
            db.statisticDao().deleteForSession(sessionId)
            db.segmentDao().insertAll(
                sum.stops.mapIndexed { i, st ->
                    ActivitySegmentEntity(
                        sessionId = sessionId, kind = SegmentKind.STOP.name, idx = i,
                        startElapsedMs = st.startElapsedMs, endElapsedMs = st.endElapsedMs,
                        latitude = st.lat, longitude = st.lon,
                    )
                } + sum.splits.map { sp ->
                    ActivitySegmentEntity(
                        sessionId = sessionId, kind = SegmentKind.SPLIT.name, idx = sp.index,
                        startElapsedMs = 0, endElapsedMs = sp.durationMs,
                        distanceM = sp.distanceM, elevationDelta = sp.elevationDelta,
                    )
                }
            )
            db.statisticDao().insertAll(sum.peaks.map {
                ActivityStatisticEntity(
                    sessionId = sessionId, kind = it.kind.name, value = it.value,
                    elapsedMs = it.elapsedMs, latitude = it.lat, longitude = it.lon,
                )
            })
        }
        return updated
    }

    suspend fun updateMeta(id: Long, title: String, note: String, tags: String, tripType: String?) {
        val s = sessions.get(id) ?: return
        sessions.update(s.copy(title = title.trim().ifEmpty { s.title }, note = note, tags = tags, tripType = tripType, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        // Children are removed through ON DELETE CASCADE inside the same statement.
        ids.chunked(500).forEach { sessions.delete(it) }
    }

    suspend fun history(filter: HistoryFilter, offset: Int, limit: Int): List<ActivitySessionEntity> {
        val args = mutableListOf<Any>()
        val sql = StringBuilder("SELECT * FROM activity_session WHERE status = 'FINISHED'")
        if (filter.types.isNotEmpty()) {
            sql.append(" AND type IN (").append(filter.types.joinToString(",") { "?" }).append(")")
            args.addAll(filter.types.map { it.name })
        }
        filter.from?.let { sql.append(" AND startTime >= ?"); args.add(it) }
        filter.to?.let { sql.append(" AND startTime < ?"); args.add(it) }
        val q = filter.search.trim()
        if (q.isNotEmpty()) {
            sql.append(" AND (title LIKE ? ESCAPE '\\' OR note LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\')")
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(3) { args.add(like) }
        }
        sql.append(
            when (filter.sort) {
                HistorySort.DATE_DESC -> " ORDER BY startTime DESC"
                HistorySort.DATE_ASC -> " ORDER BY startTime ASC"
                HistorySort.DISTANCE_DESC -> " ORDER BY distanceM DESC"
                HistorySort.DURATION_DESC -> " ORDER BY durationMs DESC"
                HistorySort.SPEED_DESC -> " ORDER BY avgSpeed DESC"
            }
        )
        sql.append(" LIMIT ? OFFSET ?")
        args.add(limit); args.add(offset)
        return sessions.query(SimpleSQLiteQuery(sql.toString(), args.toTypedArray()))
    }

    companion object {
        fun isValid(p: ActivityPointEntity): Boolean =
            !p.latitude.isNaN() && !p.longitude.isNaN() &&
                p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0 &&
                (p.latitude != 0.0 || p.longitude != 0.0)
    }
}
