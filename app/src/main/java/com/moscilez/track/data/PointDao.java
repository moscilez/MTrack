package com.moscilez.track.data;
import androidx.room.*; import java.util.*;
@Dao public interface PointDao { @Insert long insert(ActivityPoint p); @Insert void insertAll(List<ActivityPoint> points); @Query("SELECT * FROM activity_points WHERE sessionId=:sessionId ORDER BY timestamp ASC") List<ActivityPoint> forSession(long sessionId); @Query("DELETE FROM activity_points WHERE sessionId=:sessionId") void deleteForSession(long sessionId); }
