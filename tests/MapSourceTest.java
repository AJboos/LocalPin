package local.pin;

/** Actual URL/configuration contract only; no network calls or real API keys. */
public final class MapSourceTest {
    private static int checks;
    private static final String KEY="AbCd0123EfGh4567";
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void equal(String actual,String expected,String label){check(expected.equals(actual),label);}
    private static void invalid(Runnable action,String label){boolean bad=false;try{action.run();}catch(IllegalArgumentException expected){bad=true;}check(bad,label);}
    private static void unavailable(Runnable action,String label){boolean bad=false;try{action.run();}catch(IllegalStateException expected){bad=true;}check(bad,label);}
    private static String repeated(char character,int count){StringBuilder value=new StringBuilder();for(int i=0;i<count;i++)value.append(character);return value.toString();}

    public static void main(String[] args){
        MapSource osm=new MapSource(MapSource.OSM,null);
        check(osm.provider()==MapSource.OSM&&osm.ready(),"public source ready without key");
        check(osm.layerCount()==1&&osm.maxZoom()==19,"public source layer count and zoom cap");
        equal(osm.tileUrl(0,3,2,7),"https://tile.openstreetmap.org/3/2/7.png","OSM exact z/x/y URL");
        equal(osm.tileUrl(0,19,524287,0),"https://tile.openstreetmap.org/19/524287/0.png","OSM maximum zoom edge");
        check(osm.attribution().contains("OpenStreetMap"),"OSM visible attribution");
        equal(osm.attributionUrl(),"https://www.openstreetmap.org/copyright","OSM attribution URL");

        MapSource tdt=new MapSource(MapSource.TIANDITU,KEY);
        check(tdt.provider()==MapSource.TIANDITU&&tdt.ready(),"configured domestic source ready");
        check(tdt.layerCount()==2&&tdt.maxZoom()==18,"domestic source two layers and cap");
        String suffix="&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX=7&TILEROW=13&TILECOL=11&tk="+KEY;
        equal(tdt.tileUrl(0,7,11,13),"https://t0.tianditu.gov.cn/vec_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=vec"+suffix,"vector WMTS exact parameters");
        equal(tdt.tileUrl(1,7,11,13),"https://t0.tianditu.gov.cn/cva_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=cva"+suffix,"annotation WMTS exact parameters");
        check(tdt.label().contains("天地图")&&tdt.attribution().contains("天地图"),"domestic label and attribution");
        equal(tdt.helpUrl(),"https://console.tianditu.gov.cn/api/key","official key configuration help");
        check(tdt.attributionUrl().startsWith("https://")&&tdt.attributionUrl().contains("tianditu.gov.cn"),"official HTTPS attribution URL");
        check(!tdt.toString().contains(KEY),"default string representation does not expose key");
        check(!tdt.label().contains(KEY)&&!tdt.attribution().contains(KEY)
                &&!tdt.attributionUrl().contains(KEY)&&!tdt.helpUrl().contains(KEY),"display metadata never contains key");

        for(String absent:new String[]{null,"","   ","\t\r\n"}){
            MapSource missing=new MapSource(MapSource.TIANDITU,absent);
            check(!missing.ready(),"missing or blank key is unready");
            check(missing.layerCount()==2&&missing.maxZoom()==18,"unready config still describes its layers");
            unavailable(()->missing.tileUrl(0,1,0,0),"missing key never yields vector request or fallback");
            unavailable(()->missing.tileUrl(1,1,0,0),"missing key never yields annotation request or fallback");
        }
        check(new MapSource(MapSource.OSM,"").ready(),"OSM is not made unready by missing key");
        check(new MapSource(MapSource.TIANDITU,repeated('a',16)).ready(),"16-character minimum key");
        check(new MapSource(MapSource.TIANDITU,repeated('Z',128)).ready(),"128-character maximum key");
        equal(new MapSource(MapSource.TIANDITU,"  "+KEY+" \n").tileUrl(0,7,11,13),tdt.tileUrl(0,7,11,13),"boundary whitespace trimmed consistently");
        for(String key:new String[]{repeated('a',15),repeated('a',129),KEY+"&evil=1",KEY+"?x",KEY+"#x",KEY+"/x",KEY+"%2f",KEY+"=x",KEY+"+x",KEY+"_x",KEY+"-x",KEY+".x",KEY+" x",KEY+"\nx",KEY+"中",KEY+"Ａ",KEY+"\u0000x"})
            invalid(()->new MapSource(MapSource.TIANDITU,key),"invalid/injected/non-ASCII key rejected");
        invalid(()->new MapSource(MapSource.OSM,KEY+"&x=1"),"unused malformed key not silently accepted");
        for(int provider:new int[]{-1,2,Integer.MIN_VALUE,Integer.MAX_VALUE})
            invalid(()->new MapSource(provider,KEY),"unknown provider rejected");

        for(MapSource source:new MapSource[]{osm,tdt}){
            invalid(()->source.tileUrl(-1,1,0,0),"negative layer");
            invalid(()->source.tileUrl(source.layerCount(),1,0,0),"layer beyond source count");
            invalid(()->source.tileUrl(0,0,0,0),"zoom zero");
            invalid(()->source.tileUrl(0,-1,0,0),"negative zoom");
            invalid(()->source.tileUrl(0,source.maxZoom()+1,0,0),"zoom above source cap");
            invalid(()->source.tileUrl(0,32,0,0),"overflowing shift zoom rejected before shift");
            for(int zoom=1;zoom<=source.maxZoom();zoom++){
                final int z=zoom,count=1<<zoom;
                for(int layer=0;layer<source.layerCount();layer++){
                    String first=source.tileUrl(layer,z,0,0),last=source.tileUrl(layer,z,count-1,count-1);
                    check(first.startsWith("https://")&&last.startsWith("https://"),"valid zoom corners use HTTPS");
                    check(first.contains(source==osm?"tile.openstreetmap.org/":"t0.tianditu.gov.cn/"),"official source only");
                }
                invalid(()->source.tileUrl(0,z,-1,0),"negative tile x");
                invalid(()->source.tileUrl(0,z,0,-1),"negative tile y");
                invalid(()->source.tileUrl(0,z,count,0),"tile x outside matrix");
                invalid(()->source.tileUrl(0,z,0,count),"tile y outside matrix");
            }
        }
        System.out.println("PASS: "+checks+" map-source URL, layer, bound and key-validation assertions");
    }
}
