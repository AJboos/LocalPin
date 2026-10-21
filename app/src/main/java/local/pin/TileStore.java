package local.pin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.http.HttpResponseCache;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Visible tiles only, two connections and HTTPS/HTTP cache. Provider changes create a fresh store. */
final class TileStore {
    final MapSource source;
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(16*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}
    };
    private final Set<String> pending=Collections.synchronizedSet(new HashSet<>());
    private final java.util.Map<String,Long> failed=new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,20,TimeUnit.SECONDS,new ArrayBlockingQueue<>(96));
    private final Handler main=new Handler(Looper.getMainLooper());
    volatile boolean active=true;
    volatile Set<String> visible=Collections.emptySet();
    private volatile boolean closed;
    volatile String lastError="";

    TileStore(Context context){
        source=MapPreferences.load(context);
        try{
            if(HttpResponseCache.getInstalled()==null)
                HttpResponseCache.install(new File(context.getCacheDir(),"map-http"),48L*1024*1024);
        }catch(java.io.IOException ignored){lastError="地图缓存初始化失败，请清理缓存后重试";}
    }
    Bitmap get(String key,int layer){return cache.get(layer+"/"+key);}
    boolean failed(String key){
        for(int layer=0;layer<source.layerCount();layer++)if(failed.containsKey(layer+"/"+key))return true;
        return false;
    }
    void request(String key,Runnable redraw){
        if(!source.ready())return;
        for(int layer=0;layer<source.layerCount();layer++)requestLayer(key,layer,redraw);
    }
    private void requestLayer(String tile,int layer,Runnable redraw){
        String cacheKey=layer+"/"+tile;
        if(!active||closed||cache.get(cacheKey)!=null||!pending.add(cacheKey))return;
        Long last=failed.get(cacheKey);
        if(last!=null&&System.currentTimeMillis()-last<30000){pending.remove(cacheKey);return;}
        try{
            workers.execute(()->{
                HttpURLConnection connection=null;String reason=null;
                try{
                    if(!active||closed||!visible.contains(tile))return;
                    if(HttpResponseCache.getInstalled()==null)throw new java.io.IOException("Map cache unavailable");
                    String[] coordinates=tile.split("/");
                    if(coordinates.length!=3)throw new IllegalArgumentException("Invalid tile");
                    String address=source.tileUrl(layer,Integer.parseInt(coordinates[0]),Integer.parseInt(coordinates[1]),Integer.parseInt(coordinates[2]));
                    connection=(HttpURLConnection)new URL(address).openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent","LocalPin/2.2.1-preview (Android; map picker; https://github.com/AJboos/LocalPin)");
                    connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
                    int code=connection.getResponseCode();
                    if(code!=200){reason="地图服务返回 HTTP "+code;throw new java.io.IOException("Tile unavailable");}
                    try(InputStream stream=connection.getInputStream()){
                        java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
                        byte[] chunk=new byte[8192];int read;
                        while((read=stream.read(chunk))!=-1){
                            if(buffer.size()+read>1024*1024)throw new java.io.IOException("Tile too large");
                            buffer.write(chunk,0,read);
                        }
                        byte[] bytes=buffer.toByteArray();
                        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                        if(options.outWidth!=256||options.outHeight!=256){
                            reason=source.provider()==MapSource.TIANDITU?"天地图未返回地图图块，请检查 Key 和服务开通状态":"地图服务未返回有效图块";
                            throw new java.io.IOException("Invalid tile dimensions");
                        }
                        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                        if(bitmap==null)throw new java.io.IOException("Invalid tile");
                        if(!closed){cache.put(cacheKey,bitmap);failed.remove(cacheKey);if(failed.isEmpty())lastError="";}
                    }
                }catch(Exception failure){
                    // Never include exception messages or URLs: a URL may contain a private key.
                    lastError=reason!=null?reason:
                        failure instanceof java.net.UnknownHostException?"无法解析地图服务器地址":
                        failure instanceof java.net.SocketTimeoutException?"地图服务器连接或读取超时":
                        failure instanceof javax.net.ssl.SSLException?"地图 HTTPS 证书或连接失败":
                        HttpResponseCache.getInstalled()==null?"地图缓存不可用，请清理缓存后重试":"地图连接或图片解码失败";
                    failed.put(cacheKey,System.currentTimeMillis());if(failed.size()>512)failed.clear();
                }finally{
                    if(connection!=null)connection.disconnect();pending.remove(cacheKey);
                    if(!closed)main.post(redraw);
                }
            });
        }catch(java.util.concurrent.RejectedExecutionException ignored){pending.remove(cacheKey);}
    }
    void pause(){
        active=false;
        try{workers.execute(()->{HttpResponseCache http=HttpResponseCache.getInstalled();if(http!=null)http.flush();});}
        catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    void retryFailures(){failed.clear();lastError="";}
    void close(){closed=true;active=false;workers.shutdownNow();main.removeCallbacksAndMessages(null);cache.evictAll();}
}
