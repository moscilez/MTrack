package com.moscilez.track.data;

import android.content.Context;
import androidx.room.*;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities={ActivitySession.class,ActivityPoint.class,ActivitySegment.class,UserProfile.class,Goal.class,UserSettings.class},version=3,exportSchema=false)
public abstract class MTrackDatabase extends RoomDatabase {
 public abstract SessionDao sessions();
 public abstract ProfileDao profiles();
 public abstract PointDao points();
 public abstract GoalDao goals();
 public abstract SettingsDao settings();
 private static volatile MTrackDatabase INSTANCE;
 public static final Migration MIGRATION_2_3 = new Migration(2,3) {
  @Override public void migrate(SupportSQLiteDatabase db) {
   db.execSQL("ALTER TABLE activity_sessions ADD COLUMN paused INTEGER NOT NULL DEFAULT 0");
   db.execSQL("ALTER TABLE activity_sessions ADD COLUMN liked INTEGER NOT NULL DEFAULT 0");
   db.execSQL("ALTER TABLE activity_sessions ADD COLUMN savedRoute INTEGER NOT NULL DEFAULT 0");
   db.execSQL("ALTER TABLE activity_points ADD COLUMN segment INTEGER NOT NULL DEFAULT 0");
   db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_points_sessionId ON activity_points(sessionId)");
   // v2 stored maximum and point speeds in m/s; v3 consistently uses km/h.
   db.execSQL("UPDATE activity_sessions SET maxSpeed=maxSpeed*3.6");
   db.execSQL("UPDATE activity_points SET speed=speed*3.6");
   db.execSQL("UPDATE activity_sessions SET paused=1 WHERE endedAt=0");
  }
 };
 public static MTrackDatabase get(Context c) {
  if(INSTANCE==null) synchronized(MTrackDatabase.class) {
   if(INSTANCE==null) INSTANCE=Room.databaseBuilder(c.getApplicationContext(),MTrackDatabase.class,"mtrack.db")
    .addMigrations(MIGRATION_2_3).build();
  }
  return INSTANCE;
 }
 public void deleteSession(ActivitySession session) {
  runInTransaction(()->{points().deleteForSession(session.id); sessions().delete(session);});
 }
}
