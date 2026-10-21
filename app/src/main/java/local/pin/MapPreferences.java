package local.pin;

import android.content.Context;
import android.content.SharedPreferences;

/** Map credentials stay in the application's private preferences. Never log them. */
final class MapPreferences {
    private static final String PREFS="map-source";
    private MapPreferences(){}
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static MapSource load(Context context){
        SharedPreferences values=prefs(context);
        try{return new MapSource(values.getInt("provider",MapSource.OSM),values.getString("key",""));}
        catch(IllegalArgumentException invalid){return new MapSource(MapSource.OSM,"");}
    }
    static String savedKey(Context context){return prefs(context).getString("key","");}
    static void save(Context context,int provider,String key){
        new MapSource(provider,key);
        prefs(context).edit().putInt("provider",provider).putString("key",key.trim()).apply();
    }
}
