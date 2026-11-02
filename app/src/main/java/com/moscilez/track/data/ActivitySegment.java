package com.moscilez.track.data;
import androidx.room.Entity; import androidx.room.PrimaryKey;
@Entity(tableName="activity_segments") public class ActivitySegment { @PrimaryKey(autoGenerate=true) public long id; public long sessionId; public long startedAt; public long endedAt; public double distanceMeters; public double averageSpeed; public boolean stopped; }
