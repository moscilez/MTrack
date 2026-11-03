package com.moscilez.track;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.moscilez.track.data.*;
import com.moscilez.track.domain.TrackMetrics;
import com.moscilez.track.tracking.TrackingService;
import com.moscilez.track.ui.RouteView;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
 private static final int LOCATION=42,NOTIFICATIONS=43,EXPORT=44;
 private final Handler main=new Handler(Looper.getMainLooper());
 private MTrackDatabase db;
 private List<ActivitySession> rows=new ArrayList<>();
 private List<ActivityPoint> points=new ArrayList<>();
 private ActivitySession active;
 private UserSettings settings=new UserSettings();
 private UserProfile profile=new UserProfile();
 private LinearLayout root,content;
 private TextView liveTime,liveDistance,livePace,liveStatus,liveGps;
 private MaterialButton pauseButton,finishButton;
 private RouteView liveRoute;
 private String screen="home",filter="All",pendingMode;
 private long detailId,recordingId;
 private boolean busy,loaded,month,finishing,permissionNotice,needsRender;
 private int bg,surface,fg,muted,border,accent;
 private final Runnable poll=new Runnable(){public void run(){load(false);main.postDelayed(this,1000);}};
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);db=MTrackDatabase.get(this);settings=defaultSettings();
  if(state!=null){screen=state.getString("screen","home");filter=state.getString("filter","All");detailId=state.getLong("detail");recordingId=state.getLong("recording");pendingMode=state.getString("pending");month=state.getBoolean("month");}
  if(getIntent().getBooleanExtra("tracking",false))screen="tracking";
  palette();shell(R.string.app_name);content.addView(text(R.string.loading,18,muted));load(true);
  getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){goBack();}});
 }
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("tracking",false))navigate("tracking");}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("screen",screen);out.putString("filter",filter);out.putLong("detail",detailId);out.putLong("recording",recordingId);out.putString("pending",pendingMode);out.putBoolean("month",month);}
 @Override protected void onStart(){super.onStart();main.post(poll);}
 @Override protected void onStop(){main.removeCallbacks(poll);super.onStop();}
 private void load(boolean render) {
  needsRender|=render;if(busy)return;busy=true;
  final String requested=screen;final long requestedId=detailId;
  Store.IO.post(()->{
   try {
    List<ActivitySession> data=db.sessions().all();ActivitySession current=null;
    for(ActivitySession s:data)if(s.endedAt==0){current=s;break;}
    UserSettings cfg=db.settings().get();UserProfile person=db.profiles().get();
    long pointId="tracking".equals(requested)&&current!=null?current.id:"details".equals(requested)?requestedId:0;
    List<ActivityPoint> ps=pointId==0?new ArrayList<>():db.points().forSession(pointId);
    ActivitySession selected=current;
    main.post(()->{
     if(isDestroyed())return;busy=false;boolean first=!loaded;loaded=true;
     rows=data;active=selected;settings=cfg==null?defaultSettings():cfg;profile=person==null?new UserProfile():person;
     if(!requested.equals(screen)||requestedId!=detailId){load(true);return;}
     points=ps;
     if("tracking".equals(screen)&&active==null&&recordingId!=0){detailId=recordingId;screen="details";recordingId=0;finishing=false;load(true);return;}
     if(first||needsRender){needsRender=false;render();}else if("tracking".equals(screen))updateTracking();
    });
   } catch(Exception e){main.post(()->{busy=false;if(needsRender||!loaded){needsRender=false;shell(R.string.app_name);content.addView(text(R.string.load_error,18,fg));action(content,R.string.retry,()->load(true),true);}});}
  });
 }
 private UserSettings defaultSettings(){UserSettings s=new UserSettings();s.theme="dark";return s;}
 private void navigate(String destination){if("tracking".equals(screen)&&active!=null&&!"tracking".equals(destination)){confirm(R.string.leave_title,R.string.leave_message,R.string.keep_recording,()->navigateNow(destination));return;}navigateNow(destination);}
 private void navigateNow(String destination){screen=destination;points=new ArrayList<>();render();load(true);}
 private void render(){palette();switch(screen){case "picker":picker();break;case "tracking":tracking();break;case "history":history();break;case "details":details();break;case "progress":progress();break;case "profile":profile();break;case "feed":feed();break;case "routes":routes();break;default:home();}}
 private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private void palette(){boolean light="light".equals(settings.theme)||("system".equals(settings.theme)&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_NO);
  bg=Color.parseColor(light?"#F5F4EE":"#141412");surface=Color.parseColor(light?"#FFFFFF":"#20201D");fg=Color.parseColor(light?"#20211B":"#F4F3E9");muted=Color.parseColor(light?"#606157":"#B4B5A8");border=Color.parseColor(light?"#D8D9CF":"#3A3B34");accent=Color.parseColor(light?"#42651B":"#C5EA77");
 }
 private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),border);return d;}
 private TextView text(int id,int size,int color){return text(getString(id),size,color);}
 private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(6),0,dp(6));t.setLineSpacing(dp(2),1);return t;}
 private TextView label(int id){TextView t=text(id,12,muted);t.setLetterSpacing(.09f);return t;}
 private void shell(int title){
  liveTime=null;liveRoute=null;
  root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
  ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
  boolean light=bg==Color.parseColor("#F5F4EE");
  getWindow().setNavigationBarColor(bg);getWindow().setStatusBarColor(bg);
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),dp(8),dp(20),dp(8));
  if("home".equals(screen)){ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.mtrack_logo);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setContentDescription(getString(R.string.app_logo));header.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));}
  else if(!Arrays.asList("history","progress","profile").contains(screen)){MaterialButton back=button(getString(R.string.back),false);back.setOnClickListener(v->goBack());header.addView(back);}
  TextView heading=text(title,22,fg);heading.setTypeface(null,Typeface.BOLD);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);hp.setMarginStart(dp(10));header.addView(heading,hp);root.addView(header);
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(12),dp(20),dp(20));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout nav=new LinearLayout(this);nav.setPadding(dp(6),dp(4),dp(6),dp(6));nav.setBackground(shape(surface,0));
  String[] destinations={"home","history","progress","profile"};int[] names={R.string.home,R.string.history,R.string.progress,R.string.profile};
  for(int i=0;i<names.length;i++){final String dest=destinations[i];TextView n=text(names[i],12,dest.equals(screen)?accent:muted);n.setGravity(Gravity.CENTER);n.setMinHeight(dp(56));n.setTypeface(null,Typeface.BOLD);n.setBackgroundResource(android.R.drawable.list_selector_background);n.setOnClickListener(v->navigate(dest));nav.addView(n,new LinearLayout.LayoutParams(0,-2,1));}
  root.addView(nav);setContentView(root);
  WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(light);
  WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightNavigationBars(light);
  ViewCompat.requestApplyInsets(root);
 }
 private MaterialButton button(String caption,boolean primary){MaterialButton b=new MaterialButton(this);b.setText(caption);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(54));b.setCornerRadius(dp(12));b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?accent:surface));b.setTextColor(primary?bg:fg);b.setStrokeWidth(dp(1));b.setStrokeColor(android.content.res.ColorStateList.valueOf(primary?accent:border));b.setPadding(dp(14),dp(10),dp(14),dp(10));return b;}
 private MaterialButton action(LinearLayout parent,int id,Runnable run,boolean primary){MaterialButton b=button(getString(id),primary);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(5);lp.bottomMargin=dp(5);parent.addView(b,lp);b.setOnClickListener(v->run.run());return b;}
 private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(shape(surface,16));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);lp.bottomMargin=dp(8);parent.addView(c,lp);return c;}
 private void section(int id){TextView t=text(id,20,fg);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(18),0,dp(6));content.addView(t);}
 private AlertDialog.Builder dialog(){boolean light=bg==Color.parseColor("#F5F4EE");return new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,light?androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert:androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert));}
 private void confirm(int title,int message,int yes,Runnable work){dialog().setTitle(title).setMessage(message).setNegativeButton(R.string.cancel,null).setPositiveButton(yes,(d,w)->work.run()).show();}
 private void toast(int id){Toast.makeText(this,id,Toast.LENGTH_LONG).show();}
 private String sport(String value){return getString("Run".equals(value)?R.string.run:"Walk".equals(value)?R.string.walk:"Cycling".equals(value)?R.string.cycling:R.string.other);}
 private String date(long time){return new SimpleDateFormat("d MMM yyyy · HH:mm",Locale.getDefault()).format(new Date(time));}
 private String duration(long ms){long s=ms/1000;return String.format(Locale.getDefault(),"%02d:%02d:%02d",s/3600,s/60%60,s%60);}
 private boolean imperial(){return "imperial".equals(settings.units);}
 private double divisor(){return imperial()?1609.344:1000;}
 private String distance(double m){return String.format(Locale.getDefault(),"%.2f %s",m/divisor(),getString(imperial()?R.string.mi:R.string.km));}
 private String pace(ActivitySession s){double seconds=TrackMetrics.pace(s.distanceMeters,s.durationMs)*(imperial()?1.609344:1);if(!Double.isFinite(seconds)||seconds>35999)return getString(R.string.unknown);long rounded=Math.round(seconds);return String.format(Locale.getDefault(),"%d:%02d %s",rounded/60,rounded%60,getString(imperial()?R.string.pace_mi:R.string.pace_km));}
 private String speed(double kmh){if(!Double.isFinite(kmh)||kmh<0)return getString(R.string.unknown);return String.format(Locale.getDefault(),"%.1f %s",kmh/(imperial()?1.609344:1),getString(imperial()?R.string.mph:R.string.kmh));}
 private String performance(ActivitySession s){return "Cycling".equals(s.type)?s.distanceMeters>0?speed(TrackMetrics.averageSpeed(s.distanceMeters,s.durationMs)):getString(R.string.unknown):pace(s);}
 private List<ActivitySession> completed(){List<ActivitySession> list=new ArrayList<>();for(ActivitySession s:rows)if(s.endedAt>0)list.add(s);return list;}
 private void activeBanner(){if(active!=null)action(content,R.string.return_recording,()->navigate("tracking"),true);}
 private long periodStart(){Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);if(month)c.set(Calendar.DAY_OF_MONTH,1);else c.add(Calendar.DAY_OF_MONTH,-((c.get(Calendar.DAY_OF_WEEK)+5)%7));return c.getTimeInMillis();}
 private void home(){shell(R.string.app_name);content.addView(label(R.string.local_first));TextView hero=text(R.string.hero,36,fg);hero.setTypeface(null,Typeface.BOLD);content.addView(hero);content.addView(text(R.string.home_hint,16,muted));activeBanner();if(active==null)action(content,R.string.start,()->navigate("picker"),true);
  section(R.string.week);boolean previous=month;month=false;long start=periodStart();month=previous;double meters=0;long ms=0;int count=0;for(ActivitySession s:completed())if(s.startedAt>=start){meters+=s.distanceMeters;ms+=s.durationMs;count++;}LinearLayout summary=card(content);summary.addView(text(distance(meters),32,accent));summary.addView(text(getString(R.string.period_summary,duration(ms),getResources().getQuantityString(R.plurals.training_count,count,count),getString(R.string.private_label)),13,muted));
  action(content,R.string.feed,()->navigate("feed"),false);action(content,R.string.routes,()->navigate("routes"),false);section(R.string.recent);List<ActivitySession> done=completed();if(done.isEmpty())empty(R.string.empty_history);else for(int i=0;i<Math.min(3,done.size());i++)activityCard(done.get(i),false);action(content,R.string.all_history,()->navigate("history"),false);
 }
 private void empty(int message){LinearLayout c=card(content);c.addView(text(R.string.empty_title,22,fg));c.addView(text(message,16,muted));}
 private void picker(){shell(R.string.select_sport);activeBanner();if(active!=null)return;content.addView(text(R.string.permission_explanation,16,muted));String[] types={"Run","Walk","Cycling"};for(String type:types){MaterialButton b=button(sport(type),true);content.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->begin(type));}if(permissionNotice)permissionHelp();}
 private boolean fine(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
 private boolean gps(){return ((LocationManager)getSystemService(LOCATION_SERVICE)).isProviderEnabled(LocationManager.GPS_PROVIDER);}
 private void begin(String mode){
  if(active!=null){navigate("tracking");return;}pendingMode=mode;
  if(!fine()){dialog().setTitle(R.string.permission_title).setMessage(R.string.permission_explanation).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.grant,(d,w)->ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION)).show();return;}
  if(!gps()){confirm(R.string.gps_disabled,R.string.gps_disabled,R.string.gps_settings,()->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));return;}
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationsAsked",false)){
   dialog().setTitle(R.string.notification_title).setMessage(R.string.notification_explanation).setPositiveButton(R.string.continue_action,(d,w)->{getPreferences(0).edit().putBoolean("notificationsAsked",true).apply();ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);}).setNegativeButton(R.string.cancel,null).show();return;
  }
  command(TrackingService.START,mode);pendingMode=null;
 }
 private void command(String action,String mode){
  // Finishing a recovered workout needs no location permission or new foreground service.
  if(TrackingService.FINISH.equals(action)&&active!=null&&(!TrackingService.alive||!fine())) {
   long id=active.id;recordingId=id;
   stopService(new Intent(this,TrackingService.class));
   mutate(()->{ActivitySession s=db.sessions().byId(id);if(s!=null&&s.endedAt==0){s.endedAt=System.currentTimeMillis();s.paused=true;db.sessions().update(s);}});return;
  }
  try {Intent intent=new Intent(this,TrackingService.class).setAction(action);if(mode!=null)intent.putExtra("mode",mode);startForegroundService(intent);if(TrackingService.START.equals(action)){screen="tracking";tracking();}main.postDelayed(()->load(true),350);}catch(RuntimeException e){finishing=false;toast(R.string.start_error);}
 }
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==LOCATION){if(fine()&&pendingMode!=null)begin(pendingMode);else{permissionNotice=true;screen="picker";render();}}else if(code==NOTIFICATIONS&&pendingMode!=null)begin(pendingMode);}
 private void permissionHelp(){LinearLayout c=card(content);c.addView(text(R.string.permission_denied,16,fg));action(c,R.string.app_settings,()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))),false);}
 private void tracking(){shell(R.string.recording);if(active==null){content.addView(text(TrackingService.problem==0?R.string.loading:TrackingService.problem,18,muted));action(content,R.string.retry,()->navigate("picker"),false);return;}recordingId=active.id;
  content.addView(text(sport(active.type),20,fg));liveStatus=label(R.string.recording_live);content.addView(liveStatus);
  LinearLayout stats=card(content);stats.addView(label(R.string.duration));liveTime=text("",40,fg);liveTime.setTypeface(Typeface.MONOSPACE);stats.addView(liveTime);stats.addView(label(R.string.distance));liveDistance=text("",32,accent);stats.addView(liveDistance);stats.addView(label("Cycling".equals(active.type)?R.string.average_speed:R.string.average_pace));livePace=text("",24,fg);stats.addView(livePace);
  liveGps=text("",15,muted);content.addView(liveGps);
  pauseButton=action(content,R.string.pause,()->{if(!fine()){permissionNotice=true;permissionHelp();return;}command(active.paused||!TrackingService.alive?TrackingService.RESUME:TrackingService.PAUSE,null);},true);
  finishButton=action(content,R.string.finish,()->confirm(R.string.finish_title,R.string.finish_message,R.string.finish,()->{finishing=true;command(TrackingService.FINISH,null);updateTracking();}),false);
  liveRoute=routeView(content,points);content.addView(text(R.string.route_hint,12,muted));content.addView(text(R.string.duration_hint,13,muted));action(content,R.string.gps_settings,()->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),false);updateTracking();
 }
 private void updateTracking(){if(active==null){if(TrackingService.problem!=0)tracking();return;}if(liveTime==null){tracking();return;}recordingId=active.id;boolean paused=active.paused||!TrackingService.alive;liveStatus.setText(paused?R.string.paused:R.string.recording_live);liveStatus.setTextColor(accent);liveTime.setText(duration(active.durationMs));liveDistance.setText(points.size()<2?getString(R.string.unknown):distance(active.distanceMeters));livePace.setText(performance(active));
  int status=TrackingService.problem;if(status==0){if(!fine())status=R.string.permission_denied;else if(!gps())status=R.string.gps_disabled;else if(!TrackingService.alive)status=R.string.recovered;else if(TrackingService.lastFixElapsed==0)status=R.string.gps_wait;else if(SystemClock.elapsedRealtime()-TrackingService.lastFixElapsed>15000)status=R.string.gps_lost;else status=R.string.gps_ready;}
  liveGps.setText(status);liveRoute.setPoints(points);pauseButton.setText(paused?R.string.resume:R.string.pause);boolean saving=finishing&&TrackingService.problem!=R.string.storage_error;finishButton.setText(saving?R.string.saving:R.string.finish);finishButton.setEnabled(!saving);
 }
 private RouteView routeView(LinearLayout parent,List<ActivityPoint> ps){RouteView route=new RouteView(this,accent,border);route.setBackground(shape(surface,14));route.setPoints(ps);parent.addView(route,new LinearLayout.LayoutParams(-1,dp(240)));return route;}
 private void history(){shell(R.string.history);activeBanner();filters();List<ActivitySession> list=completed();int count=0;for(ActivitySession s:list)if("All".equals(filter)||filter.equals(s.type)){activityCard(s,false);count++;}if(count==0)empty(list.isEmpty()?R.string.empty_history:R.string.empty_filter);action(content,R.string.start,()->navigate("picker"),true);}
 private void filters(){HorizontalScrollView h=new HorizontalScrollView(this);h.setHorizontalScrollBarEnabled(false);LinearLayout line=new LinearLayout(this);String[] types={"All","Run","Walk","Cycling"};for(String type:types){MaterialButton b=button("All".equals(type)?getString(R.string.all):sport(type),filter.equals(type));line.addView(b);b.setOnClickListener(v->{filter=type;render();});}h.addView(line);content.addView(h);}
 private void activityCard(ActivitySession s,boolean feed){LinearLayout c=card(content);c.addView(text(sport(s.type)+" · "+date(s.startedAt),15,muted));TextView meters=text(s.distanceMeters>0?distance(s.distanceMeters):getString(R.string.unknown),30,fg);meters.setTypeface(Typeface.MONOSPACE);c.addView(meters);c.addView(text(duration(s.durationMs)+"  /  "+performance(s),15,muted));action(c,R.string.details,()->openDetails(s.id),false);if(feed)action(c,s.liked?R.string.liked:R.string.like,()->mutate(()->{ActivitySession latest=db.sessions().byId(s.id);if(latest!=null){latest.liked=!latest.liked;db.sessions().update(latest);}}),false);}
 private void openDetails(long id){detailId=id;points=new ArrayList<>();navigate("details");}
 private ActivitySession selected(){for(ActivitySession s:rows)if(s.id==detailId)return s;return null;}
 private void details(){shell(R.string.summary);ActivitySession s=selected();if(s==null){content.addView(text(R.string.loading,18,muted));action(content,R.string.all_history,()->navigate("history"),false);return;}
  content.addView(label(R.string.saved_local));content.addView(text(sport(s.type),30,fg));content.addView(text(date(s.startedAt),15,muted));routeView(content,points);content.addView(text(points.isEmpty()?R.string.empty_route:R.string.route_hint,12,muted));LinearLayout stats=card(content);metric(stats,R.string.distance,points.size()<2?getString(R.string.unknown):distance(s.distanceMeters));metric(stats,R.string.duration,duration(s.durationMs));metric(stats,"Cycling".equals(s.type)?R.string.average_speed:R.string.average_pace,performance(s));metric(stats,R.string.max_speed,s.maxSpeed>0?speed(s.maxSpeed):getString(R.string.unknown));stats.addView(text(getString(R.string.gps_points,points.size()),13,muted));content.addView(text(R.string.duration_hint,13,muted));if(points.size()<2)content.addView(text(R.string.no_gps_distance,14,muted));
  if(points.size()>1)action(content,s.savedRoute?R.string.unsave_route:R.string.save_route,()->mutate(()->{ActivitySession latest=db.sessions().byId(s.id);if(latest!=null){latest.savedRoute=!latest.savedRoute;db.sessions().update(latest);}}),false);
  action(content,R.string.repeat_activity,()->begin(s.type),true);action(content,R.string.delete,()->confirm(R.string.delete_title,R.string.delete_message,R.string.delete_confirm,()->{screen="history";mutate(()->db.deleteSession(s));}),false);
 }
 private void metric(LinearLayout parent,int label,String value){parent.addView(text(label,13,muted));parent.addView(text(value,27,fg));}
 private void feed(){shell(R.string.feed);content.addView(text(R.string.feed_hint,15,muted));if(completed().isEmpty())empty(R.string.empty_history);for(ActivitySession s:completed())activityCard(s,true);action(content,R.string.start,()->navigate("picker"),true);}
 private void routes(){shell(R.string.routes);content.addView(text(R.string.routes_hint,15,muted));int count=0;for(ActivitySession s:completed())if(s.savedRoute){activityCard(s,false);count++;}if(count==0)content.addView(text(R.string.no_routes,18,fg));action(content,R.string.all_history,()->navigate("history"),false);}
 private void progress(){shell(R.string.progress);LinearLayout period=new LinearLayout(this);MaterialButton week=button(getString(R.string.week),!month),mo=button(getString(R.string.month),month);period.addView(week,new LinearLayout.LayoutParams(0,-2,1));period.addView(mo,new LinearLayout.LayoutParams(0,-2,1));week.setOnClickListener(v->{month=false;render();});mo.setOnClickListener(v->{month=true;render();});content.addView(period);long start=periodStart();double total=0;long ms=0;int count=0;Map<String,Double> days=new LinkedHashMap<>();SimpleDateFormat key=new SimpleDateFormat("yyyy-MM-dd",Locale.US);SimpleDateFormat caption=new SimpleDateFormat("d MMM",Locale.getDefault());Calendar day=Calendar.getInstance();day.setTimeInMillis(start);long now=System.currentTimeMillis();List<String> captions=new ArrayList<>();while(day.getTimeInMillis()<=now){days.put(key.format(day.getTime()),0d);captions.add(caption.format(day.getTime()));day.add(Calendar.DAY_OF_MONTH,1);}
  for(ActivitySession s:completed())if(s.startedAt>=start&&s.startedAt<=now){total+=s.distanceMeters;ms+=s.durationMs;count++;String k=key.format(new Date(s.startedAt));if(days.containsKey(k))days.put(k,days.get(k)+s.distanceMeters);}
  LinearLayout summary=card(content);metric(summary,R.string.distance,distance(total));metric(summary,R.string.sessions_count,String.valueOf(count));metric(summary,R.string.duration,duration(ms));content.addView(text(R.string.progress_hint,14,muted));section(R.string.daily_distance);if(count==0)content.addView(text(R.string.empty_progress,16,muted));
  HorizontalScrollView scroller=new HorizontalScrollView(this);LinearLayout bars=new LinearLayout(this);bars.setGravity(Gravity.BOTTOM);double max=1;for(double m:days.values())max=Math.max(max,m);int i=0;for(double m:days.values()){LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);View bar=new View(this);bar.setBackground(shape(m>0?accent:border,4));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(22),dp((float)(120*m/max)+2));bp.gravity=Gravity.CENTER_HORIZONTAL;column.addView(bar,bp);TextView label=text(String.valueOf(i+1),11,muted);column.addView(label);column.setContentDescription(getString(R.string.day_distance,captions.get(i),distance(m)));bars.addView(column,new LinearLayout.LayoutParams(dp(38),dp(160)));i++;}scroller.addView(bars);scroller.setContentDescription(getString(R.string.chart_accessibility));content.addView(scroller);
  i=0;for(double m:days.values()){if(m>0)content.addView(text(getString(R.string.day_distance,captions.get(i),distance(m)),15,fg));i++;}
 }
 private void profile(){shell(R.string.profile);content.addView(label(R.string.local_profile));EditText name=new EditText(this);name.setSingleLine(true);name.setText(profile.name);name.setHint(R.string.name_hint);name.setTextColor(fg);name.setHintTextColor(muted);name.setMinHeight(dp(56));name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});content.addView(name);action(content,R.string.save_profile,()->{String value=name.getText().toString().trim();mutate(()->{UserProfile p=db.profiles().get();if(p==null)p=new UserProfile();p.name=value;db.profiles().save(p);});},true);
  section(R.string.units);choices(new int[]{R.string.metric,R.string.imperial},imperial()?1:0,index->{String value=index==0?"metric":"imperial";saveSetting(value,null);});section(R.string.theme);String[] themes={"dark","light","system"};choices(new int[]{R.string.theme_dark,R.string.theme_light,R.string.theme_system},Arrays.asList(themes).indexOf(settings.theme),index->saveSetting(null,themes[index]));
  section(R.string.routes);action(content,R.string.routes,()->navigate("routes"),false);section(R.string.data_management);content.addView(text(R.string.privacy,15,muted));action(content,R.string.export,()->{if(active!=null){toast(R.string.active_data_warning);return;}confirm(R.string.export_title,R.string.export_message,R.string.continue_action,()->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"mtrack-export.json");startActivityForResult(intent,EXPORT);});},false);
  action(content,R.string.clear_data,()->{if(active!=null){toast(R.string.active_data_warning);return;}confirm(R.string.clear_title,R.string.clear_message,R.string.delete_confirm,()->mutate(()->{db.clearAllTables();getPreferences(0).edit().clear().apply();}));},false);content.addView(text(R.string.version_label,12,muted));
 }
 private interface Choice {void choose(int index);}
 private void choices(int[] labels,int selected,Choice listener){RadioGroup group=new RadioGroup(this);for(int i=0;i<labels.length;i++){RadioButton radio=new RadioButton(this);radio.setId(View.generateViewId());radio.setText(labels[i]);radio.setTextSize(16);radio.setTextColor(fg);radio.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));radio.setMinHeight(dp(52));group.addView(radio);if(i==selected)radio.setChecked(true);final int position=i;radio.setOnClickListener(v->listener.choose(position));}content.addView(group);}
 private void saveSetting(String units,String theme){mutate(()->{UserSettings s=db.settings().get();if(s==null)s=defaultSettings();if(units!=null)s.units=units;if(theme!=null)s.theme=theme;db.settings().save(s);});}
 private void mutate(Runnable mutation){Store.IO.post(()->{try{mutation.run();main.post(()->load(true));}catch(Exception e){main.post(()->toast(R.string.storage_error));}});}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==EXPORT&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();Store.IO.post(()->{try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){LocalExport.write(out,db);main.post(()->toast(R.string.export_done));}catch(Exception e){main.post(()->toast(R.string.export_error));}});}}
 private void goBack(){if("home".equals(screen))finish();else navigate("home");}
}
