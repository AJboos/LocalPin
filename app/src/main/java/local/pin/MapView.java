package local.pin;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.PriorityQueue;
import java.util.Comparator;

final class MapView extends View {
    interface Listener { void selected(double lat, double lon); }
    private TileStore tiles;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float density;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;
    private Listener listener;
    double centerLat = 35, centerLon = 105;
    int zoom = 4;
    private double selectedLat = Double.NaN, selectedLon, realLat = Double.NaN, realLon;
    private float scaleAccum = 1;
    private boolean scalingGesture;
    private boolean routeMode, closedRoute;
    private List<SimulationEngine.Point> route = Collections.emptyList();
    private SimulationEngine.Point motionPosition;
    private RoutePlan previewPlan;
    private List<SimulationEngine.Point> previewLine=Collections.emptyList();
    private int previewZoom=Integer.MIN_VALUE;

    MapView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        tiles = new TileStore(context);
        setContentDescription("地图：拖动浏览、双指缩放、轻点选择目的地。也可以用搜索和缩放按钮。");
        setFocusable(true);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent now, float dx, float dy) {
                if (scalingGesture) return true;
                double size = world();
                centerLon = MapMath.lon(MapMath.x(centerLon, size) + dx, size);
                centerLat = MapMath.lat(MapMath.y(centerLat, size) + dy, size);
                invalidate(); return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (scalingGesture) return true;
                double size = world();
                select(MapMath.lat(MapMath.y(centerLat, size) + e.getY() - getHeight()/2.0, size),
                        MapMath.lon(MapMath.x(centerLon, size) + e.getX() - getWidth()/2.0, size));
                performClick(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { zoomAt(1, e.getX(), e.getY()); return true; }
        });
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaleAccum = 1; scalingGesture = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                scaleAccum *= d.getScaleFactor();
                if (scaleAccum > 1.35f) { zoomAt(1, d.getFocusX(), d.getFocusY()); scaleAccum = 1; }
                else if (scaleAccum < .74f) { zoomAt(-1, d.getFocusX(), d.getFocusY()); scaleAccum = 1; }
                return true;
            }
        });
    }
    void setListener(Listener value) { listener = value; }
    private double world() { return 256.0 * density * (1 << zoom); }
    void center(double lat, double lon, int level) { centerLat=MapMath.clamp(lat,-85.05112878,85.05112878); centerLon=lon; zoom=(int)MapMath.clamp(level,2,tiles.source.maxZoom()); invalidate(); }
    void real(double lat, double lon) { realLat=lat; realLon=lon; center(lat,lon,17); }
    void hideRealMarker(){realLat=Double.NaN;invalidate();}
    void select(double lat, double lon) { if(!routeMode){selectedLat=lat;selectedLon=lon;}if(listener!=null)listener.selected(lat,lon);invalidate(); }
    void restoreSelection(double lat,double lon) { selectedLat=lat; selectedLon=lon; invalidate(); }
    void clearSelection() { selectedLat=Double.NaN; invalidate(); }
    void setRouteMode(boolean enabled) { routeMode=enabled;invalidate(); }
    void setRoute(List<SimulationEngine.Point> points, boolean closed) {
        route=Collections.unmodifiableList(new ArrayList<>(points));closedRoute=closed;
        previewPlan=null;previewZoom=Integer.MIN_VALUE;
        // Incomplete drafts remain drawable; only a valid route can be started.
        if(route.size()>=2)try{previewPlan=new RoutePlan(route,closed&&route.size()>2,1,1,0,0);}
        catch(IllegalArgumentException incomplete){}
        invalidate();
    }
    void setMotionPosition(SimulationEngine.Point point) { motionPosition=point;invalidate(); }
    void zoomBy(int delta) { zoomAt(delta, getWidth()/2f, getHeight()/2f); }
    private void zoomAt(int delta, float focusX, float focusY) {
        int next=(int)MapMath.clamp(zoom+delta,2,tiles.source.maxZoom()); if(next==zoom) return;
        double old=world(), fx=focusX-getWidth()/2.0, fy=focusY-getHeight()/2.0;
        double lon=MapMath.lon(MapMath.x(centerLon,old)+fx,old), lat=MapMath.lat(MapMath.y(centerLat,old)+fy,old);
        zoom=next; double size=world();
        centerLon=MapMath.lon(MapMath.x(lon,size)-fx,size); centerLat=MapMath.lat(MapMath.y(lat,size)-fy,size); invalidate();
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN) scalingGesture=false;
        scale.onTouchEvent(e); gestures.onTouchEvent(e); return true;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(231,237,232));
        double tile=256.0*density, size=world();
        double left=MapMath.x(centerLon,size)-getWidth()/2.0, top=MapMath.y(centerLat,size)-getHeight()/2.0;
        int firstX=(int)Math.floor(left/tile), lastX=(int)Math.floor((left+getWidth())/tile);
        int firstY=(int)Math.floor(top/tile), lastY=(int)Math.floor((top+getHeight())/tile), count=1<<zoom;
        Set<String> visible=new HashSet<>();
        for(int ty=firstY;ty<=lastY;ty++) for(int tx=firstX;tx<=lastX;tx++) {
            if(ty<0||ty>=count) continue;
            visible.add(zoom+"/"+((tx%count+count)%count)+"/"+ty);
        }
        tiles.visible=visible;
        for(int ty=firstY;ty<=lastY;ty++) for(int tx=firstX;tx<=lastX;tx++) {
            if(ty<0||ty>=count) continue;
            String key=zoom+"/"+((tx%count+count)%count)+"/"+ty;
            RectF rect=new RectF((float)(tx*tile-left),(float)(ty*tile-top),(float)((tx+1)*tile-left),(float)((ty+1)*tile-top));
            Bitmap bitmap=tiles.get(key,0);
            if(bitmap!=null) canvas.drawBitmap(bitmap,null,rect,paint);
            else {
                paint.setColor(0xFFCFDAD2); paint.setStrokeWidth(density); paint.setStyle(Paint.Style.STROKE); canvas.drawRect(rect,paint); paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF627269); paint.setTextSize(12*density);
                canvas.drawText(!tiles.source.ready()?"先到设置配置地图 Key":tiles.failed(key)?"地图未加载 · 查看核验":"加载地图…",rect.left+12*density,rect.top+28*density,paint);
            }
            for(int layer=1;layer<tiles.source.layerCount();layer++){
                Bitmap labels=tiles.get(key,layer);if(labels!=null)canvas.drawBitmap(labels,null,rect,paint);
            }
            tiles.request(key,this::invalidate);
        }
        if(Double.isFinite(realLat)) drawPoint(canvas,realLat,realLon,left,top,size,0xFF2479D8,false);
        if(routeMode) drawRoute(canvas,left,top,size);
        else if(Double.isFinite(selectedLat)) drawPoint(canvas,selectedLat,selectedLon,left,top,size,0xFF087F73,true);
        if(routeMode&&motionPosition!=null)drawPoint(canvas,motionPosition.latitude,motionPosition.longitude,left,top,size,0xFFF07820,true);
        paint.setColor(0xDFFFFFFF); canvas.drawRoundRect(new RectF(10*density,10*density,198*density,36*density),6*density,6*density,paint);
        paint.setColor(0xFF29453B); paint.setTextSize(12*density); canvas.drawText(routeMode?"轻点添加途经点 · 双指缩放":"轻点选位置 · 双指缩放",18*density,27*density,paint);
    }
    private float projectedX(double longitude,double left,double size) {
        double dx=MapMath.x(longitude,size)-(left+getWidth()/2.0);
        if(dx>size/2)dx-=size;if(dx< -size/2)dx+=size;
        return (float)(getWidth()/2.0+dx);
    }
    private void drawRoute(Canvas canvas,double left,double top,double size) {
        if(previewZoom!=zoom){
            if(previewPlan!=null)previewLine=RoutePreview.sample(previewPlan,size,1.5*density);
            else{
                ArrayList<SimulationEngine.Point> draft=new ArrayList<>(route);
                if(closedRoute&&draft.size()>2)draft.add(draft.get(0));
                previewLine=draft;
            }
            previewZoom=zoom;
        }
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);
        for(int index=1;index<previewLine.size();index++) {
            SimulationEngine.Point first=previewLine.get(index-1),second=previewLine.get(index);
            float x1=projectedX(first.longitude,left,size),x2=projectedX(second.longitude,left,size);
            if(x2-x1>size/2)x2-=size;if(x2-x1< -size/2)x2+=size;
            float y1=(float)(MapMath.y(first.latitude,size)-top),y2=(float)(MapMath.y(second.latitude,size)-top);
            for(int copy=-1;copy<=1;copy++) {
                float shift=(float)(copy*size);
                float margin=8*density;
                if(Math.max(x1+shift,x2+shift)<-margin||Math.min(x1+shift,x2+shift)>getWidth()+margin
                        ||Math.max(y1,y2)<-margin||Math.min(y1,y2)>getHeight()+margin)continue;
                paint.setColor(0xDDFFFFFF);paint.setStrokeWidth(7*density);canvas.drawLine(x1+shift,y1,x2+shift,y2,paint);
                paint.setColor(0xFF087F73);paint.setStrokeWidth(4*density);canvas.drawLine(x1+shift,y1,x2+shift,y2,paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);paint.setStrokeCap(Paint.Cap.BUTT);
        for(int index=0;index<route.size();index++) {
            SimulationEngine.Point point=route.get(index);
            float x=projectedX(point.longitude,left,size),y=(float)(MapMath.y(point.latitude,size)-top);
            paint.setColor(Color.WHITE);canvas.drawCircle(x,y,12*density,paint);
            paint.setColor(index==0?0xFF087F73:0xFF29645A);canvas.drawCircle(x,y,10*density,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(11*density);paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(Integer.toString(index+1),x,y+4*density,paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }
    /** Pure geometry, kept independent of View/Canvas for deterministic verification. */
    static final class RoutePreview {
        static final int MAX_EDGES=2048;
        private static final int MAX_DEPTH=12;
        private static final double MAX_ARC_METERS=6371008.8*Math.PI/12;

        static List<SimulationEngine.Point> sample(RoutePlan plan,double worldSize,double tolerancePixels){
            if(plan==null||!Double.isFinite(worldSize)||worldSize<=0
                    ||!Double.isFinite(tolerancePixels)||tolerancePixels<=0)
                throw new IllegalArgumentException("Invalid preview geometry");
            List<Edge> roots=new ArrayList<>();
            PriorityQueue<Edge> queue=new PriorityQueue<>(new Comparator<Edge>(){
                public int compare(Edge first,Edge second){return Double.compare(second.score,first.score);}
            });
            int segments=plan.points.size()-1+(plan.closed?1:0);
            double offset=0;
            for(int index=0;index<segments;index++){
                SimulationEngine.Point first=plan.points.get(index),second=plan.points.get((index+1)%plan.points.size());
                double end=offset+RoutePlan.distance(first,second);
                Edge edge=new Edge(plan,first,second,offset,end,0,worldSize,tolerancePixels);
                roots.add(edge);if(edge.score>1)queue.add(edge);offset=end;
            }
            int edges=segments;
            while(edges<MAX_EDGES&&!queue.isEmpty()){
                Edge edge=queue.remove();
                if(edge.depth>=MAX_DEPTH)continue;
                double middle=(edge.from+edge.to)/2;
                edge.left=new Edge(plan,edge.first,edge.middle,edge.from,middle,edge.depth+1,worldSize,tolerancePixels);
                edge.right=new Edge(plan,edge.middle,edge.second,middle,edge.to,edge.depth+1,worldSize,tolerancePixels);
                edges++;
                if(edge.left.score>1)queue.add(edge.left);
                if(edge.right.score>1)queue.add(edge.right);
            }
            ArrayList<SimulationEngine.Point> result=new ArrayList<>(edges+1);
            result.add(plan.points.get(0));
            for(Edge edge:roots)append(edge,result);
            return Collections.unmodifiableList(result);
        }
        private static void append(Edge edge,List<SimulationEngine.Point> result){
            if(edge.left==null)result.add(edge.second);
            else{append(edge.left,result);append(edge.right,result);}
        }
        private static final class Edge {
            final SimulationEngine.Point first,second,middle;
            final double from,to,score;
            final int depth;
            Edge left,right;
            Edge(RoutePlan plan,SimulationEngine.Point first,SimulationEngine.Point second,
                 double from,double to,int depth,double world,double tolerance){
                this.first=first;this.second=second;this.from=from;this.to=to;this.depth=depth;
                middle=plan.pointAt((from+to)/2);
                double x1=MapMath.x(first.longitude,world),x2=MapMath.x(second.longitude,world);
                if(x2-x1>world/2)x2-=world;if(x2-x1< -world/2)x2+=world;
                double y1=MapMath.y(first.latitude,world),y2=MapMath.y(second.latitude,world);
                double deviation=error(middle,x1,y1,x2,y2,world);
                // Quarter samples also detect an S-shaped projected arc whose
                // midpoint happens to lie on its chord.
                if(to-from>1000){
                    deviation=Math.max(deviation,error(plan.pointAt(from+(to-from)/4),x1,y1,x2,y2,world));
                    deviation=Math.max(deviation,error(plan.pointAt(from+(to-from)*3/4),x1,y1,x2,y2,world));
                }
                // Long arcs receive a few anchors even when their midpoint lies
                // on the chord. The global budget refines the largest errors first.
                score=Math.max(deviation/tolerance,(to-from)/MAX_ARC_METERS);
            }
            private static double error(SimulationEngine.Point point,double x1,double y1,double x2,double y2,double world){
                double x=MapMath.x(point.longitude,world);
                x+=Math.rint(((x1+x2)/2-x)/world)*world;
                double y=MapMath.y(point.latitude,world),dx=x2-x1,dy=y2-y1;
                double squared=dx*dx+dy*dy;
                double along=squared==0?0:Math.max(0,Math.min(1,((x-x1)*dx+(y-y1)*dy)/squared));
                return Math.hypot(x-x1-along*dx,y-y1-along*dy);
            }
        }
    }
    private void drawPoint(Canvas c,double lat,double lon,double left,double top,double size,int color,boolean pin) {
        double dx=MapMath.x(lon,size)-(left+getWidth()/2.0); if(dx>size/2)dx-=size;if(dx< -size/2)dx+=size;
        float x=(float)(getWidth()/2.0+dx), y=(float)(MapMath.y(lat,size)-top);
        if(pin) { paint.setColor(color); Path path=new Path();path.moveTo(x,y);path.lineTo(x-8*density,y-17*density);path.lineTo(x+8*density,y-17*density);path.close();c.drawPath(path,paint);y-=20*density; }
        paint.setColor(Color.WHITE);c.drawCircle(x,y,(pin?12:9)*density,paint);paint.setColor(color);c.drawCircle(x,y,(pin?9:6)*density,paint);
    }
    void resumeMap(){tiles.active=true;invalidate();}
    void pauseMap(){tiles.pause();}
    void close(){tiles.close();}
    String mapProblem(){return tiles.source.ready()?tiles.lastError:"天地图尚未配置 Key，请打开设置 → 地图来源";}
    MapSource source(){return tiles.source;}
    void reloadSource(){
        tiles.close();tiles=new TileStore(getContext());
        zoom=Math.min(zoom,tiles.source.maxZoom());previewZoom=Integer.MIN_VALUE;invalidate();
    }
    void retryMap(){tiles.retryFailures();invalidate();}
}
