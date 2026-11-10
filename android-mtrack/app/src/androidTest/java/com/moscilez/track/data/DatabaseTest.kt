package com.moscilez.track.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moscilez.track.data.db.ActivitySessionEntity
import com.moscilez.track.data.db.MTrackDatabase
import com.moscilez.track.data.db.UserSettingsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private lateinit var db: MTrackDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, MTrackDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun session(uuid: String, status: String, start: Long) = ActivitySessionEntity(
        uuid = uuid, type = "RUN", title = "Run", status = status, startTime = start, createdAt = start, updatedAt = start,
    )

    @Test
    fun unfinishedSessionIsFoundForRecovery() = runBlocking {
        val dao = db.sessionDao()
        dao.insert(session("a", "FINISHED", 1_000))
        val id = dao.insert(session("b", "RUNNING", 2_000))
        assertEquals(id, dao.getUnfinished()?.id)
        assertEquals(setOf("a", "b"), dao.allUuids().toSet())
        assertEquals(1, dao.getAllFinished().size)
    }

    @Test
    fun settingsAreSingleRow() = runBlocking {
        val dao = db.settingsDao()
        assertNull(dao.get())
        dao.upsert(UserSettingsEntity(units = "IMPERIAL"))
        dao.upsert(UserSettingsEntity(units = "METRIC"))
        assertEquals("METRIC", dao.get()?.units)
    }
}

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MTrackDatabase::class.java)

    @Test
    fun migrate1To2KeepsDataAndAddsColumns() {
        helper.createDatabase(name, 1).close()
        val db = helper.runMigrationsAndValidate(name, 2, true, MTrackDatabase.MIGRATION_1_2)
        db.query("PRAGMA table_info(activity_point)").use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(c.getColumnIndexOrThrow("name")) == "segment") found = true
            assertTrue(found)
        }
        db.close()
    }
}
