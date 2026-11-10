package com.moscilez.track.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserProfileEntity::class,
        UserSettingsEntity::class,
        ActivitySessionEntity::class,
        ActivityPointEntity::class,
        ActivitySegmentEntity::class,
        ActivityStatisticEntity::class,
        GoalEntity::class,
        GoalProgressEntity::class,
        ExportMetadataEntity::class,
    ],
    version = MTrackDatabase.VERSION,
    exportSchema = true,
)
abstract class MTrackDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun settingsDao(): SettingsDao
    abstract fun sessionDao(): SessionDao
    abstract fun pointDao(): PointDao
    abstract fun segmentDao(): SegmentDao
    abstract fun statisticDao(): StatisticDao
    abstract fun goalDao(): GoalDao
    abstract fun exportDao(): ExportMetadataDao

    companion object {
        const val VERSION = 2
        const val NAME = "mtrack.db"

        /**
         * v1 -> v2: adds the privacy trimming flag and the pause-segment column for points.
         * Every schema change must ship an explicit migration; destructive fallback is
         * deliberately disabled so user history is never silently wiped.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN privacyTrimRouteEnds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE activity_point ADD COLUMN segment INTEGER NOT NULL DEFAULT 0")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): MTrackDatabase =
            Room.databaseBuilder(context, MTrackDatabase::class.java, NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
