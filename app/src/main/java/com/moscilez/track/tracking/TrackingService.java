package com.moscilez.track.tracking;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.moscilez.track.MainActivity;
import com.moscilez.track.R;
import com.moscilez.track.data.*;
import com.moscilez.track.domain.*;
import java.util.*;

/** All mutable recording state and database access are serialized on Store.IO. */
public class TrackingService extends Service implements LocationListener {
 public static final String START="start", PAUSE="pause", RESUME="resume", FINISH="finish";
 public static volatile boolean alive;
 public static volatile int problem;
 public static volatile long lastFixElapsed;
 private MTrackDatabase db;
 private LocationManager locations;
 private ActivitySession session;
 private ActiveClock clock;
 private ActivityPoint last;
 private int segment;
 private boolean listening, destroyed, foregroundReady;
 private final Runnable tick=new Runnable() {
  @Override public void run() {
   if(destroyed) return;
   if(session!=null&&session.endedAt==0) {
    try { checkpoint(); notifyState(); }
    catch(Exception e) { storageFailure(); }
   }
   Store.IO.postDelayed(this,1000);
  }
 };
 @Override public void onCreate() {
  super.onCreate(); alive=true; problem=0; lastFixElapsed=0;
  db=MTrackDatabase.get(this); locations=(LocationManager)getSystemService(LOCATION_SERVICE);
  if(!hasPermission()){problem=R.string.permission_denied;stopSelf();return;}
  if(!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)){problem=R.string.gps_disabled;stopSelf();return;}
  NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
  manager.createNotificationChannel(new NotificationChannel("tracking",getString(R.string.channel),NotificationManager.IMPORTANCE_LOW));
  try {startForeground(7,notification());foregroundReady=true;}
  catch(RuntimeException e){problem=R.string.start_error;stopSelf();return;}
  Store.IO.post(()->{
   try {
    for(ActivitySession row:db.sessions().all()) if(row.endedAt==0) {session=row;break;}
    if(session!=null) {
     session.paused=true;
     clock=new ActiveClock(session.durationMs,SystemClock.elapsedRealtime(),false);
     List<ActivityPoint> ps=db.points().forSession(session.id);
     last=ps.isEmpty()?null:ps.get(ps.size()-1); segment=last==null?0:last.segment+1;
     db.sessions().update(session); problem=R.string.recovered;
    }
   } catch(Exception e) { storageFailure(); }
  });
  Store.IO.post(tick);
 }
 @Override public int onStartCommand(Intent intent,int flags,int startId) {
  if(!foregroundReady||!hasPermission()){if(foregroundReady)problem=R.string.permission_denied;stopSelf();return START_NOT_STICKY;}
  final String action=intent==null?null:intent.getAction();
  final String mode=intent==null?null:intent.getStringExtra("mode");
  Store.IO.post(()->{
   try {
    if(START.equals(action)&&session==null) {
     if(!Arrays.asList("Run","Walk","Cycling").contains(mode)) {stopSelf();return;}
     session=new ActivitySession(mode,System.currentTimeMillis());
     session.id=db.sessions().insert(session);
     clock=new ActiveClock(0,SystemClock.elapsedRealtime(),true); subscribe();
    } else if(PAUSE.equals(action)&&session!=null) { pause(); checkpoint(); }
    else if(RESUME.equals(action)&&session!=null) {
     if(!hasPermission()) {problem=R.string.permission_denied;return;}
     if(!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {problem=R.string.gps_disabled;return;}
     if(session.paused) {segment++;lastFixElapsed=0;clock.resume(SystemClock.elapsedRealtime());session.paused=false;problem=0;subscribe();checkpoint();}
    } else if(FINISH.equals(action)&&session!=null) {
     pause(); session.durationMs=clock.value(SystemClock.elapsedRealtime());
     session.avgSpeed=TrackMetrics.averageSpeed(session.distanceMeters,session.durationMs);
     if(!Double.isFinite(session.avgSpeed))session.avgSpeed=0;
     session.endedAt=System.currentTimeMillis();
     try {db.sessions().update(session);} catch(Exception e){session.endedAt=0;throw e;}
     stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    } else if(session==null) stopSelf();
    notifyState();
   } catch(Exception e) {storageFailure();if(session==null||session.id==0){session=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
  });
  return START_STICKY;
 }
 private boolean hasPermission() {return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
 private void subscribe() {
  if(!hasPermission()){pause();problem=R.string.permission_denied;return;}
  try {
   locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,0,this,Store.IO.getLooper());listening=true;
   if(!locations.isProviderEnabled(LocationManager.GPS_PROVIDER))problem=R.string.gps_disabled;
  } catch(SecurityException e) {pause();problem=R.string.permission_denied;}
  catch(IllegalArgumentException e) {pause();problem=R.string.gps_disabled;}
 }
 private void pause() {
  if(clock!=null)clock.pause(SystemClock.elapsedRealtime());
  if(session!=null)session.paused=true;
  if(listening){locations.removeUpdates(this);listening=false;}
 }
 private void checkpoint() {
  session.durationMs=clock.value(SystemClock.elapsedRealtime());
  session.avgSpeed=TrackMetrics.averageSpeed(session.distanceMeters,session.durationMs);
  if(!Double.isFinite(session.avgSpeed)) session.avgSpeed=0;
  db.sessions().update(session);
 }
 private void storageFailure() {pause();problem=R.string.storage_error;notifyState();}
 @Override public void onLocationChanged(Location l) {
  if(session==null||session.paused||destroyed)return;
  // Android locations include monotonic timestamps: reject cached/stale fixes.
  long age=SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos();
  if(age<0||age>15000000000L)return;
  ActivityPoint p=new ActivityPoint();p.sessionId=session.id;p.latitude=l.getLatitude();p.longitude=l.getLongitude();
  // Legacy schema has a non-null altitude column; unknown altitude is never displayed or exported.
  p.accuracy=l.hasAccuracy()?l.getAccuracy():0;p.altitude=l.hasAltitude()?l.getAltitude():0;
  p.timestamp=l.getTime();p.segment=segment;
  if(TrackMetrics.valid(p))lastFixElapsed=SystemClock.elapsedRealtime();
  if(!TrackMetrics.accept(last,p,session.type))return;
  double delta=last!=null&&TrackMetrics.connected(last,p)?TrackMetrics.meters(last,p):0;
  double speed=last!=null&&delta>0?delta*3600/(p.timestamp-last.timestamp):0;
  p.speed=speed;
  double oldDistance=session.distanceMeters,oldMax=session.maxSpeed;
  session.distanceMeters+=delta;session.maxSpeed=Math.max(session.maxSpeed,speed);
  try {
   db.runInTransaction(()->{db.points().insert(p);checkpoint();});
   last=p;problem=0;
  } catch(Exception e){session.distanceMeters=oldDistance;session.maxSpeed=oldMax;storageFailure();}
 }
 @Override public void onProviderDisabled(String provider) {problem=R.string.gps_disabled;}
 @Override public void onProviderEnabled(String provider) {problem=0;}
 @Override public void onStatusChanged(String provider,int status,Bundle extras) {}
 private Notification notification() {
  Intent open=new Intent(this,MainActivity.class).putExtra("tracking",true);
  PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  boolean paused=session!=null&&session.paused;
  NotificationCompat.Builder b=new NotificationCompat.Builder(this,"tracking")
   .setSmallIcon(R.drawable.ic_tracking).setContentTitle(getString(R.string.app_name))
   .setContentText(getString(problem==R.string.storage_error?R.string.storage_error:paused?R.string.paused_notification:R.string.recording_notification))
   .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE);
  if(session!=null) {
   Intent control=new Intent(this,TrackingService.class).setAction(paused?RESUME:PAUSE);
   PendingIntent pi=PendingIntent.getService(this,1,control,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
   b.addAction(0,getString(paused?R.string.resume:R.string.pause),pi);
  }
  return b.build();
 }
 private void notifyState() {if(!destroyed&&(session==null||session.endedAt==0))((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(7,notification());}
 @Override public void onDestroy() {
  alive=false;
  Store.IO.post(()->{destroyed=true;Store.IO.removeCallbacks(tick);pause();if(session!=null&&session.endedAt==0)try{checkpoint();}catch(Exception ignored){}});
  super.onDestroy();
 }
 @Override public IBinder onBind(Intent intent) {return null;}
}
