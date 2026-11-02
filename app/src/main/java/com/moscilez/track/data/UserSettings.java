package com.moscilez.track.data;
import androidx.room.Entity; import androidx.room.PrimaryKey;
@Entity(tableName="user_settings") public class UserSettings { @PrimaryKey public int id=1; public String theme="system"; public String language="ru"; public String units="metric"; public long gpsIntervalMs=5000; public float minDistanceMeters=5; public boolean autoPause=true; public boolean notifications=true; }
