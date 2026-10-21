package local.pin;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.location.provider.ProviderProperties;
import android.os.*;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** User-started foreground simulation. No boot receiver or automatic restart. */
public final class MockService extends Service {
    static final String START="local.pin.START",STOP="local.pin.STOP",START_ROUTE="local.pin.START_ROUTE",
            PAUSE="local.pin.PAUSE",RESUME="local.pin.RESUME",RECONFIGURE="local.pin.RECONFIGURE";
    static volatile boolean running,cpuHeld;
    static volatile double latitude,longitude;
    static volatile String status="尚未开始模拟",lastError="";
    static volatile long lastSuccessfulUpdate,recoveredPauseMillis,successfulWrites;
    static volatile long gpsCallbacks,networkCallbacks,lastGpsCallback,lastNetworkCallback;
    static volatile int intervalMillis=SimulationEngine.DEFAULT_INTERVAL;
    static volatile RoutePlan currentRoute;
    static volatile MotionSession.Sample motionSnapshot;
    private static final String CHANNEL="simulation";
    private static final int NOTIFICATION=7;
    private final List<String> ownedProviders=new ArrayList<>();
    private HandlerThread writerThread;
    private Handler handler;
    private SimulationEngine engine;
    private LocationManager manager;
    private PowerManager.WakeLock cpu;
    private long lastLease;
    private static final SubmittedFixes submitted=new SubmittedFixes();
    private long lastNotificationAt;
    private String lastNotificationPhase="";
    private boolean foreground;
    private volatile boolean stopping;

