package com.moscilez.track.data;
import androidx.room.Entity; import androidx.room.PrimaryKey;
@Entity(tableName="goals") public class Goal { @PrimaryKey(autoGenerate=true) public long id; public String kind; public String activityType; public double target; public long periodStart; public long periodEnd; public boolean completed; }
