package com.moscilez.track.data;
import androidx.room.*; import java.util.*;
@Dao public interface SessionDao { @Query("SELECT * FROM activity_sessions ORDER BY startedAt DESC") List<ActivitySession> all(); @Insert long insert(ActivitySession s); @Update void update(ActivitySession s); @Delete void delete(ActivitySession s); @Query("SELECT * FROM activity_sessions WHERE id=:id") ActivitySession byId(long id); }
