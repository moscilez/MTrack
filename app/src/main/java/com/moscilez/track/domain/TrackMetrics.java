package com.moscilez.track.domain;

import com.moscilez.track.data.ActivityPoint;
import java.util.List;

/** Pure calculations shared by the recorder, route view and tests. Speeds are km/h. */
public final class TrackMetrics {
 private TrackMetrics() {}
 public static double meters(ActivityPoint a, ActivityPoint b) {
  double p1=Math.toRadians(a.latitude), p2=Math.toRadians(b.latitude);
  double dp=p2-p1, dl=Math.toRadians(b.longitude-a.longitude);
  double h=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
  return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)));
 }
 public static boolean valid(ActivityPoint p) {
  return Double.isFinite(p.latitude)&&Double.isFinite(p.longitude)&&Math.abs(p.latitude)<=90&&Math.abs(p.longitude)<=180
   &&p.accuracy>0&&p.accuracy<=40&&p.timestamp>0;
 }
 public static double speedLimit(String type) { return "Cycling".equals(type)?30:12; }
 public static boolean accept(ActivityPoint previous, ActivityPoint p, String type) {
  if(!valid(p)) return false;
  if(previous==null) return true;
  long dt=p.timestamp-previous.timestamp;
  if(dt<=0) return false;
  // Re-anchor after a pause or prolonged loss; never measure across that gap.
  if(p.segment!=previous.segment||dt>30000)return true;
  double distance=meters(previous,p);
  if(distance/(dt/1000d)>speedLimit(type)) return false;
  return distance>=Math.max(3,Math.min(10,(p.accuracy+previous.accuracy)*0.25));
 }
 public static boolean connected(ActivityPoint a,ActivityPoint b) { return a.segment==b.segment&&b.timestamp>a.timestamp&&b.timestamp-a.timestamp<=30000; }
 public static double distance(List<ActivityPoint> points) {
  double total=0; for(int i=1;i<points.size();i++) if(connected(points.get(i-1),points.get(i))) total+=meters(points.get(i-1),points.get(i)); return total;
 }
 public static double averageSpeed(double meters,long durationMs) { return durationMs>0?meters*3600/durationMs:Double.NaN; }
 public static double pace(double meters,long durationMs) { return meters>0&&durationMs>0?durationMs/meters:Double.NaN; }
}
