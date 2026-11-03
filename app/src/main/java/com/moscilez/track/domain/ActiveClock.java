package com.moscilez.track.domain;

/** Monotonic active duration. Wall-clock changes and pauses never add time. */
public final class ActiveClock {
 private long total, anchor; private boolean running;
 public ActiveClock(long savedDuration, long now, boolean active) {
  total=Math.max(0,savedDuration); anchor=now; running=active;
 }
 public long value(long now) { return total+(running?Math.max(0,now-anchor):0); }
 public void pause(long now) { total=value(now); anchor=now; running=false; }
 public void resume(long now) { if(!running) { anchor=now; running=true; } }
}
