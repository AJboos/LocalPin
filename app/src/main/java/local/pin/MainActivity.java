package local.pin;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int TEAL=0xFF087F73, INK=0xFF203D34;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService searchWorker=Executors.newSingleThreadExecutor();
    private MapView map;
    private TextView selection,status;
    private EditText query;
    private Button start,search,locate;
    private double lat=Double.NaN,lon;
    private String place="已选地图位置";
    private String pendingAction="";
    private CancellationSignal locationCancel;
    private boolean destroyed;
    private int searchGeneration;
    private final Runnable refresh=new Runnable(){@Override public void run(){
        long age=MockService.lastSuccessfulUpdate==0?0:(SystemClock.elapsedRealtime()-MockService.lastSuccessfulUpdate)/1000;
        String detail=MockService.status;
        if(MockService.running){
            long now=SystemClock.elapsedRealtime();
            boolean delivered=MockService.lastGpsCallback>0&&MockService.lastNetworkCallback>0
                &&now-MockService.lastGpsCallback<5000&&now-MockService.lastNetworkCallback<5000;
            detail=age>5?"位置更新已暂停 · 请检查后台运行设置":
                MockService.recoveredPauseMillis>0?"模拟运行中 · 已从暂停恢复":
                delivered?"本机 GPS / 网络回调已匹配选点":"正在发送 · 等待本机位置回调";
        }
        status.setText(detail);
        status.setTextColor(MockService.running&&age<=5?TEAL:0xFF617268);
        start.setText(MockService.running?"正在模拟":"开始模拟");
        start.setEnabled(Double.isFinite(lat)&&!MockService.running);
        if(Double.isFinite(lat))updateSelection();
        handler.postDelayed(this,700);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xFFF5F7F5);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        setContentView(root);root.requestApplyInsets();
        LinearLayout header=row();header.setPadding(dp(16),dp(6),dp(10),0);
        TextView title=text("点位",24,INK);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        Button setup=button("首次设置",false);setup.setOnClickListener(v->setup());header.addView(setup);
        Button runtime=button("运行",false);runtime.setOnClickListener(v->runtimeSettings());header.addView(runtime);
        Button info=button("说明",false);info.setOnClickListener(v->info());header.addView(info);root.addView(header);
        TextView subtitle=text("地图选点 · 后台模拟 · 2.1",12,0xFF617268);subtitle.setPadding(dp(18),0,0,dp(6));root.addView(subtitle);
        LinearLayout searchRow=row();searchRow.setPadding(dp(12),0,dp(12),dp(6));
        query=new EditText(this);query.setSingleLine(true);query.setTextSize(14);query.setHint("搜索城市、学校或教学楼");query.setContentDescription("搜索地点");
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchRow.addView(query,new LinearLayout.LayoutParams(0,dp(48),1));
        search=button("搜索",false);search.setOnClickListener(v->search());searchRow.addView(search);root.addView(searchRow);
        query.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        FrameLayout frame=new FrameLayout(this);map=new MapView(this);frame.addView(map,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.VERTICAL);
        locate=button("我附近",false);locate.setOnClickListener(v->withLocation("locate"));tools.addView(locate);
        Button plus=button("＋",false);plus.setContentDescription("放大地图");plus.setOnClickListener(v->map.zoomBy(1));tools.addView(plus);
        Button minus=button("－",false);minus.setContentDescription("缩小地图");minus.setOnClickListener(v->map.zoomBy(-1));tools.addView(minus);
        FrameLayout.LayoutParams toolParams=new FrameLayout.LayoutParams(dp(84),-2,Gravity.END|Gravity.TOP);toolParams.setMargins(0,dp(46),dp(8),0);frame.addView(tools,toolParams);
        root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        TextView attribution=text("© OpenStreetMap contributors  ·  地图数据许可",10,0xFF52695E);
        attribution.setGravity(Gravity.END);attribution.setPadding(dp(8),dp(4),dp(8),dp(4));
        attribution.setOnClickListener(v->openUrl("https://www.openstreetmap.org/copyright"));root.addView(attribution);
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(16),dp(8),dp(16),dp(10));
        selection=text("轻点教学楼或其他地点，选择目的地",16,INK);selection.setTypeface(null,Typeface.BOLD);selection.setMaxLines(2);bottom.addView(selection);
        status=text(MockService.status,12,0xFF617268);status.setPadding(0,dp(4),0,dp(6));status.setMinHeight(dp(30));bottom.addView(status);
        LinearLayout buttons=row();start=button("开始模拟",true);start.setEnabled(false);start.setOnClickListener(v->withLocation("start"));
        buttons.addView(start,new LinearLayout.LayoutParams(0,dp(48),1));
        Button verify=button("核验",false);verify.setOnClickListener(v->diagnostics());LinearLayout.LayoutParams verifyParams=new LinearLayout.LayoutParams(dp(68),dp(48));verifyParams.leftMargin=dp(8);buttons.addView(verify,verifyParams);
        Button stop=button("停止",false);stop.setOnClickListener(v->stopSimulation());LinearLayout.LayoutParams stopParams=new LinearLayout.LayoutParams(dp(68),dp(48));stopParams.leftMargin=dp(8);buttons.addView(stop,stopParams);bottom.addView(buttons);
        TextView privacy=text("在线地图会向图块服务传送浏览区域。无广告、无账号。",10,0xFF617268);privacy.setPadding(0,dp(7),0,0);bottom.addView(privacy);root.addView(bottom);
        map.setListener((a,b)->{
            lat=a;lon=b;place="已选地图位置";
            // Once the user explicitly starts simulation, each map tap directly
            // applies that point. A separate pending pin caused ambiguity in v1.0.
            if(MockService.running)begin();
            updateSelection();
        });
        if(state!=null){
            map.center(state.getDouble("centerLat",35),state.getDouble("centerLon",105),state.getInt("zoom",4));
            lat=state.getDouble("lat",Double.NaN);lon=state.getDouble("lon",0);place=state.getString("place","已选地图位置");
        }else{
            SharedPreferences saved=getSharedPreferences("map",MODE_PRIVATE);
            map.center(Double.longBitsToDouble(saved.getLong("centerLat",Double.doubleToLongBits(35))),
                Double.longBitsToDouble(saved.getLong("centerLon",Double.doubleToLongBits(105))),saved.getInt("zoom",4));
            lat=Double.longBitsToDouble(saved.getLong("lat",Double.doubleToLongBits(Double.NaN)));
            lon=Double.longBitsToDouble(saved.getLong("lon",Double.doubleToLongBits(0)));
        }
        if(MockService.running){lat=MockService.latitude;lon=MockService.longitude;if(state==null)map.center(lat,lon,17);}
        if(Double.isFinite(lat)){map.restoreSelection(lat,lon);updateSelection();}
    }
    private void updateSelection(){
        boolean same=MockService.running&&Math.abs(lat-MockService.latitude)<1e-8&&Math.abs(lon-MockService.longitude)<1e-8;
        selection.setText(MockService.running?(same?"正在模拟此处 · 点地图可换位置":"正在应用所选位置…"):place+" · 点开始模拟");
        start.setEnabled(Double.isFinite(lat)&&!MockService.running);
    }
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private Button button(String label,boolean primary){
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:TEAL);
        b.setMinWidth(dp(60));b.setMinimumWidth(dp(60));b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setPadding(dp(10),dp(2),dp(10),dp(2));
        GradientDrawable background=new GradientDrawable();background.setColor(primary?TEAL:Color.WHITE);background.setCornerRadius(dp(12));if(!primary)background.setStroke(dp(1),0xFFD9E4DD);b.setBackground(background);return b;
    }
    private void withLocation(String action){
        if("start".equals(action)&&!Double.isFinite(lat))return;
        if("locate".equals(action)&&MockService.running){toast("请先停止模拟，才能获取真实位置");return;}
        if("start".equals(action)&&!MockService.selected(this)){setup();return;}
        if(!getSystemService(LocationManager.class).isLocationEnabled()){
            toast("请先开启手机定位");try{startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));}catch(ActivityNotFoundException ignored){}return;
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            pendingAction=action;
            new AlertDialog.Builder(this).setTitle("需要精确位置权限").setMessage("“我附近”用于找到当前校园；安卓的定位前台服务也需要位置权限。请选择“使用应用时允许”，并开启精确位置。不会在后台读取真实位置。")
                .setPositiveButton("继续",(d,w)->requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},10)).setNegativeButton("取消",null).show();return;
        }
        if("locate".equals(action)){locate();return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            pendingAction="start";requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;
        }
        begin();
    }
    private void begin(){
        if(locationCancel!=null){locationCancel.cancel();locationCancel=null;}locate.setEnabled(true);locate.setText("我附近");
        try {startForegroundService(new Intent(this,MockService.class).setAction(MockService.START).putExtra("lat",lat).putExtra("lon",lon));map.hideRealMarker();}
        catch(RuntimeException e){toast("无法启动，请检查系统权限后重试");}
    }
    private void stopSimulation(){
        boolean wasRunning=stopService(new Intent(this,MockService.class));
        if(!wasRunning){
            boolean clean=true;
            if(MockService.selected(this))clean=MockService.removeProviders(getSystemService(LocationManager.class),Arrays.asList(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER));
            MockService.status=clean?"已停止模拟，等待手机重新获取真实位置":"请将开发者选项中的模拟位置应用设为无";
        }
        toast("已请求停止；目标应用可能需要刷新位置");
    }
    private void locate(){
        if(locationCancel!=null)locationCancel.cancel();
        LocationManager lm=getSystemService(LocationManager.class);
        locate.setEnabled(false);locate.setText("定位中");
        try {
            Location last=null;
            for(String provider:Arrays.asList(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)){
                Location candidate=lm.getLastKnownLocation(provider);
                if(candidate!=null&&!candidate.isMock()&&(SystemClock.elapsedRealtimeNanos()-candidate.getElapsedRealtimeNanos())<300_000_000_000L&&(last==null||candidate.getElapsedRealtimeNanos()>last.getElapsedRealtimeNanos()))last=candidate;
            }
            if(last!=null){map.real(last.getLatitude(),last.getLongitude());toast("已移到你附近，请点选目的地");}
            String provider=lm.isProviderEnabled(LocationManager.GPS_PROVIDER)?LocationManager.GPS_PROVIDER:LocationManager.NETWORK_PROVIDER;
            CancellationSignal signal=new CancellationSignal();locationCancel=signal;
            lm.getCurrentLocation(provider,signal,getMainExecutor(),location->{
                if(destroyed||locationCancel!=signal)return;
                locationCancel=null;locate.setEnabled(true);locate.setText("我附近");
                if(location!=null&&!location.isMock()&&!MockService.running){map.real(location.getLatitude(),location.getLongitude());toast("已找到当前位置，请点选目的地");}
                else toast("暂时没有真实定位，可搜索学校或手动拖动地图");
            });
            handler.postDelayed(()->{if(locationCancel==signal){signal.cancel();locationCancel=null;locate.setEnabled(true);locate.setText("我附近");toast("定位超时，可搜索学校或到窗边重试");}},25000);
        } catch(SecurityException|IllegalArgumentException e){locate.setEnabled(true);locate.setText("我附近");toast("无法定位，请检查精确位置权限");}
    }
    private void search(){
        if(!search.isEnabled())return;
        String name=query.getText().toString().trim();if(name.isEmpty()){toast("先输入学校或地点名称");return;}
        if(!Geocoder.isPresent()){toast("这台手机没有系统地点搜索服务，可用“我附近”后拖动地图");return;}
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(),0);
        search.setEnabled(false);search.setText("搜索中");
        int generation=++searchGeneration;
        Geocoder geocoder=new Geocoder(this,Locale.SIMPLIFIED_CHINESE);
        if(Build.VERSION.SDK_INT>=33){
            try {
                geocoder.getFromLocationName(name,6,new Geocoder.GeocodeListener(){
                    @Override public void onGeocode(List<Address> result){runOnUiThread(()->searchResult(generation,name,result));}
                    @Override public void onError(String error){runOnUiThread(()->searchResult(generation,name,null));}
                });
            } catch(RuntimeException e){searchResult(generation,name,null);}
        } else {
            searchWorker.execute(()->{
                List<Address> result=null;
                try{result=geocoder.getFromLocationName(name,6);}catch(Exception ignored){}
                List<Address> found=result;
                runOnUiThread(()->searchResult(generation,name,found));
            });
        }
        handler.postDelayed(()->{
            if(!destroyed&&searchGeneration==generation&&!search.isEnabled()){
                searchGeneration++;search.setEnabled(true);search.setText("搜索");toast("系统地点搜索超时，可用“我附近”后拖动地图");
            }
        },20000);
    }
    private void searchResult(int generation,String name,List<Address> found){
                if(destroyed||generation!=searchGeneration)return;search.setEnabled(true);search.setText("搜索");
                if(found==null||found.isEmpty()){toast("没找到，可搜索城市或学校全名；也可用“我附近”");return;}
                List<Address> valid=new ArrayList<>();for(Address address:found)if(address.hasLatitude()&&address.hasLongitude())valid.add(address);
                if(valid.isEmpty()){toast("搜索没有返回可用位置");return;}
                String[] labels=new String[valid.size()];for(int i=0;i<labels.length;i++){Address a=valid.get(i);labels[i]=a.getMaxAddressLineIndex()>=0?a.getAddressLine(0):name;}
                new AlertDialog.Builder(this).setTitle("选择搜索结果").setItems(labels,(dialog,index)->{
                    Address a=valid.get(index);map.center(a.getLatitude(),a.getLongitude(),17);
                    lat=Double.NaN;map.clearSelection();start.setEnabled(false);
                    selection.setText("已找到“"+name+"”，请轻点地图选择目的地");
                }).setNegativeButton("取消",null).show();
    }
    private void setup(){
        new AlertDialog.Builder(this).setTitle("首次设置一次即可").setMessage("1. 在手机“关于手机”中连续点版本号 7 次，开启开发者选项。\n\n2. 在开发者选项中找到“选择模拟位置信息应用”，选择“点位”。\n\n3. 回到这里，在地图上点选位置，再点开始。\n\n模拟位置可能影响多个应用，目标应用也可能识别并拒绝模拟位置。")
            .setPositiveButton("打开开发者选项",(d,w)->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(ActivityNotFoundException e){toast("请到手机设置中搜索“开发者选项”");}}).setNegativeButton("知道了",null).show();
    }
    private void runtimeSettings(){
        int[] intervals={200,500,1000};int selected=0;
        int current=MockService.preferredInterval(this);
        for(int i=0;i<intervals.length;i++)if(intervals[i]==current)selected=i;
        new AlertDialog.Builder(this).setTitle("位置刷新频率")
            .setSingleChoiceItems(new String[]{"每 0.2 秒 · 与参考样本接近","每 0.5 秒","每 1 秒 · 减少耗电"},selected,(dialog,index)->{
                getSharedPreferences("runtime",MODE_PRIVATE).edit().putInt("interval",intervals[index]).apply();
                if(MockService.running)begin();dialog.dismiss();toast("已设置刷新频率");
            }).setNeutralButton("后台设置",(d,w)->backgroundSettings()).setNegativeButton("关闭",null).show();
    }
    private void backgroundSettings(){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
        catch(ActivityNotFoundException e){toast("请到系统应用设置中找到点位的耗电管理");}
    }
    private void diagnostics(){
        boolean selected=MockService.selected(this);
        StringBuilder report=new StringBuilder("模拟应用授权：").append(selected?"已授权":"尚未选择点位");
        report.append("\n模拟服务：").append(MockService.running?"运行中":"未运行");
        if(MockService.running){
            long now=SystemClock.elapsedRealtime();
            report.append("\n设定刷新间隔：").append(MockService.intervalMillis).append(" 毫秒");
            report.append("\n成功发送批次：").append(MockService.successfulWrites);
            report.append("\n最近发送：").append(MockService.lastSuccessfulUpdate>0?(now-MockService.lastSuccessfulUpdate)+" 毫秒前":"尚未完成");
            report.append("\nCPU 保持：").append(MockService.cpuHeld?"已申请短时保持":"未持有");
            report.append("\nGPS 匹配回调：").append(MockService.gpsCallbacks).append(" 次");
            report.append("\n网络匹配回调：").append(MockService.networkCallbacks).append(" 次");
            report.append("\n回调是本机核验，不代表其他应用已采用。");
        }
        if(!MockService.lastError.isEmpty())report.append("\n最近错误：").append(MockService.lastError);
        if(MockService.running&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            double targetLat=Double.isFinite(lat)?lat:MockService.latitude;
            double targetLon=Double.isFinite(lat)?lon:MockService.longitude;
            double pending=PositionCheck.distanceMeters(targetLat,targetLon,MockService.latitude,MockService.longitude);
            report.append("\n选点与发送点：").append(pending<=1?"一致":"尚有差异，请重新选点");
            report.append("\n\n系统最近位置回读：");
            LocationManager manager=getSystemService(LocationManager.class);
            String[] providers={LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.FUSED_PROVIDER};
            String[] labels={"GPS","网络","系统融合"};
            for(int i=0;i<providers.length;i++){
                report.append("\n").append(labels[i]).append("：");
                try{
                    Location point=manager.getLastKnownLocation(providers[i]);
                    if(point==null){report.append("暂无返回位置");continue;}
                    PositionCheck.Result checked=PositionCheck.evaluate(targetLat,targetLon,point.getLatitude(),point.getLongitude(),point.isMock(),point.getElapsedRealtimeNanos()/1_000_000,SystemClock.elapsedRealtime());
                    String meters=String.format(Locale.ROOT,"%.1f",checked.distanceMeters);
                    switch(checked.state){
                        case MATCH:report.append("匹配选点，误差 ").append(meters).append(" 米");break;
                        case OFFSET:report.append("模拟位置与选点相差 ").append(meters).append(" 米");break;
                        case STALE:report.append("记录已过期，").append(checked.ageMillis/1000).append(" 秒未更新");break;
                        case NOT_MOCK:report.append("返回了其他来源的位置，距选点 ").append(meters).append(" 米");break;
                        case BAD_TIME:report.append("位置时间戳异常，不能确认成功");break;
                        default:report.append("没有可用位置");
                    }
                }catch(SecurityException e){report.append("读取被系统拒绝，请检查精确位置权限");}
                catch(IllegalArgumentException e){report.append("此手机没有该位置源");}
                catch(RuntimeException e){report.append("读取失败（").append(e.getClass().getSimpleName()).append("）");}
            }
            report.append("\n\n匹配表示系统返回了选点；其他应用是否采用需另行核对。系统融合暂无位置不代表 GPS 失败。");
        }else if(MockService.running){report.append("\n精确位置权限不足，无法进行系统回读。");}
        else{report.append("\n开始模拟后可核对系统返回的位置；此时不读取真实位置。");}
        String mapError=map.mapProblem();
        report.append("\n\n最近地图加载错误：").append(mapError.isEmpty()?"暂无记录":mapError);
        report.append("\n本次核验在手机本地完成，不上传结果。");
        new AlertDialog.Builder(this).setTitle("系统核验").setMessage(report.toString())
            .setPositiveButton("知道了",null)
            .setNeutralButton("后台设置",(d,w)->backgroundSettings())
            .setNegativeButton("重试地图",(d,w)->map.retryMap()).show();
    }
    private void info(){
        new AlertDialog.Builder(this).setTitle("点位 2.1 · 使用说明").setMessage("拖动地图找到教学楼，轻点选中，再点开始。运行中点地图立即换位置。核验可查看 GPS、网络回调与位置新鲜度。\n\n本版根据同机对照调整定位参数。默认每 0.2 秒刷新，切后台继续运行；从最近任务划掉也不主动停止。结束请用本页或通知的停止按钮。\n\n一加手机请将点位的耗电管理设为“完全允许后台行为”。运行期间申请短时 CPU 保持，并定期续期，可能增加耗电；不能保证系统绝不会暂停或终止进程。\n\n地图来自 OpenStreetMap，图块服务能收到 IP 和浏览区域，校园覆盖与网络可用性不能保证。搜索使用手机系统 Geocoder，搜索词可能发送给系统服务商。\n\n本地只记住上次浏览区域、一个选点与刷新设置，无广告、统计或账号。系统 GPS 与地图采用 WGS84。模拟中的海拔等字段是测试信息，不是真实观测。\n\n停止后等待目标应用重新定位。必要时将开发者选项的模拟位置应用改为无。\n\n本机回调匹配只表示系统返回了选点，第三方应用是否采用需另外核对。")
            .setPositiveButton("知道了",null).setNeutralButton("系统核验",(d,w)->diagnostics()).setNegativeButton("地图政策",(d,w)->openUrl("https://operations.osmfoundation.org/policies/tiles/")).show();
    }
    private void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){toast("没有可打开链接的浏览器");}}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==10){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)withLocation(pendingAction);else{toast("需要精确位置权限；可在应用权限中开启后重试");}}
        if(code==11){if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)toast("通知未开启，可回到点位点击停止");begin();}
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putDouble("centerLat",map.centerLat);out.putDouble("centerLon",map.centerLon);out.putInt("zoom",map.zoom);out.putDouble("lat",lat);out.putDouble("lon",lon);out.putString("place",place);}
    @Override protected void onResume(){super.onResume();map.resumeMap();handler.post(refresh);}
    @Override protected void onPause(){super.onPause();
        getSharedPreferences("map",MODE_PRIVATE).edit().putLong("centerLat",Double.doubleToLongBits(map.centerLat))
            .putLong("centerLon",Double.doubleToLongBits(map.centerLon)).putInt("zoom",map.zoom)
            .putLong("lat",Double.doubleToLongBits(lat)).putLong("lon",Double.doubleToLongBits(lon)).apply();
        map.pauseMap();handler.removeCallbacks(refresh);if(locationCancel!=null){locationCancel.cancel();locationCancel=null;locate.setEnabled(true);locate.setText("我附近");}}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(locationCancel!=null)locationCancel.cancel();searchWorker.shutdownNow();map.close();super.onDestroy();}
}
