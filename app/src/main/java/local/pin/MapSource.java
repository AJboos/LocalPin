package local.pin;

/** Immutable map configuration; constructs official URLs but performs no network I/O. */
final class MapSource {
    static final int OSM=0,TIANDITU=1;
    private final int provider;
    private final String key;

    MapSource(int provider,String key) {
        if(provider!=OSM&&provider!=TIANDITU)
            throw new IllegalArgumentException("Unknown map provider");
        String normalized=key==null?"":key.trim();
        if(!normalized.isEmpty()) {
            if(normalized.length()<16||normalized.length()>128)
                throw new IllegalArgumentException("Invalid map API key");
            for(int i=0;i<normalized.length();i++) {
                char value=normalized.charAt(i);
                if(!(value>='a'&&value<='z'||value>='A'&&value<='Z'||value>='0'&&value<='9'))
                    throw new IllegalArgumentException("Invalid map API key");
            }
        }
        this.provider=provider;
        // Do not retain an unused secret for the public, keyless provider.
        this.key=provider==TIANDITU?normalized:"";
    }

    int provider(){return provider;}
    int layerCount(){return provider==OSM?1:2;}
    int maxZoom(){return provider==OSM?19:18;}
    boolean ready(){return provider==OSM||!key.isEmpty();}
    String label(){return provider==OSM?"OpenStreetMap":"天地图";}
    String attribution(){return provider==OSM?"© OpenStreetMap contributors":"地图来源：天地图（国家基础地理信息中心）";}
    String attributionUrl(){return provider==OSM?"https://www.openstreetmap.org/copyright":"https://www.tianditu.gov.cn/";}
    String helpUrl(){return provider==OSM?"https://operations.osmfoundation.org/policies/tiles/":"https://console.tianditu.gov.cn/api/key";}

    String tileUrl(int layer,int zoom,int x,int y) {
        if(layer<0||layer>=layerCount())throw new IllegalArgumentException("Invalid map layer");
        if(zoom<1||zoom>maxZoom())throw new IllegalArgumentException("Invalid map zoom");
        int count=1<<zoom;
        if(x<0||x>=count||y<0||y>=count)
            throw new IllegalArgumentException("Invalid map tile coordinates");
        if(!ready())throw new IllegalStateException("Map API key required");
        if(provider==OSM)return "https://tile.openstreetmap.org/"+zoom+"/"+x+"/"+y+".png";
        String kind=layer==0?"vec":"cva";
        return "https://t0.tianditu.gov.cn/"+kind+"_w/wmts?SERVICE=WMTS&REQUEST=GetTile"
                +"&VERSION=1.0.0&LAYER="+kind+"&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles"
                +"&TILEMATRIX="+zoom+"&TILEROW="+y+"&TILECOL="+x+"&tk="+key;
    }
}
