package com.moscilez.track;

import android.Manifest;
import android.content.*;
import android.location.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.moscilez.track.data.*;
import com.moscilez.track.tracking.TrackingService;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Uses injected GPS only in tests. Production has no demo points or simulated metrics. */
@RunWith(AndroidJUnit4.class)
public class RecordingFlowTest {
 private Context context;private MTrackDatabase db;private long id;
 private ActivityScenario<MainActivity> scenario;private LocationManager manager;
 private void shell(String command)throws Exception {try(ParcelFileDescriptor p=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(p)){byte[] buffer=new byte[1024];while(in.read(buffer)!=-1){}}}
 private <T> T io(Callable<T> task)throws Exception {FutureTask<T> future=new FutureTask<>(task);Store.IO.post(future);return future.get(10,TimeUnit.SECONDS);}
 private void until(BooleanSupplier check)throws Exception {long end=SystemClock.elapsedRealtime()+12000;while(SystemClock.elapsedRealtime()<end){if(check.getAsBoolean())return;Thread.sleep(100);}fail("Timed out waiting for recording state");}
 private ActivitySession row(){try{return io(()->db.sessions().byId(id));}catch(Exception e){throw new AssertionError(e);}}
 private View find(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText())&&view.isClickable())return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
 private void click(int label)throws Exception {until(()->{AtomicBoolean clicked=new AtomicBoolean();scenario.onActivity(a->{View view=find(a.getWindow().getDecorView(),a.getString(label));if(view!=null){view.performClick();clicked.set(true);}});return clicked.get();});}
 private void fix(double longitude)throws Exception {Location l=new Location(LocationManager.GPS_PROVIDER);l.setLatitude(55.75);l.setLongitude(longitude);l.setAccuracy(5);l.setTime(System.currentTimeMillis());l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,l);Thread.sleep(2300);}
 @Before public void setup()throws Exception {
  context=ApplicationProvider.getApplicationContext();db=MTrackDatabase.get(context);
  Assume.assumeTrue(io(()->{for(ActivitySession s:db.sessions().all())if(s.endedAt==0)return false;return true;}));
  shell("pm grant com.moscilez.track android.permission.ACCESS_FINE_LOCATION");shell("pm grant com.moscilez.track android.permission.ACCESS_COARSE_LOCATION");
  shell("appops set com.moscilez.track android:mock_location allow");
  if(Build.VERSION.SDK_INT>=33)shell("pm grant com.moscilez.track android.permission.POST_NOTIFICATIONS");
  manager=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
  manager.addTestProvider(LocationManager.GPS_PROVIDER,false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE);manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true);
  scenario=ActivityScenario.launch(MainActivity.class);
 }
 @After public void cleanup()throws Exception {
  if(manager==null)return; // A skipped test must not stop a user's active recording.
  context.stopService(new Intent(context,TrackingService.class));
  if(scenario!=null)scenario.close();
  if(manager!=null)manager.removeTestProvider(LocationManager.GPS_PROVIDER);
  shell("appops set com.moscilez.track android:mock_location default");
  if(id>0)io(()->{ActivitySession s=db.sessions().byId(id);if(s!=null)db.deleteSession(s);return null;});
 }
 @Test public void recordsPausesSurvivesRecreationAndFinishes()throws Exception {
  fix(37.6100);
  click(R.string.start);click(R.string.run);
  until(()->{try{id=io(()->{for(ActivitySession s:db.sessions().all())if(s.endedAt==0)return s.id;return 0L;});return id>0;}catch(Exception e){throw new AssertionError(e);}});
  fix(37.6100);fix(37.61015);fix(37.61030);
  String diagnostic="distance="+row().distanceMeters+", points="+io(()->db.points().forSession(id).size())+", problem="+TrackingService.problem;
  assertTrue(diagnostic,row().distanceMeters>10);assertTrue(diagnostic,row().distanceMeters<35);
  scenario.recreate();click(R.string.pause);until(()->row().paused);
  long paused=row().durationMs;Thread.sleep(2300);assertEquals(paused,row().durationMs);
  assertEquals(id,row().id);
  context.stopService(new Intent(context,TrackingService.class));until(()->!TrackingService.alive);
  scenario.recreate();Thread.sleep(500);click(R.string.resume);until(()->!row().paused);
  scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
  fix(37.6120);double before=row().distanceMeters;fix(37.61215);
  scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
  assertTrue(row().distanceMeters>before);assertTrue(row().distanceMeters<60);
  scenario.onActivity(a->a.startForegroundService(new Intent(a,TrackingService.class).setAction(TrackingService.FINISH)));
  until(()->row().endedAt>0);assertTrue(row().durationMs>0);
  assertTrue(io(()->db.points().forSession(id).size())>=5);
  scenario.close();scenario=ActivityScenario.launch(MainActivity.class);click(R.string.history);click(R.string.details);
  // Opening a saved workout after a fresh Activity uses persisted records.
  Thread.sleep(1000);assertTrue(row().endedAt>0);
  try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"test-summary.png"))){InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
 }
}
