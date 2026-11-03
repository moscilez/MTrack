package com.moscilez.track.data;
import androidx.room.Entity; import androidx.room.PrimaryKey;
@Entity(tableName="activity_points", indices={@androidx.room.Index("sessionId")}) public class ActivityPoint { @PrimaryKey(autoGenerate=true) public long id; public long sessionId; public double latitude,longitude,altitude,speed,accuracy; public long timestamp; public int segment; }
