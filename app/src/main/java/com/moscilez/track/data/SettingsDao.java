package com.moscilez.track.data;
import androidx.room.*;
@Dao public interface SettingsDao { @Query("SELECT * FROM user_settings WHERE id=1") UserSettings get(); @Insert(onConflict=OnConflictStrategy.REPLACE) void save(UserSettings settings); }
