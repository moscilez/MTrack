package com.moscilez.track.data;

import android.os.Handler;
import android.os.HandlerThread;

/** One serialized IO queue for Room and recording commands. Never blocks the UI. */
public final class Store {
 private static final HandlerThread THREAD=new HandlerThread("mtrack-storage");
 static { THREAD.start(); }
 public static final Handler IO=new Handler(THREAD.getLooper());
 private Store() {}
}
