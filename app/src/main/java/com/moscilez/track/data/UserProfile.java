package com.moscilez.track.data;
import androidx.room.Entity; import androidx.room.PrimaryKey;
@Entity(tableName="user_profile") public class UserProfile { @PrimaryKey public int id=1; public String name=""; public String units="metric"; public String language="ru"; public float heightCm; public float weightKg; }
