package com.moscilez.track.data;
import androidx.room.*; import java.util.*;
@Dao public interface GoalDao { @Query("SELECT * FROM goals ORDER BY periodEnd ASC") List<Goal> all(); @Insert long insert(Goal goal); @Update void update(Goal goal); @Delete void delete(Goal goal); }
