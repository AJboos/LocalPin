package local.pin;

/** Pure local comparison of a requested WGS84 point with a framework readback. */
public final class PositionCheck {
    public enum State { MATCH, OFFSET, STALE, NOT_MOCK, NO_FIX, BAD_TIME }
    public static final class Result {
        public final State state;
        public final double distanceMeters;
        public final long ageMillis;
        Result(State state,double distanceMeters,long ageMillis) {
            this.state=state;this.distanceMeters=distanceMeters;this.ageMillis=ageMillis;
        }
    }
    private PositionCheck() {}
    public static Result evaluate(double expectedLat,double expectedLon,double lat,double lon,
                                  boolean mock,long fixElapsedMillis,long nowElapsedMillis) {
        if(!MapMath.valid(expectedLat,expectedLon)||!MapMath.valid(lat,lon))
            return new Result(State.NO_FIX,Double.NaN,0);
        double distance=distanceMeters(expectedLat,expectedLon,lat,lon);
        long age=nowElapsedMillis-fixElapsedMillis;
        if(fixElapsedMillis<=0||age< -1000)return new Result(State.BAD_TIME,distance,age);
        if(age>5000)return new Result(State.STALE,distance,age);
        if(!mock)return new Result(State.NOT_MOCK,distance,Math.max(0,age));
        return new Result(distance<=10?State.MATCH:State.OFFSET,distance,Math.max(0,age));
    }
    public static double distanceMeters(double aLat,double aLon,double bLat,double bLon) {
        double dLat=Math.toRadians(bLat-aLat),dLon=Math.toRadians(bLon-aLon);
        double sLat=Math.sin(dLat/2),sLon=Math.sin(dLon/2);
        double a=sLat*sLat+Math.cos(Math.toRadians(aLat))*Math.cos(Math.toRadians(bLat))*sLon*sLon;
        return 6371008.8*2*Math.atan2(Math.sqrt(MapMath.clamp(a,0,1)),Math.sqrt(1-MapMath.clamp(a,0,1)));
    }
}
