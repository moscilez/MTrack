package com.moscilez.track.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PersistenceTest {
 private Context context;private MTrackDatabase db;
 private final String name="persistence-test.db";
 @Before public void setup(){context=ApplicationProvider.getApplicationContext();context.deleteDatabase(name);open();}
 private void open(){db=Room.databaseBuilder(context,MTrackDatabase.class,name).addMigrations(MTrackDatabase.MIGRATION_2_3).build();}
 @After public void clean(){db.close();context.deleteDatabase(name);}
 @Test public void workoutRouteAndSettingsSurviveReopenAndDeleteTogether(){
  ActivitySession s=new ActivitySession("Run",1000);s.durationMs=300000;s.distanceMeters=1000;s.endedAt=301000;s.liked=true;s.savedRoute=true;s.id=db.sessions().insert(s);
  ActivityPoint p=new ActivityPoint();p.sessionId=s.id;p.latitude=55;p.longitude=37;p.timestamp=1000;p.accuracy=5;p.segment=2;db.points().insert(p);
  UserSettings cfg=new UserSettings();cfg.units="imperial";cfg.theme="dark";db.settings().save(cfg);
  db.close();open();ActivitySession restored=db.sessions().byId(s.id);
  assertEquals(1000,restored.distanceMeters,0);assertEquals(300000,restored.durationMs);assertTrue(restored.liked);assertTrue(restored.savedRoute);
  assertEquals(2,db.points().forSession(s.id).get(0).segment);assertEquals("imperial",db.settings().get().units);
  db.deleteSession(restored);assertNull(db.sessions().byId(s.id));assertTrue(db.points().forSession(s.id).isEmpty());
 }
 @Test public void migrationPreservesVersionTwoWorkout(){
  db.close();context.deleteDatabase(name);
  SQLiteDatabase old=context.openOrCreateDatabase(name,0,null);
  old.execSQL("CREATE TABLE activity_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,type TEXT,startedAt INTEGER NOT NULL,endedAt INTEGER NOT NULL,durationMs INTEGER NOT NULL,distanceMeters REAL NOT NULL,avgSpeed REAL NOT NULL,maxSpeed REAL NOT NULL,note TEXT)");
  old.execSQL("CREATE TABLE activity_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,sessionId INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,altitude REAL NOT NULL,speed REAL NOT NULL,accuracy REAL NOT NULL,timestamp INTEGER NOT NULL)");
  old.execSQL("CREATE TABLE activity_segments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,sessionId INTEGER NOT NULL,startedAt INTEGER NOT NULL,endedAt INTEGER NOT NULL,distanceMeters REAL NOT NULL,averageSpeed REAL NOT NULL,stopped INTEGER NOT NULL)");
  old.execSQL("CREATE TABLE user_profile (id INTEGER NOT NULL PRIMARY KEY,name TEXT,units TEXT,language TEXT,heightCm REAL NOT NULL,weightKg REAL NOT NULL)");
  old.execSQL("CREATE TABLE goals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,kind TEXT,activityType TEXT,target REAL NOT NULL,periodStart INTEGER NOT NULL,periodEnd INTEGER NOT NULL,completed INTEGER NOT NULL)");
  old.execSQL("CREATE TABLE user_settings (id INTEGER NOT NULL PRIMARY KEY,theme TEXT,language TEXT,units TEXT,gpsIntervalMs INTEGER NOT NULL,minDistanceMeters REAL NOT NULL,autoPause INTEGER NOT NULL,notifications INTEGER NOT NULL)");
  old.execSQL("INSERT INTO activity_sessions VALUES(1,'Run',1000,0,12000,30,9,10,'original')");
  old.setVersion(2);old.close();open();
  ActivitySession s=db.sessions().byId(1);assertEquals("original",s.note);assertEquals(12000,s.durationMs);assertTrue(s.paused);assertEquals(30,s.distanceMeters,0);assertEquals(36,s.maxSpeed,0.0001);
 }
 @Test public void exportContainsPersistedRouteAndMetrics()throws Exception {
  ActivitySession s=new ActivitySession("Walk",1000);s.endedAt=61000;s.durationMs=60000;s.distanceMeters=70;s.id=db.sessions().insert(s);
  ActivityPoint p=new ActivityPoint();p.sessionId=s.id;p.latitude=55.75;p.longitude=37.61;p.accuracy=5;p.timestamp=2000;db.points().insert(p);
  java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();LocalExport.write(out,db);
  org.json.JSONObject json=new org.json.JSONObject(out.toString("UTF-8"));org.json.JSONObject record=json.getJSONArray("sessions").getJSONObject(0);
  assertEquals(70,record.getDouble("distanceMeters"),0);assertEquals(60000,record.getLong("durationMs"));
  assertEquals(55.75,record.getJSONArray("points").getJSONObject(0).getDouble("latitude"),0);
 }
}
