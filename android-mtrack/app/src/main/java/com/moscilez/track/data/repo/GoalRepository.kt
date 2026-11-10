package com.moscilez.track.data.repo

import com.moscilez.track.data.db.GoalEntity
import com.moscilez.track.data.db.GoalProgressEntity
import com.moscilez.track.data.db.MTrackDatabase
import com.moscilez.track.domain.model.ActivityStat
import com.moscilez.track.domain.model.GoalStatus
import com.moscilez.track.domain.stats.GoalEvaluator
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import java.util.UUID

class GoalRepository(private val db: MTrackDatabase) {
    private val dao = db.goalDao()

    val active: Flow<List<GoalEntity>> = dao.observeActive()
    val all: Flow<List<GoalEntity>> = dao.observeAll()

    fun history(goalId: Long): Flow<List<GoalProgressEntity>> = dao.observeHistory(goalId)

    suspend fun create(goal: GoalEntity): Long = dao.insert(goal.copy(uuid = goal.uuid.ifEmpty { UUID.randomUUID().toString() }))
    suspend fun update(goal: GoalEntity) = dao.update(goal)
    suspend fun delete(goal: GoalEntity) = dao.delete(goal)
    suspend fun get(id: Long) = dao.get(id)

    /**
     * Evaluates goals for their current period and persists one progress row per period,
     * which forms the goal's history.
     */
    suspend fun evaluateAndRecord(goals: List<GoalEntity>, stats: List<ActivityStat>, now: Long, zone: ZoneId): List<GoalStatus> =
        goals.map { g ->
            val status = GoalEvaluator.evaluate(g.toSpec(), stats, now, zone)
            val existing = dao.progressFor(g.id, status.periodStart)
            if (existing == null || existing.value != status.value || existing.target != g.target) {
                dao.upsertProgress(
                    GoalProgressEntity(
                        goalId = g.id, periodStart = status.periodStart, periodEnd = status.periodEnd,
                        value = status.value, target = g.target, achieved = status.achieved, recordedAt = now,
                    )
                )
            }
            status
        }
}
