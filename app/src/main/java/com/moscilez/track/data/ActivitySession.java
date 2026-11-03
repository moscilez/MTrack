package com.moscilez.track.data;
import androidx.room.Entity; import androidx.room.PrimaryKey;
@Entity(tableName="activity_sessions") public class ActivitySession {
 @PrimaryKey(autoGenerate=true) public long id; public String type; public long startedAt; public long endedAt; public long durationMs; public double distanceMeters; public double avgSpeed; public double maxSpeed; public String note;
 public boolean paused; public boolean liked; public boolean savedRoute;
 public ActivitySession(String type,long startedAt){this.type=type;this.startedAt=startedAt;this.note="";}
}
