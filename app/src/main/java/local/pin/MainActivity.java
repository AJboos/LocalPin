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
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
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
    private TextView selection,status,motionDetails,attribution;
    private EditText query;
    private Button start,search,locate,pause,staticModeButton,routeModeButton,undoRoute,clearRoute,configureRoute;
    private LinearLayout routeControls;
    private RouteEditor routeDraft;
    private RoutePlan displayedRun;
    private boolean routeMode,startPending;
    private long startRequestedAt;
    private double lat=Double.NaN,lon;
    private String place="已选地图位置";
    private String pendingAction="";
    private CancellationSignal locationCancel;
    private boolean destroyed;
    private int searchGeneration;
    private final Runnable refresh=new Runnable(){@Override public void run(){
        renderSimulation();
        handler.postDelayed(this,700);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        routeDraft=RoutePreferences.load(this,state);
        routeMode=RoutePreferences.routeMode(this,state);
        if(state!=null){
            startRequestedAt=state.getLong("startRequestedAt",0);
            startPending=state.getBoolean("startPending",false)&&SystemClock.elapsedRealtime()-startRequestedAt<5000;
            String action=state.getString("pendingAction","");pendingAction="start".equals(action)||"locate".equals(action)?action:"";
        }
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
        Button runtime=button("设置",false);runtime.setOnClickListener(v->appSettings());header.addView(runtime);
        Button info=button("说明",false);info.setOnClickListener(v->info());header.addView(info);root.addView(header);
        TextView subtitle=text("地图选点 · 路线运动 · 2.2.1 预览",12,0xFF617268);subtitle.setPadding(dp(18),0,0,dp(6));root.addView(subtitle);
        LinearLayout modes=row();modes.setPadding(dp(12),0,dp(12),dp(4));
        staticModeButton=button("静止选点",false);staticModeButton.setOnClickListener(v->changeMode(false));
        routeModeButton=button("路线模拟",false);routeModeButton.setOnClickListener(v->changeMode(true));
        modes.addView(staticModeButton,new LinearLayout.LayoutParams(0,dp(42),1));
        modes.addView(routeModeButton,new LinearLayout.LayoutParams(0,dp(42),1));root.addView(modes);
        routeControls=row();routeControls.setPadding(dp(12),0,dp(12),dp(4));
        undoRoute=button("撤销一点",false);undoRoute.setOnClickListener(v->{if(editAllowed()){routeDraft.undo();routeChanged();}});
        clearRoute=button("清空路线",false);clearRoute.setOnClickListener(v->{if(editAllowed())new AlertDialog.Builder(this)
            .setTitle("清空路线？").setMessage("删除当前草稿中的所有途经点，速度和结束条件保留。")
            .setPositiveButton("清空",(d,w)->{if(editAllowed()){routeDraft.clear();routeChanged();}}).setNegativeButton("取消",null).show();});
        configureRoute=button("路线设置",false);configureRoute.setOnClickListener(v->routeSettings());
        for(Button action:new Button[]{undoRoute,clearRoute,configureRoute})routeControls.addView(action,new LinearLayout.LayoutParams(0,dp(42),1));
        root.addView(routeControls);
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
        attribution=text(map.source().attribution()+"  ·  地图来源与许可",10,0xFF52695E);
        attribution.setGravity(Gravity.END);attribution.setPadding(dp(8),dp(4),dp(8),dp(4));
        attribution.setOnClickListener(v->openUrl(map.source().attributionUrl()));root.addView(attribution);
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(16),dp(8),dp(16),dp(10));
        selection=text("轻点教学楼或其他地点，选择目的地",16,INK);selection.setTypeface(null,Typeface.BOLD);selection.setMaxLines(2);bottom.addView(selection);
        status=text(MockService.status,12,0xFF617268);status.setPadding(0,dp(4),0,dp(6));status.setMinHeight(dp(30));bottom.addView(status);
        motionDetails=text("",12,INK);motionDetails.setPadding(0,0,0,dp(6));bottom.addView(motionDetails);
        pause=button("暂停运动",false);pause.setOnClickListener(v->pauseOrResume());bottom.addView(pause,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout buttons=row();start=button("开始模拟",true);start.setEnabled(false);start.setOnClickListener(v->withLocation("start"));
        buttons.addView(start,new LinearLayout.LayoutParams(0,dp(48),1));
        Button verify=button("核验",false);verify.setOnClickListener(v->diagnostics());LinearLayout.LayoutParams verifyParams=new LinearLayout.LayoutParams(dp(68),dp(48));verifyParams.leftMargin=dp(8);buttons.addView(verify,verifyParams);
        Button stop=button("停止",false);stop.setOnClickListener(v->stopSimulation());LinearLayout.LayoutParams stopParams=new LinearLayout.LayoutParams(dp(68),dp(48));stopParams.leftMargin=dp(8);buttons.addView(stop,stopParams);bottom.addView(buttons);
        TextView privacy=text("在线地图会向图块服务传送浏览区域。无广告、无账号。",10,0xFF617268);privacy.setPadding(0,dp(7),0,0);bottom.addView(privacy);root.addView(bottom);
        map.setListener((a,b)->{
            if(startPending){
                if(!routeMode){if(Double.isFinite(lat))map.restoreSelection(lat,lon);else map.clearSelection();}
                toast("正在启动，请稍候再选点");return;
            }
            if(routeMode){
                if(!editAllowed())return;
                try{routeDraft.add(a,b);routeChanged();}catch(IllegalArgumentException invalid){toast(invalid.getMessage());}
                return;
            }
            lat=a;lon=b;place="已选地图位置";
            // Once the user explicitly starts simulation, each map tap directly
            // applies that point. A separate pending pin caused ambiguity in v1.0.
            if(MockService.running&&MockService.currentRoute==null)begin();
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
        if(MockService.running){
            RoutePlan active=MockService.currentRoute;
            if(active!=null){routeDraft.showPlan(active);displayedRun=active;routeMode=true;
                MotionSession.Sample sample=MockService.motionSnapshot;
                if(state==null){SimulationEngine.Point point=sample==null?active.points.get(0):sample.point;map.center(point.latitude,point.longitude,17);}
            }else{routeMode=false;lat=MockService.latitude;lon=MockService.longitude;if(state==null)map.center(lat,lon,17);}
        }
        if(Double.isFinite(lat))map.restoreSelection(lat,lon);
        syncRouteMap();renderSimulation();
    }
    private void updateSelection(){
        if(routeMode){selection.setText(routeSummary(routeDraft));return;}
        boolean same=MockService.running&&Math.abs(lat-MockService.latitude)<1e-8&&Math.abs(lon-MockService.longitude)<1e-8;
        selection.setText(MockService.running?(same?"正在模拟此处 · 点地图可换位置":"正在应用所选位置…"):place+" · 点开始模拟");
        start.setEnabled(Double.isFinite(lat)&&!MockService.running&&!startPending);
    }
    private boolean editAllowed(){if(MockService.running||startPending){toast("请先停止模拟，再编辑路线或切换模式");return false;}return true;}
    private void changeMode(boolean route){
        if(route==routeMode)return;if(!editAllowed())return;
        routeMode=route;syncRouteMap();RoutePreferences.save(this,routeDraft,routeMode);renderSimulation();
    }
    private void syncRouteMap(){map.setRouteMode(routeMode);map.setRoute(routeDraft.points(),routeDraft.closed);if(!routeMode)map.setMotionPosition(null);}
    private void routeChanged(){syncRouteMap();RoutePreferences.save(this,routeDraft,routeMode);renderSimulation();}
    private void renderSimulation(){
        boolean running=MockService.running;
        if(startPending&&(running||SystemClock.elapsedRealtime()-startRequestedAt>5000))startPending=false;
        RoutePlan plan=running?MockService.currentRoute:null;
        MotionSession.Sample sample=plan==null?null:MockService.motionSnapshot;
        if(plan!=null&&plan!=displayedRun){routeDraft.showPlan(plan);displayedRun=plan;routeMode=true;syncRouteMap();}
        if(!running){displayedRun=null;map.setMotionPosition(null);}
        if(sample!=null)map.setMotionPosition(sample.point);
        routeControls.setVisibility(routeMode?View.VISIBLE:View.GONE);
        boolean locked=running||startPending;
        undoRoute.setEnabled(!locked&&routeDraft.size()>0);clearRoute.setEnabled(!locked&&routeDraft.size()>0);configureRoute.setEnabled(!locked);
        staticModeButton.setTextColor(routeMode?0xFF617268:TEAL);routeModeButton.setTextColor(routeMode?TEAL:0xFF617268);
        staticModeButton.setEnabled(!locked||!routeMode);routeModeButton.setEnabled(!locked||routeMode);
        long age=MockService.lastSuccessfulUpdate==0?0:(SystemClock.elapsedRealtime()-MockService.lastSuccessfulUpdate)/1000;
        String detail=MockService.status;
        if(running&&sample!=null){
            detail=age>5?"定位更新中断，运动数据可能已过期；请检查后台或停止后重试":
                sample.completed?"运动已完成，正在驻留末点；点停止恢复系统定位":
                sample.paused?"运动已暂停，仍在模拟当前位置":"沿路线运动中；模拟位置标记保持不变";
            motionDetails.setText(String.format(Locale.ROOT,"累计 %s  ·  运动 %s  ·  当前 %.1f km/h",
                    formatDistance(sample.travelledMeters),formatDuration(sample.activeMillis),sample.speedMps*3.6));
        }else if(running){
            long now=SystemClock.elapsedRealtime();
            boolean delivered=MockService.lastGpsCallback>0&&MockService.lastNetworkCallback>0
                &&now-MockService.lastGpsCallback<5000&&now-MockService.lastNetworkCallback<5000;
            detail=age>5?"位置更新已暂停 · 请检查后台运行设置":delivered?"本机 GPS / 网络回调已匹配选点":"正在发送 · 等待本机位置回调";
        }
        if(startPending)detail="正在启动模拟，请稍候…";
        status.setText(detail);status.setTextColor(running&&age<=5?TEAL:0xFF617268);
        motionDetails.setVisibility(sample!=null?View.VISIBLE:View.GONE);
        pause.setVisibility(running&&plan!=null?View.VISIBLE:View.GONE);
        pause.setEnabled(sample!=null&&!sample.completed);
        pause.setText(sample!=null&&sample.paused?"继续运动":"暂停运动");
        start.setText(startPending?"正在启动":running?(sample!=null&&sample.completed?"末点驻留":"正在模拟"):(routeMode?"开始路线":"开始模拟"));
        start.setEnabled(!locked&&(routeMode?routeDraft.size()>=(routeDraft.closed?3:2):Double.isFinite(lat)));
        locate.setEnabled(!running&&!startPending&&locationCancel==null);
        updateSelection();
    }
    private static String formatDistance(double meters){return meters>=1000?String.format(Locale.ROOT,"%.2f 千米",meters/1000):String.format(Locale.ROOT,"%.0f 米",meters);}
    private static String formatDuration(long millis){long seconds=Math.max(0,millis)/1000;return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private static String routeSummary(RouteEditor draft){
        String basic=(draft.closed?"闭环":"开放路线")+" · "+draft.size()+" 点 · "+formatDistance(draft.length(draft.closed));
        if(draft.size()<(draft.closed?3:2))return basic+"\n轻点地图继续添加途经点";
        try{
            RoutePlan plan=draft.plan();
            if(plan.durationLimitMillis>0)return basic+"\n"+(plan.closed?"按时长结束：":"预计用时：")+formatDuration(plan.durationLimitMillis);
            double distance=plan.closed?plan.distanceLimitMeters:plan.lengthMeters;
            if(plan.closed&&draft.loopDistanceByDuration)return basic+"\n累计 "+formatDistance(distance)+" · 匀速 "+String.format(Locale.ROOT,"%.2f km/h",plan.minSpeedMps*3.6)+" · "+formatDuration(Math.round(distance/plan.minSpeedMps*1000));
            return basic+"\n"+(plan.closed?"累计 "+formatDistance(distance)+"，":"")+"预计 "+formatDuration(Math.round(distance/plan.maxSpeedMps*1000))+"–"+formatDuration(Math.round(distance/plan.minSpeedMps*1000));
        }catch(IllegalArgumentException invalid){return basic+"\n"+invalid.getMessage();}
    }
    private EditText numberInput(String value){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(value);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);return input;
    }
    private static String number(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private double readNumber(EditText input,String name){
        try{double value=Double.parseDouble(input.getText().toString().trim());if(Double.isFinite(value))return value;}
        catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("请填写有效的"+name);
    }
    private LinearLayout field(LinearLayout parent,String label,EditText input){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        row.addView(text(label,13,INK));row.addView(input,new LinearLayout.LayoutParams(-1,dp(44)));parent.addView(row);return row;
    }
    private void routeSettings(){
        if(!editAllowed())return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        CheckBox closed=new CheckBox(this);closed.setText("闭环循环：末点连回起点");closed.setChecked(routeDraft.closed);content.addView(closed);
        TextView length=text("",13,INK);content.addView(length);
        RadioGroup openChoice=new RadioGroup(this);
        RadioButton openSpeed=new RadioButton(this);openSpeed.setId(View.generateViewId());openSpeed.setText("速度区间：走完整条路线");openChoice.addView(openSpeed);
        RadioButton openTime=new RadioButton(this);openTime.setId(View.generateViewId());openTime.setText("总时长：自动计算匀速");openChoice.addView(openTime);content.addView(openChoice);
        RadioGroup loopChoice=new RadioGroup(this);
        RadioButton loopDistance=new RadioButton(this);loopDistance.setId(View.generateViewId());loopDistance.setText("按累计距离结束");loopChoice.addView(loopDistance);
        RadioButton loopTime=new RadioButton(this);loopTime.setId(View.generateViewId());loopTime.setText("按运动时长结束");loopChoice.addView(loopTime);content.addView(loopChoice);
        RadioButton loopDistanceTime=new RadioButton(this);loopDistanceTime.setId(View.generateViewId());loopDistanceTime.setText("距离 + 总时长：自动计算匀速");loopChoice.addView(loopDistanceTime);
        EditText minimum=numberInput(number(routeDraft.minKmh)),maximum=numberInput(number(routeDraft.maxKmh));
        EditText duration=numberInput(number(routeDraft.durationMinutes)),distance=numberInput(number(routeDraft.distanceMeters));
        LinearLayout minimumRow=field(content,"最低速度（km/h）",minimum),maximumRow=field(content,"最高速度（km/h）",maximum);
        LinearLayout durationRow=field(content,"运动时长（分钟，不含暂停）",duration),distanceRow=field(content,"目标累计距离（米，可跨多圈）",distance);
        TextView preview=text("",13,TEAL);content.addView(preview);
        content.addView(text("区间内速度会逐渐变化；最低与最高相同即为匀速。完成后继续模拟末点，点击停止才恢复系统定位。\n路线是途经点之间的连线，不会自动沿道路规划。",12,0xFF617268));
        openChoice.check(routeDraft.openByDuration?openTime.getId():openSpeed.getId());
        loopChoice.check(routeDraft.loopDistanceByDuration?loopDistanceTime.getId():routeDraft.loopByDuration?loopTime.getId():loopDistance.getId());
        Runnable render=()->{
            boolean loop=closed.isChecked(),uniform=loop?loopDistanceTime.isChecked():openTime.isChecked();
            boolean timed=loop?loopTime.isChecked()||loopDistanceTime.isChecked():openTime.isChecked();
            openChoice.setVisibility(loop?View.GONE:View.VISIBLE);loopChoice.setVisibility(loop?View.VISIBLE:View.GONE);
            minimumRow.setVisibility(uniform?View.GONE:View.VISIBLE);maximumRow.setVisibility(uniform?View.GONE:View.VISIBLE);
            durationRow.setVisibility(timed?View.VISIBLE:View.GONE);distanceRow.setVisibility(loop&&!loopTime.isChecked()?View.VISIBLE:View.GONE);
            double lengthMeters=routeDraft.length(loop);length.setText("路线总长："+formatDistance(lengthMeters)+"（"+routeDraft.size()+" 个途经点）");
            try{
                if(timed){
                    double minutes=readNumber(duration,"时长");
                    if(minutes<=0||minutes*60000>=Long.MAX_VALUE)throw new IllegalArgumentException("时长应大于零");
                    if(loop&&uniform){double meters=readNumber(distance,"距离");if(meters<=0)throw new IllegalArgumentException("距离应大于零");
                        preview.setText("自动匀速："+String.format(Locale.ROOT,"%.2f km/h",meters/(minutes*60)*3.6));
                    }else if(loop){double lo=readNumber(minimum,"最低速度"),hi=readNumber(maximum,"最高速度");
                        if(lo<=0||hi<lo)throw new IllegalArgumentException("请检查速度区间");
                        preview.setText("预计累计 "+formatDistance(lo/3.6*minutes*60)+"–"+formatDistance(hi/3.6*minutes*60));
                    }else preview.setText("自动匀速："+String.format(Locale.ROOT,"%.2f km/h",lengthMeters/(minutes*60)*3.6));
                }else{
                    double lo=readNumber(minimum,"最低速度"),hi=readNumber(maximum,"最高速度");
                    double meters=loop?readNumber(distance,"距离"):lengthMeters;
                    if(lo<=0||hi<lo||meters<0)throw new IllegalArgumentException("请检查速度或距离");
                    preview.setText("预计运动时长："+formatDuration(Math.round(meters/(hi/3.6)*1000))+"–"+formatDuration(Math.round(meters/(lo/3.6)*1000)));
                }
            }catch(IllegalArgumentException invalid){preview.setText(invalid.getMessage());}
        };
        closed.setOnCheckedChangeListener((v,checked)->render.run());
        openChoice.setOnCheckedChangeListener((group,id)->render.run());loopChoice.setOnCheckedChangeListener((group,id)->render.run());
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){render.run();}public void afterTextChanged(Editable value){}};
        for(EditText input:new EditText[]{minimum,maximum,duration,distance})input.addTextChangedListener(watcher);
        render.run();ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("路线设置").setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!editAllowed()){dialog.dismiss();return;}
            try{
                RouteEditor next=new RouteEditor();for(SimulationEngine.Point point:routeDraft.points())next.add(point.latitude,point.longitude);
                next.closed=closed.isChecked();next.openByDuration=openTime.isChecked();next.loopByDuration=loopTime.isChecked();next.loopDistanceByDuration=loopDistanceTime.isChecked();
                boolean uniform=next.closed?next.loopDistanceByDuration:next.openByDuration;
                boolean timed=next.closed?next.loopByDuration||next.loopDistanceByDuration:next.openByDuration;
                next.minKmh=uniform?routeDraft.minKmh:readNumber(minimum,"最低速度");next.maxKmh=uniform?routeDraft.maxKmh:readNumber(maximum,"最高速度");
                next.durationMinutes=timed?readNumber(duration,"时长"):routeDraft.durationMinutes;
                next.distanceMeters=next.closed&&!next.loopByDuration?readNumber(distance,"距离"):routeDraft.distanceMeters;
                next.validateSettings();
                if(next.size()>=(next.closed?3:2))next.plan();
                routeDraft=next;routeChanged();dialog.dismiss();
            }catch(IllegalArgumentException invalid){toast(invalid.getMessage());}
        }));dialog.show();
    }
    private void pauseOrResume(){
        MotionSession.Sample sample=MockService.motionSnapshot;
        if(!MockService.running||MockService.currentRoute==null||sample==null||sample.completed)return;
        serviceCommand(sample.paused?MockService.RESUME:MockService.PAUSE);
    }
    private void serviceCommand(String action){
        try{startService(new Intent(this,MockService.class).setAction(action));}
        catch(RuntimeException failure){toast("控制请求未完成，请查看核验或停止后重试");}
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
        if("start".equals(action)){
            if(startPending)return;
            try{if(routeMode)routeDraft.plan();else if(!Double.isFinite(lat))return;}
            catch(IllegalArgumentException invalid){toast(invalid.getMessage());return;}
        }
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
        if(startPending)return;
        if(MockService.running&&MockService.currentRoute!=null){toast("路线正在运行；请使用暂停、继续或停止");return;}
        if(locationCancel!=null){locationCancel.cancel();locationCancel=null;}locate.setEnabled(true);locate.setText("我附近");
        try {
            Intent request=new Intent(this,MockService.class);
            if(routeMode){
                RoutePlan plan=routeDraft.plan();double[] points=new double[plan.points.size()*2];
                for(int index=0;index<plan.points.size();index++){points[index*2]=plan.points.get(index).latitude;points[index*2+1]=plan.points.get(index).longitude;}
                request.setAction(MockService.START_ROUTE).putExtra("route",points).putExtra("closed",plan.closed)
                    .putExtra("minSpeedMps",plan.minSpeedMps).putExtra("maxSpeedMps",plan.maxSpeedMps)
                    .putExtra("durationMillis",plan.durationLimitMillis).putExtra("distanceMeters",plan.distanceLimitMeters);
                RoutePreferences.save(this,routeDraft,routeMode);
            }else request.setAction(MockService.START).putExtra("lat",lat).putExtra("lon",lon);
            if(!MockService.running){startPending=true;startRequestedAt=SystemClock.elapsedRealtime();}
            startForegroundService(request);map.hideRealMarker();renderSimulation();
        }catch(IllegalArgumentException invalid){startPending=false;toast(invalid.getMessage());renderSimulation();}
        catch(RuntimeException e){startPending=false;toast("无法启动，请检查系统权限后重试");renderSimulation();}
    }
    private void stopSimulation(){
        startPending=false;pendingAction="";
        boolean wasRunning=stopService(new Intent(this,MockService.class));
        if(!wasRunning){
            boolean clean=true;
            if(MockService.selected(this))clean=MockService.removeProviders(getSystemService(LocationManager.class),Arrays.asList(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER));
            MockService.status=clean?"已停止模拟，等待手机重新获取真实位置":"请将开发者选项中的模拟位置应用设为无";
        }
        renderSimulation();
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
                    if(!routeMode&&!MockService.running){lat=Double.NaN;map.clearSelection();start.setEnabled(false);}
                    if(routeMode)toast(MockService.running?"已移至搜索结果；停止模拟后可编辑路线":"已移至搜索结果，请按顺序轻点添加途经点");
                    else selection.setText("已找到“"+name+"”，请轻点地图选择目的地");
                }).setNegativeButton("取消",null).show();
    }
    private void setup(){
        new AlertDialog.Builder(this).setTitle("首次设置一次即可").setMessage("1. 在手机“关于手机”中连续点版本号 7 次，开启开发者选项。\n\n2. 在开发者选项中找到“选择模拟位置信息应用”，选择“点位”。\n\n3. 回到这里，在地图上点选位置，再点开始。\n\n模拟位置可能影响多个应用，目标应用也可能识别并拒绝模拟位置。")
            .setPositiveButton("打开开发者选项",(d,w)->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(ActivityNotFoundException e){toast("请到手机设置中搜索“开发者选项”");}}).setNegativeButton("知道了",null).show();
    }
    private void appSettings(){
        new AlertDialog.Builder(this).setTitle("设置").setItems(new String[]{"位置刷新频率","地图来源","后台运行设置"},(dialog,index)->{
            if(index==0)runtimeSettings();else if(index==1)mapSettings();else backgroundSettings();
        }).setNegativeButton("关闭",null).show();
    }
    private void mapSettings(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(8),dp(20),dp(8));
        RadioGroup choices=new RadioGroup(this);
        RadioButton osm=new RadioButton(this);osm.setId(View.generateViewId());osm.setText("OpenStreetMap · 无需 Key");choices.addView(osm);
        RadioButton domestic=new RadioButton(this);domestic.setId(View.generateViewId());domestic.setText("天地图 · 国内服务，需要自己的 Key");choices.addView(domestic);content.addView(choices);
        TextView notice=text("当前默认地图是 OpenStreetMap，不是谷歌地图。其加载和细节取决于网络与当地数据。\n\n天地图使用官方矢量底图及中文注记，需要在官方开发者平台申请服务 Key；是否可用及配额由服务商决定。本版不含高德或百度 SDK。",13,INK);content.addView(notice);
        EditText key=new EditText(this);key.setSingleLine(true);key.setTextSize(14);key.setHint("粘贴自己的天地图服务 Key");
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);key.setText(MapPreferences.savedKey(this));content.addView(key);
        TextView privacy=text("Key 仅保存在本机应用私有存储，不显示在核验信息或日志中。地图服务会收到 IP、浏览区域和服务 Key；切换地图源不会重置路线运动。",12,0xFF617268);content.addView(privacy);
        choices.check(map.source().provider()==MapSource.TIANDITU?domestic.getId():osm.getId());
        key.setVisibility(domestic.isChecked()?View.VISIBLE:View.GONE);
        choices.setOnCheckedChangeListener((group,id)->key.setVisibility(domestic.isChecked()?View.VISIBLE:View.GONE));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("地图来源").setView(scroll)
                .setPositiveButton("保存",null).setNegativeButton("取消",null)
                .setNeutralButton("申请天地图 Key",(d,w)->openUrl(new MapSource(MapSource.TIANDITU,"").helpUrl())).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            int provider=domestic.isChecked()?MapSource.TIANDITU:MapSource.OSM;
            String secret=provider==MapSource.TIANDITU?key.getText().toString().trim():MapPreferences.savedKey(this);
            try{
                MapSource next=new MapSource(provider,secret);
                if(!next.ready()){toast("请先填写自己的天地图 Key，或选择 OpenStreetMap");return;}
                MapPreferences.save(this,provider,secret);map.reloadSource();
                attribution.setText(map.source().attribution()+"  ·  地图来源与许可");dialog.dismiss();toast("地图来源已更新");
            }catch(IllegalArgumentException invalid){toast("Key 格式不正确，请粘贴平台提供的完整字母数字 Key");}
        }));dialog.show();
    }
    private void runtimeSettings(){
        int[] intervals={200,500,1000};int selected=0;
        int current=MockService.preferredInterval(this);
        for(int i=0;i<intervals.length;i++)if(intervals[i]==current)selected=i;
        new AlertDialog.Builder(this).setTitle("位置刷新频率")
            .setSingleChoiceItems(new String[]{"每 0.2 秒 · 更平滑","每 0.5 秒","每 1 秒 · 减少耗电"},selected,(dialog,index)->{
                getSharedPreferences("runtime",MODE_PRIVATE).edit().putInt("interval",intervals[index]).apply();
                if(MockService.running)serviceCommand(MockService.RECONFIGURE);dialog.dismiss();toast("已设置刷新频率");
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
            if(MockService.recoveredPauseMillis>0)report.append("\n最近调度空档：").append(MockService.recoveredPauseMillis)
                    .append(" 毫秒；已恢复发送，运动每次最多推进 5 秒");
            report.append("\nGPS 匹配回调：").append(MockService.gpsCallbacks).append(" 次");
            report.append("\n网络匹配回调：").append(MockService.networkCallbacks).append(" 次");
            report.append("\n回调是本机核验，不代表其他应用已采用。");
            MotionSession.Sample motion=MockService.motionSnapshot;
            if(MockService.currentRoute!=null&&motion!=null){
                report.append("\n路线累计：").append(formatDistance(motion.travelledMeters));
                report.append("\n运动时长：").append(formatDuration(motion.activeMillis));
                report.append("\n当前速度：").append(String.format(Locale.ROOT,"%.2f km/h",motion.speedMps*3.6));
                report.append("\n运动状态：").append(motion.completed?"已完成，驻留末点":motion.paused?"手动暂停":"运动中");
                report.append("\n移动中的缓存可能落后于当前点，单次偏移不等于目标应用采用情况。");
            }
        }
        if(!MockService.lastError.isEmpty())report.append("\n最近错误：").append(MockService.lastError);
        if(MockService.running&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            MotionSession.Sample motion=MockService.currentRoute==null?null:MockService.motionSnapshot;
            double targetLat=motion!=null?motion.point.latitude:Double.isFinite(lat)?lat:MockService.latitude;
            double targetLon=motion!=null?motion.point.longitude:Double.isFinite(lat)?lon:MockService.longitude;
            if(motion==null){
                double pending=PositionCheck.distanceMeters(targetLat,targetLon,MockService.latitude,MockService.longitude);
                report.append("\n选点与发送点：").append(pending<=1?"一致":"尚有差异，请重新选点");
            }else report.append("\n路线回读与各自时间戳对应的提交记录比较。");
            report.append("\n\n系统最近位置回读：");
            LocationManager manager=getSystemService(LocationManager.class);
            String[] providers={LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.FUSED_PROVIDER};
            String[] labels={"GPS","网络","系统融合"};
            for(int i=0;i<providers.length;i++){
                report.append("\n").append(labels[i]).append("：");
                try{
                    Location point=manager.getLastKnownLocation(providers[i]);
                    if(point==null){report.append("暂无返回位置");continue;}
                    if(motion!=null){
                        report.append(MockService.matchesSubmitted(point)?"本机回读已匹配历史提交":"未匹配本次提交，可能是旧缓存");
                        continue;
                    }
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
        report.append("\n\n地图来源：").append(map.source().label());
        String mapError=map.mapProblem();
        report.append("\n\n最近地图加载错误：").append(mapError.isEmpty()?"暂无记录":mapError);
        report.append("\n本次核验在手机本地完成，不上传结果。");
        new AlertDialog.Builder(this).setTitle("系统核验").setMessage(report.toString())
            .setPositiveButton("知道了",null)
            .setNeutralButton("后台设置",(d,w)->backgroundSettings())
            .setNegativeButton("重试地图",(d,w)->map.retryMap()).show();
    }
    private void info(){
        new AlertDialog.Builder(this).setTitle("点位 2.2.1 预览 · 使用说明").setMessage("静止选点：轻点地图选择位置，运行中再次点选即可换点。\n\n路线模拟：按顺序轻点地图添加途经点，可撤销、清空，或在路线设置中选择闭环和速度。连线不会自动沿道路规划。运行期间停止编辑，可拖动和缩放查看。\n\n开放路线可按速度区间走完整程，或按总时长自动匀速。闭环可按累计距离或运动时长结束，也可指定距离和总时长自动计算匀速。时长不计手动暂停；调度恢复后自动继续，每次最多推进 5 秒，超出空档不补跑。实际完成时间可能延长。\n\n暂停和完成后仍持续模拟当前位置；关闭页面或划掉最近任务不主动停止。点击页面或通知中的停止才清理模拟位置源。修改刷新频率不重置路线进度。\n\n运行期间申请带超时的 CPU 保持，可能增加耗电，不能保证系统不会终止应用。\n\n地图可在设置中选择 OpenStreetMap 或使用自己的 Key 接入天地图；它们不是谷歌地图。服务能收到 IP、浏览区域和对应服务 Key。搜索仍使用系统 Geocoder，搜索词可能发往服务商。\n\n本地保存地图视野、静止选点、路线草稿、地图 Key 和设置，无广告、统计或账号；清除应用存储可删除。系统模拟位置采用 WGS84，天地图使用其官方墨卡托图层。\n\n本版保留模拟位置标记，不伪造其他应用的运动传感器。核验匹配只说明本程序的系统回读，不能证明其他应用采用。本轮后台修复与带 Key 的天地图仍待真机验证。")
            .setPositiveButton("知道了",null).setNeutralButton("系统核验",(d,w)->diagnostics()).setNegativeButton("地图服务说明",(d,w)->openUrl(map.source().provider()==MapSource.OSM?map.source().helpUrl():"https://www.tianditu.gov.cn/about/service")).show();
    }
    private void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){toast("没有可打开链接的浏览器");}}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(destroyed)return;
        if(code==10){String action=pendingAction;pendingAction="";if(action.isEmpty())return;
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)withLocation(action);else toast("需要精确位置权限；可在应用权限中开启后重试");}
        if(code==11){String action=pendingAction;pendingAction="";if(!"start".equals(action))return;
            if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)toast("通知未开启，可回到点位点击停止");begin();}
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putDouble("centerLat",map.centerLat);out.putDouble("centerLon",map.centerLon);out.putInt("zoom",map.zoom);out.putDouble("lat",lat);out.putDouble("lon",lon);out.putString("place",place);out.putBoolean("startPending",startPending);out.putLong("startRequestedAt",startRequestedAt);out.putString("pendingAction",pendingAction);RoutePreferences.save(out,routeDraft,routeMode);}
    @Override protected void onResume(){super.onResume();map.resumeMap();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){super.onPause();
        getSharedPreferences("map",MODE_PRIVATE).edit().putLong("centerLat",Double.doubleToLongBits(map.centerLat))
            .putLong("centerLon",Double.doubleToLongBits(map.centerLon)).putInt("zoom",map.zoom)
            .putLong("lat",Double.doubleToLongBits(lat)).putLong("lon",Double.doubleToLongBits(lon)).apply();
        RoutePreferences.save(this,routeDraft,routeMode);
        map.pauseMap();handler.removeCallbacks(refresh);if(locationCancel!=null){locationCancel.cancel();locationCancel=null;locate.setEnabled(true);locate.setText("我附近");}}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(locationCancel!=null)locationCancel.cancel();searchWorker.shutdownNow();map.close();super.onDestroy();}
}
