package com.moscilez.track.domain;
import android.location.Location; import com.moscilez.track.data.ActivityPoint; import java.util.*;
public final class TrackMetrics {
 private TrackMetrics(){}
 public static void addPoint(List<ActivityPoint> points, Location l, long sessionId){ActivityPoint p=new ActivityPoint();p.sessionId=sessionId;p.latitude=l.getLatitude();p.longitude=l.getLongitude();p.altitude=l.hasAltitude()?l.getAltitude():0;p.speed=l.hasSpeed()?l.getSpeed():0;p.accuracy=l.hasAccuracy()?l.getAccuracy():0;p.timestamp=l.getTime();points.add(p);}
 public static double distance(List<ActivityPoint> ps){double total=0;float[] r=new float[1];for(int i=1;i<ps.size();i++){Location.distanceBetween(ps.get(i-1).latitude,ps.get(i-1).longitude,ps.get(i).latitude,ps.get(i).longitude,r);if(r[0]>=1&&r[0]<1000)total+=r[0];}return total;}
 public static double maxSpeed(List<ActivityPoint> ps){double max=0;for(ActivityPoint p:ps)max=Math.max(max,p.speed);return max;}
 public static double averageSpeed(List<ActivityPoint> ps){if(ps.size()<2)return 0;long t=ps.get(ps.size()-1).timestamp-ps.get(0).timestamp;return t<=0?0:distance(ps)/(t/3600000d);}
 public static double elevationGain(List<ActivityPoint> ps){double gain=0;for(int i=1;i<ps.size();i++){double d=ps.get(i).altitude-ps.get(i-1).altitude;if(d>1&&d<100)gain+=d;}return gain;}
}
