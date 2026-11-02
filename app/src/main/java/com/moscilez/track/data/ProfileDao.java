package com.moscilez.track.data;
import androidx.room.*;
@Dao public interface ProfileDao { @Query("SELECT * FROM user_profile WHERE id=1") UserProfile get(); @Insert(onConflict=OnConflictStrategy.REPLACE) void save(UserProfile profile); }
