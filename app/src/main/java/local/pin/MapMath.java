package local.pin;

/** WGS84 / Web Mercator only. Never injects coordinates from GCJ02 map imagery. */
public final class MapMath {
    private MapMath() {}
    public static double clamp(double n, double min, double max) { return Math.max(min, Math.min(max, n)); }
    public static double wrap(double n, double size) { return (n % size + size) % size; }
    public static double x(double lon, double size) { return (lon + 180.0) / 360.0 * size; }
    public static double y(double lat, double size) {
        double r = Math.toRadians(clamp(lat, -85.05112878, 85.05112878));
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * size;
    }
    public static double lon(double x, double size) { return wrap(x, size) / size * 360.0 - 180.0; }
    public static double lat(double y, double size) { return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * clamp(y, 0, size) / size)))); }
    public static boolean valid(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }
}
