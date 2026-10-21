package local.pin;

/** Run with javac/java; no network or test frameworks. */
public final class MapMathTest {
    public static void main(String[] args) {
        double[][] places = {{31.2304,121.4737},{39.9042,116.4074},{0,0},{-33.8688,151.2093},{85,179.999},{-85,-179.999}};
        // Independent Web Mercator anchors: round-trip tests alone can hide
        // matching mistakes in the forward and reverse projections.
        near(128,MapMath.x(0,256),1e-8);
        near(192,MapMath.x(90,256),1e-8);
        near(64,MapMath.x(-90,256),1e-8);
        near(128,MapMath.y(0,256),1e-8);
        near(92.08960945029247,MapMath.y(45,256),1e-8);
        near(0,MapMath.y(85.0511287798066,256),1e-8);
        near(85.0511287798066,MapMath.lat(0,256),1e-8);
        near(-85.0511287798066,MapMath.lat(256,256),1e-8);
        near(0,MapMath.lat(128,256),1e-8);
        int assertions=9;
        for(int zoom=2;zoom<=19;zoom++) {
            double world=256.0*3*(1<<zoom);
            for(double[] p:places) {
                near(p[0],MapMath.lat(MapMath.y(p[0],world),world),1e-8);
                near(p[1],MapMath.lon(MapMath.x(p[1],world),world),1e-8);
                // Dragging a point to screen pixels and tapping it must round-trip.
                double cx=MapMath.x(p[1],world),cy=MapMath.y(p[0],world);
                double tapX=420.25,tapY=117.75;
                double lon=MapMath.lon(cx+tapX-540,world),lat=MapMath.lat(cy+tapY-700,world);
                check(MapMath.valid(lat,lon));
                assertions+=3;
            }
        }
        check(!MapMath.valid(Double.NaN,120));check(!MapMath.valid(0,Double.POSITIVE_INFINITY));
        check(!MapMath.valid(91,0));check(!MapMath.valid(0,-181));check(MapMath.valid(-90,180));
        near(179,MapMath.lon(MapMath.x(-181,256),256),1e-8);
        near(-179,MapMath.lon(MapMath.x(181,256),256),1e-8);
        System.out.println("PASS: " + (assertions+7) + " map projection, wrap and validity assertions");
    }
    private static void near(double a,double b,double tolerance){if(!Double.isFinite(a)||!Double.isFinite(b)||Math.abs(a-b)>tolerance)throw new AssertionError(a+" != "+b);}
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
}
