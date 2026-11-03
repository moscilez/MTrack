package com.moscilez.track.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import com.moscilez.track.R;
import com.moscilez.track.data.ActivityPoint;
import com.moscilez.track.domain.TrackMetrics;
import java.util.*;

/** Offline schematic; no imaginary streets, tiles, requests or API keys. */
public final class RouteView extends View {
 private List<ActivityPoint> points=Collections.emptyList();
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 private final int line, grid;
 public RouteView(Context context) {this(context,Color.rgb(197,234,119),Color.rgb(58,59,52));}
 public RouteView(Context context,int line,int grid) {super(context);this.line=line;this.grid=grid;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
 public void setPoints(List<ActivityPoint> value) {points=value;setContentDescription(getContext().getString(R.string.route_accessibility,value.size()));invalidate();}
 private double lon(ActivityPoint p) {double d=p.longitude-points.get(0).longitude;return ((d+540)%360)-180;}
 @Override protected void onDraw(Canvas c) {
  super.onDraw(c);float density=getResources().getDisplayMetrics().density;
  paint.setColor(grid);paint.setStrokeWidth(density);
  for(float x=24*density;x<getWidth();x+=32*density)c.drawLine(x,0,x,getHeight(),paint);
  for(float y=24*density;y<getHeight();y+=32*density)c.drawLine(0,y,getWidth(),y,paint);
  if(points.isEmpty())return;
  double cos=Math.max(0.01,Math.cos(Math.toRadians(points.get(0).latitude)));
  double minX=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,minY=Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
  for(ActivityPoint p:points){double x=lon(p)*cos,y=-p.latitude;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
  float pad=28*density;
  double scale=Math.min((getWidth()-pad*2)/Math.max(maxX-minX,0.0001),(getHeight()-pad*2)/Math.max(maxY-minY,0.0001));
  double cx=(minX+maxX)/2,cy=(minY+maxY)/2;
  paint.setColor(line);paint.setStrokeWidth(3*density);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
  float lastX=0,lastY=0;
  for(int i=0;i<points.size();i++) {
   ActivityPoint p=points.get(i);float x=(float)(getWidth()/2d+(lon(p)*cos-cx)*scale),y=(float)(getHeight()/2d+(-p.latitude-cy)*scale);
   if(i>0&&TrackMetrics.connected(points.get(i-1),p))c.drawLine(lastX,lastY,x,y,paint);
   if(i==0)c.drawCircle(x,y,6*density,paint);
   if(i==points.size()-1)c.drawRect(x-5*density,y-5*density,x+5*density,y+5*density,paint);
   lastX=x;lastY=y;
  }
 }
}
