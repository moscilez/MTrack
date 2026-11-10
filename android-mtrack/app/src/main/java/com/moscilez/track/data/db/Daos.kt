package com.moscilez.track.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observe(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfileEntity?

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)

    @Query("DELETE FROM user_profile")
    suspend fun clear()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1")
    fun observe(): Flow<UserSettingsEntity?>

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun get(): UserSettingsEntity?

    @Upsert
    suspend fun upsert(settings: UserSettingsEntity)

    @Query("DELETE FROM user_settings")
    suspend fun clear()
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ActivitySessionEntity): Long

    @Update
    suspend fun update(session: ActivitySessionEntity)

    @Query("SELECT * FROM activity_session WHERE id = :id")
    suspend fun get(id: Long): ActivitySessionEntity?

    @Query("SELECT * FROM activity_session WHERE id = :id")
    fun observe(id: Long): Flow<ActivitySessionEntity?>

    @Query("SELECT * FROM activity_session WHERE status != 'FINISHED' ORDER BY startTime DESC LIMIT 1")
    suspend fun getUnfinished(): ActivitySessionEntity?

    @Query("SELECT * FROM activity_session WHERE status != 'FINISHED' ORDER BY startTime DESC LIMIT 1")
    fun observeUnfinished(): Flow<ActivitySessionEntity?>

    @Query("SELECT * FROM activity_session WHERE status = 'FINISHED' ORDER BY startTime DESC")
    fun observeFinished(): Flow<List<ActivitySessionEntity>>

    @Query("SELECT * FROM activity_session WHERE status = 'FINISHED' ORDER BY startTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivitySessionEntity>>

    @Query("SELECT * FROM activity_session WHERE status = 'FINISHED' ORDER BY startTime ASC")
    suspend fun getAllFinished(): List<ActivitySessionEntity>

    @Query("SELECT * FROM activity_session WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ActivitySessionEntity>

    @Query("SELECT uuid FROM activity_session")
    suspend fun allUuids(): List<String>

    @Query("SELECT COUNT(*) FROM activity_session")
    suspend fun count(): Int

    /** Dynamic history query (filters, search, sort, keyset-free offset paging). */
    @RawQuery(observedEntities = [ActivitySessionEntity::class])
    suspend fun query(query: SupportSQLiteQuery): List<ActivitySessionEntity>

    @Query("DELETE FROM activity_session WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM activity_session")
    suspend fun clear()
}

@Dao
interface PointDao {
    @Insert
    suspend fun insertAll(points: List<ActivityPointEntity>)

    /** Keyset paging over a route; constant memory regardless of route length. */
    @Query("SELECT * FROM activity_point WHERE sessionId = :sessionId AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun chunk(sessionId: Long, afterId: Long, limit: Int): List<ActivityPointEntity>

    @Query("SELECT * FROM activity_point WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun last(sessionId: Long): ActivityPointEntity?

    @Query("SELECT COUNT(*) FROM activity_point WHERE sessionId = :sessionId")
    suspend fun count(sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM activity_point")
    suspend fun countAll(): Int

    @Query("DELETE FROM activity_point WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

@Dao
interface SegmentDao {
    @Insert
    suspend fun insertAll(segments: List<ActivitySegmentEntity>)

    @Query("SELECT * FROM activity_segment WHERE sessionId = :sessionId ORDER BY kind, idx")
    suspend fun forSession(sessionId: Long): List<ActivitySegmentEntity>

    @Query("SELECT * FROM activity_segment WHERE kind = 'STOP' AND sessionId IN (SELECT id FROM activity_session WHERE startTime >= :from AND startTime < :to)")
    suspend fun stopsBetween(from: Long, to: Long): List<ActivitySegmentEntity>

    @Query("DELETE FROM activity_segment WHERE sessionId = :sessionId AND kind != 'PAUSE'")
    suspend fun deleteDerived(sessionId: Long)
}

@Dao
interface StatisticDao {
    @Insert
    suspend fun insertAll(stats: List<ActivityStatisticEntity>)

    @Query("SELECT * FROM activity_statistic WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: Long): List<ActivityStatisticEntity>

    @Query("DELETE FROM activity_statistic WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goal WHERE archived = 0 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal ORDER BY createdAt DESC")
    suspend fun getAll(): List<GoalEntity>

    @Query("SELECT * FROM goal WHERE id = :id")
    suspend fun get(id: Long): GoalEntity?

    @Insert
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("SELECT uuid FROM goal")
    suspend fun allUuids(): List<String>

    /** REPLACE resolves the unique (goalId, periodStart) index: one row per goal period. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: GoalProgressEntity)

    @Query("SELECT * FROM goal_progress WHERE goalId = :goalId AND periodStart = :periodStart")
    suspend fun progressFor(goalId: Long, periodStart: Long): GoalProgressEntity?

    @Query("SELECT * FROM goal_progress WHERE goalId = :goalId ORDER BY periodStart DESC")
    fun observeHistory(goalId: Long): Flow<List<GoalProgressEntity>>

    @Query("SELECT * FROM goal_progress ORDER BY periodStart DESC")
    suspend fun allProgress(): List<GoalProgressEntity>

    @Query("DELETE FROM goal")
    suspend fun clear()
}

@Dao
interface ExportMetadataDao {
    @Insert
    suspend fun insert(meta: ExportMetadataEntity)

    @Query("SELECT * FROM export_metadata ORDER BY timestamp DESC LIMIT 20")
    fun observeRecent(): Flow<List<ExportMetadataEntity>>

    @Query("DELETE FROM export_metadata")
    suspend fun clear()
}