    static boolean selected(Context context) {
        AppOpsManager ops=context.getSystemService(AppOpsManager.class);
        return ops!=null&&ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(),context.getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    static int preferredInterval(Context context) {
        return SimulationEngine.validInterval(context.getSharedPreferences("runtime",MODE_PRIVATE)
                .getInt("interval",SimulationEngine.DEFAULT_INTERVAL));
    }
    @Override public void onCreate() {
        super.onCreate();manager=getSystemService(LocationManager.class);
        currentRoute=null;motionSnapshot=null;submitted.clear();
        writerThread=new HandlerThread("LocalPin-Simulation");writerThread.start();
        handler=new Handler(writerThread.getLooper());
        cpu=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"local.pin:simulation");
        cpu.setReferenceCounted(false);
        engine=new SimulationEngine(new AndroidBackend(),new SimulationEngine.Clock(){
            public long utcMillis(){return System.currentTimeMillis();}
            public long elapsedMillis(){return SystemClock.elapsedRealtime();}
            public long elapsedNanos(){return SystemClock.elapsedRealtimeNanos();}
        },new SimulationEngine.Scheduler(){
            public void post(Runnable task,long delay){handler.postDelayed(task,delay);}
            public void remove(Runnable task){handler.removeCallbacks(task);}
        },this::publish);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"模拟定位状态",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("模拟期间持续显示，点击停止可恢复系统定位");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction();
        if(PAUSE.equals(action)||RESUME.equals(action)||RECONFIGURE.equals(action)){
            if(!foreground){stopSelf();return START_NOT_STICKY;}
            handler.post(()->{
                if(stopping)return;
                if(PAUSE.equals(action))engine.pauseMotion();
                else if(RESUME.equals(action))engine.resumeMotion();
                else engine.reconfigureInterval(preferredInterval(this));
            });
            return START_NOT_STICKY;
        }
        if(!START.equals(action)&&!START_ROUTE.equals(action))return START_NOT_STICKY;
        try{
            final RoutePlan route=START_ROUTE.equals(action)?readRoute(intent):null;
            final SimulationEngine.Point point=route==null?new SimulationEngine.Point(
                    intent.getDoubleExtra("lat",Double.NaN),intent.getDoubleExtra("lon",Double.NaN)):null;
            checkAccess();
            if(!foreground){
                startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);foreground=true;
            }
            final int interval=preferredInterval(this);
            handler.post(()->{
                if(stopping)return;
                submitted.clear();lastGpsCallback=0;lastNetworkCallback=0;
                if(route==null)engine.apply(point,interval);else engine.applyRoute(route,interval);
            });
        }catch(RuntimeException failure){
            lastError="启动失败："+failure.getClass().getSimpleName();
            status="无法开始：请检查模拟位置授权、精确位置权限和手机定位开关";stopSelf();
        }
        return START_NOT_STICKY;
    }
    private static RoutePlan readRoute(Intent intent){
        double[] coordinates=intent.getDoubleArrayExtra("route");
        if(coordinates==null||coordinates.length<4||coordinates.length>400||coordinates.length%2!=0)
            throw new IllegalArgumentException("Invalid route coordinates");
        List<SimulationEngine.Point> points=new ArrayList<>();
        for(int i=0;i<coordinates.length;i+=2)points.add(new SimulationEngine.Point(coordinates[i],coordinates[i+1]));
        return new RoutePlan(points,intent.getBooleanExtra("closed",false),
                intent.getDoubleExtra("minSpeedMps",Double.NaN),intent.getDoubleExtra("maxSpeedMps",Double.NaN),
                intent.getLongExtra("durationMillis",0),intent.getDoubleExtra("distanceMeters",0));
    }
    private void checkAccess() {
        if(!selected(this))throw new SecurityException("Mock app not selected");
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Precise location required");
        if(!manager.isLocationEnabled())throw new IllegalStateException("Location disabled");
    }
    private void publish(SimulationEngine.State state) {
        currentRoute=state.routePlan;motionSnapshot=state.motion;
        if(state.point!=null){latitude=state.point.latitude;longitude=state.point.longitude;}
        intervalMillis=state.intervalMillis;successfulWrites=state.writes;
        lastSuccessfulUpdate=state.lastWriteMillis;recoveredPauseMillis=state.recoveredPauseMillis;cpuHeld=cpu.isHeld();
        running=state.running&&!stopping;
        if(!state.error.isEmpty()){
            lastError="定位中断："+state.error;status="模拟已中断，请打开核验检查原因";
            if(!stopping)stopSelf();
        }else if(state.running){
            lastError="";
            if(state.motion==null)status="正在发送所选位置";
            else if(state.motion.completed)status="路线运动已完成 · 正在停留终点";
            else if(state.motion.autoPaused)status="检测到后台中断，运动已暂停 · 请点继续";
            else if(state.motion.paused)status="路线运动已暂停 · 正在保持当前位置";
            else status="正在沿路线运动";
        }
        else if(!state.cleanupSucceeded)status="已停止发送；请将开发者选项的模拟位置应用改为无";
        else if(lastError.isEmpty())status="已停止模拟，等待手机重新定位";
        long now=SystemClock.elapsedRealtime();
        String phase=state.motion==null?"static":state.motion.completed?"completed":state.motion.paused?"paused":"moving";
        if(foreground&&running&&(!phase.equals(lastNotificationPhase)||now-lastNotificationAt>=5000)){
            try{getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());}
            catch(RuntimeException ignored){}
            lastNotificationPhase=phase;lastNotificationAt=now;
        }
    }
    private final LocationListener gpsListener=listenerFor(LocationManager.GPS_PROVIDER);
    private final LocationListener networkListener=listenerFor(LocationManager.NETWORK_PROVIDER);
    private LocationListener listenerFor(final String subscribedSource){return new LocationListener(){
        @Override public void onLocationChanged(Location point){
            SimulationEngine.State current=engine.state();
            if(stopping||!current.running||current.point==null)return;
            if(!matchesSubmitted(point))return;
            long now=SystemClock.elapsedRealtime();
            // The compatibility payload labels both sources as GPS. Count the subscription,
            // rather than trusting the provider string embedded in the returned payload.
            if(LocationManager.GPS_PROVIDER.equals(subscribedSource)){gpsCallbacks++;lastGpsCallback=now;}
            else if(LocationManager.NETWORK_PROVIDER.equals(subscribedSource)){networkCallbacks++;lastNetworkCallback=now;}
        }
    };}
    static boolean matchesSubmitted(Location location){
        return location!=null&&submitted.matches(location.getLatitude(),location.getLongitude(),location.isMock(),
                location.getElapsedRealtimeNanos(),SystemClock.elapsedRealtime());
    }
    private final class AndroidBackend implements SimulationEngine.Backend {
        public void open(){
            checkAccess();gpsCallbacks=0;networkCallbacks=0;lastGpsCallback=0;lastNetworkCallback=0;
            for(String name:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER})manager.removeTestProvider(name);
            for(String name:new String[]{LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER}){
                boolean gps=LocationManager.GPS_PROVIDER.equals(name);
                ProviderProperties properties=new ProviderProperties.Builder()
                        .setHasSatelliteRequirement(gps).setHasNetworkRequirement(false)
                        .setHasCellRequirement(gps).setHasMonetaryCost(false)
                        .setHasAltitudeSupport(gps).setHasSpeedSupport(gps).setHasBearingSupport(gps)
                        .setPowerUsage(gps?ProviderProperties.POWER_USAGE_HIGH:ProviderProperties.POWER_USAGE_MEDIUM)
                        .setAccuracy(ProviderProperties.ACCURACY_COARSE).build();
                manager.addTestProvider(name,properties);ownedProviders.add(name);
                manager.setTestProviderEnabled(name,true);
            }
            renewCpu();
        }
        public void write(SimulationEngine.Point point,long utcMillis,long elapsedNanos){
            checkAccess();renewCpu();
            // Matches the public API payload observed in the reference app on this phone.
            // In particular, its network source also returns a GPS-labelled Location.
            Location location=new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(point.latitude);location.setLongitude(point.longitude);
            location.setAccuracy(1f);location.setAltitude(30d);location.setBearing(1f);
            if(Double.isFinite(point.speedMps))location.setSpeed((float)point.speedMps);
            if(Double.isFinite(point.bearingDegrees))location.setBearing((float)point.bearingDegrees);
            location.setTime(utcMillis);location.setElapsedRealtimeNanos(elapsedNanos);
            location.setElapsedRealtimeUncertaintyNanos(0d);
            Bundle extras=new Bundle();extras.putInt("satellites",7);extras.putInt("locationType",7);
            location.setExtras(extras);
            // Route speed/bearing describe the simulated path. Static fixes retain the old
            // payload shape. The test-provider API always keeps the system mock marker.
            for(String provider:ownedProviders)manager.setTestProviderLocation(provider,location);
            submitted.record(point,elapsedNanos);
        }
        public void listen(){
            // Subscribe after replacing both providers and sending the first simulated fix.
            for(String name:ownedProviders)
                manager.requestLocationUpdates(name,100L,0f,
                        LocationManager.GPS_PROVIDER.equals(name)?gpsListener:networkListener,writerThread.getLooper());
        }
        public boolean close(){
            boolean clean=true;
            for(LocationListener listener:new LocationListener[]{gpsListener,networkListener})
                try{manager.removeUpdates(listener);}catch(RuntimeException failure){clean=false;}
            clean=removeProviders(manager,ownedProviders)&&clean;ownedProviders.clear();
            submitted.clear();
            if(cpu.isHeld())cpu.release();cpuHeld=false;return clean;
        }
    }
    private void renewCpu(){
        long now=SystemClock.elapsedRealtime();
        if(!cpu.isHeld()||now-lastLease>=60000){cpu.acquire(180000);lastLease=now;}
        cpuHeld=cpu.isHeld();
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MockService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String detail=motionSnapshot==null?"切后台继续运行；结束请点停止":status;
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(local.pin.R.drawable.ic_pin)
                .setContentTitle("点位 · 正在模拟位置").setContentText(detail)
                .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null,"停止模拟",stop).build());
        MotionSession.Sample sample=motionSnapshot;
        if(sample!=null&&!sample.completed){
            String action=sample.paused?RESUME:PAUSE;
            PendingIntent toggle=PendingIntent.getService(this,2,new Intent(this,MockService.class).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(null,sample.paused?"继续运动":"暂停运动",toggle).build());
        }
        return builder.build();
    }
    @Override public void onDestroy(){
        stopping=true;running=false;handler.removeCallbacksAndMessages(null);
        // Serialized stop waits for an in-flight write, then cancels its next scheduled refresh.
        engine.stop();writerThread.quitSafely();
        if(foreground)stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    static boolean removeProviders(LocationManager manager,List<String> providers){
        boolean clean=true;
        for(String name:providers){
            try{manager.removeTestProvider(name);}catch(IllegalArgumentException ignored){}
            catch(RuntimeException failure){clean=false;}
        }
        return clean;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override protected void dump(FileDescriptor fd,PrintWriter out,String[] args){
        SimulationEngine.State current=engine.state();
        out.println("LocalPin 2.2 runtime (coordinates omitted)");
        out.println("running="+current.running+" intervalMillis="+current.intervalMillis+" writes="+current.writes);
        out.println("gpsCallbacks="+gpsCallbacks+" networkCallbacks="+networkCallbacks+" cpuHeld="+cpu.isHeld());
        out.println("lastError="+current.error+" cleanupSucceeded="+current.cleanupSucceeded);
        if(current.motion!=null)out.println("routeMotion=true paused="+current.motion.paused+
                " autoPaused="+current.motion.autoPaused+" completed="+current.motion.completed+
                " travelledMeters="+current.motion.travelledMeters+" activeMillis="+current.motion.activeMillis);
        if(!current.running||current.point==null)return;
        for(String name:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
            try{
                Location location=manager.getLastKnownLocation(name);
                if(location==null){out.println(name+": no location");continue;}
                PositionCheck.Result checked=PositionCheck.evaluate(current.point.latitude,current.point.longitude,
                        location.getLatitude(),location.getLongitude(),location.isMock(),
                        location.getElapsedRealtimeNanos()/1000000,SystemClock.elapsedRealtime());
                String check=current.motion==null?checked.state.name():matchesSubmitted(location)?"MATCH_SUBMITTED":"UNMATCHED_SAMPLE";
                out.println(name+": payloadProvider="+location.getProvider()+" mock="+location.isMock()
                        +" check="+check+" ageMillis="+checked.ageMillis
                        +" hasSpeed="+location.hasSpeed()+" hasVerticalAccuracy="+location.hasVerticalAccuracy()
                        +" hasBearingAccuracy="+location.hasBearingAccuracy());
            }catch(RuntimeException failure){out.println(name+": "+failure.getClass().getSimpleName());}
        }
    }
}
