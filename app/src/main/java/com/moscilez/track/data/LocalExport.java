package com.moscilez.track.data;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Explicit user-requested portable export, including sensitive route coordinates. */
public final class LocalExport {
 private LocalExport(){}
 public static void write(OutputStream stream,MTrackDatabase db)throws Exception {
  if(stream==null)throw new IOException("No output stream");
  // Streaming per workout keeps export memory bounded by the largest route.
  Writer out=new OutputStreamWriter(stream,StandardCharsets.UTF_8);
  out.write("{\"format\":\"mtrack\",\"version\":1,\"sessions\":[");
  boolean first=true;
  for(ActivitySession s:db.sessions().all()) {
   if(!first)out.write(",");first=false;
   JSONObject j=new JSONObject();j.put("id",s.id);j.put("type",s.type);j.put("startedAt",s.startedAt);j.put("endedAt",s.endedAt);
   j.put("durationMs",s.durationMs);j.put("distanceMeters",s.distanceMeters);j.put("averageKmh",s.avgSpeed);j.put("maxKmh",s.maxSpeed);
   j.put("liked",s.liked);j.put("savedRoute",s.savedRoute);j.put("note",s.note);
   JSONArray points=new JSONArray();
   for(ActivityPoint p:db.points().forSession(s.id)) {
    JSONObject item=new JSONObject();item.put("latitude",p.latitude);item.put("longitude",p.longitude);item.put("timestamp",p.timestamp);
    item.put("accuracyMeters",p.accuracy);item.put("segment",p.segment);points.put(item);
   }
   j.put("points",points);out.write(j.toString());
  }
  out.write("]}");out.flush();
 }
}
