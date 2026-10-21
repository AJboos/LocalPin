package local.pin;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.HashSet;
import java.util.Set;

final class MapView extends View {
    interface Listener { void selected(double lat, double lon); }
    private final TileStore tiles;
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
    void center(double lat, double lon, int level) { centerLat=MapMath.clamp(lat,-85.05112878,85.05112878); centerLon=lon; zoom=(int)MapMath.clamp(level,2,19); invalidate(); }
    void real(double lat, double lon) { realLat=lat; realLon=lon; center(lat,lon,17); }
    void hideRealMarker(){realLat=Double.NaN;invalidate();}
    void select(double lat, double lon) { selectedLat=lat; selectedLon=lon; if(listener!=null) listener.selected(lat,lon); invalidate(); }
    void restoreSelection(double lat,double lon) { selectedLat=lat; selectedLon=lon; invalidate(); }
    void clearSelection() { selectedLat=Double.NaN; invalidate(); }
    void zoomBy(int delta) { zoomAt(delta, getWidth()/2f, getHeight()/2f); }
    private void zoomAt(int delta, float focusX, float focusY) {
        int next=(int)MapMath.clamp(zoom+delta,2,19); if(next==zoom) return;
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
            Bitmap bitmap=tiles.get(key);
            if(bitmap!=null) canvas.drawBitmap(bitmap,null,rect,paint);
            else {
                paint.setColor(0xFFCFDAD2); paint.setStrokeWidth(density); paint.setStyle(Paint.Style.STROKE); canvas.drawRect(rect,paint); paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF627269); paint.setTextSize(12*density);
                canvas.drawText(tiles.failed(key)?"地图未加载 · 检查网络":"加载地图…",rect.left+12*density,rect.top+28*density,paint);
                tiles.request(key,this::invalidate);
            }
        }
        if(Double.isFinite(realLat)) drawPoint(canvas,realLat,realLon,left,top,size,0xFF2479D8,false);
        if(Double.isFinite(selectedLat)) drawPoint(canvas,selectedLat,selectedLon,left,top,size,0xFF087F73,true);
        paint.setColor(0xDFFFFFFF); canvas.drawRoundRect(new RectF(10*density,10*density,163*density,36*density),6*density,6*density,paint);
        paint.setColor(0xFF29453B); paint.setTextSize(12*density); canvas.drawText("轻点选位置 · 双指缩放",18*density,27*density,paint);
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
    String mapProblem(){return tiles.lastError;}
    void retryMap(){tiles.retryFailures();invalidate();}
}
