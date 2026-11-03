package com.moscilez.track.domain;

import com.moscilez.track.data.ActivityPoint;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TrackMetricsTest {
 private ActivityPoint point(double lon,long time,int segment) {ActivityPoint p=new ActivityPoint();p.latitude=0;p.longitude=lon;p.timestamp=time;p.segment=segment;p.accuracy=5;return p;}
 @Test public void durationExcludesPauseAndRepeatedResume() {
  ActiveClock c=new ActiveClock(2000,1000,true);
  assertEquals(6000,c.value(5000));c.pause(5000);
  assertEquals(6000,c.value(50000));c.pause(50000);
  c.resume(60000);c.resume(61000);assertEquals(9000,c.value(63000));
 }
 @Test public void restoredClockDoesNotCountDowntime() {
  ActiveClock c=new ActiveClock(9000,1000000,false);
  assertEquals(9000,c.value(2000000));c.resume(2000000);assertEquals(10000,c.value(2001000));
 }
 @Test public void haversineAndPaceUseActiveDuration() {
  ActivityPoint a=point(0,1000,0),b=point(.001,21000,0);
  double meters=TrackMetrics.distance(Arrays.asList(a,b));assertEquals(111.195,meters,.1);
  assertEquals(12,TrackMetrics.averageSpeed(1000,300000),.0001);
  assertEquals(300,TrackMetrics.pace(1000,300000),.0001);
 }
 @Test public void pauseAndSignalGapsAreNotJoined() {
  assertEquals(0,TrackMetrics.distance(Arrays.asList(point(0,1000,0),point(.001,21000,1))),0);
  assertEquals(0,TrackMetrics.distance(Arrays.asList(point(0,1000,0),point(.001,41000,0))),0);
 }
 @Test public void rejectsJumpsPoorAccuracyAndDuplicateTime() {
  ActivityPoint a=point(0,1000,0),jump=point(1,2000,0),bad=point(.001,21000,0);
  bad.accuracy=90;assertFalse(TrackMetrics.accept(a,jump,"Run"));assertFalse(TrackMetrics.accept(a,bad,"Run"));
  assertFalse(TrackMetrics.accept(a,point(.001,1000,0),"Run"));
  assertTrue(TrackMetrics.accept(a,point(.001,21000,0),"Run"));
 }
 @Test public void stationaryNoiseDoesNotAccumulate() {
  assertFalse(TrackMetrics.accept(point(0,1000,0),point(.000001,6000,0),"Walk"));
 }
 @Test public void recoversWithNewAnchorAfterLongSignalLoss() {
  ActivityPoint a=point(0,1000,0),b=point(1,41000,0);
  assertTrue(TrackMetrics.accept(a,b,"Run"));
  assertEquals(0,TrackMetrics.distance(Arrays.asList(a,b)),0);
 }
 @Test public void unavailableMetricsAreUnknown() {
  assertTrue(Double.isNaN(TrackMetrics.pace(0,1000)));
  assertTrue(Double.isNaN(TrackMetrics.averageSpeed(20,0)));
 }
}
