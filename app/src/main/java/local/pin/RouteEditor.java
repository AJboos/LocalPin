package local.pin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mutable UI draft. A running RoutePlan always receives its own immutable copy. */
final class RouteEditor {
    static final int MAX_POINTS = RoutePlan.MAX_POINTS;
    private final ArrayList<SimulationEngine.Point> vertices = new ArrayList<>();
    boolean closed, openByDuration, loopByDuration, loopDistanceByDuration;
    double minKmh = 3, maxKmh = 5, durationMinutes = 10, distanceMeters = 1000;

    List<SimulationEngine.Point> points() { return Collections.unmodifiableList(new ArrayList<>(vertices)); }
    int size() { return vertices.size(); }
    void add(double latitude, double longitude) {
        if (vertices.size() >= MAX_POINTS) throw new IllegalArgumentException("路线最多添加 200 个途经点");
        SimulationEngine.Point point = new SimulationEngine.Point(latitude, longitude);
        if (!vertices.isEmpty() && RoutePlan.distance(vertices.get(vertices.size() - 1), point) <= 1e-6)
            throw new IllegalArgumentException("相邻途经点不能重合，请点击另一个位置");
        vertices.add(point);
    }
    void undo() { if (!vertices.isEmpty()) vertices.remove(vertices.size() - 1); }
    void clear() { vertices.clear(); }
    double length(boolean closing) {
        double length = 0;
        for (int index = 1; index < vertices.size(); index++)
            length += RoutePlan.distance(vertices.get(index - 1), vertices.get(index));
        if (closing && vertices.size() > 1) length += RoutePlan.distance(vertices.get(vertices.size() - 1), vertices.get(0));
        return length;
    }
    void validateSettings() {
        if (!Double.isFinite(minKmh) || !Double.isFinite(maxKmh) || minKmh <= 0 || maxKmh < minKmh || maxKmh > 360)
            throw new IllegalArgumentException("速度应大于零且不超过 360 km/h，最高不能低于最低");
        if (!Double.isFinite(durationMinutes) || durationMinutes <= 0
                || durationMinutes * 60000 < 1 || durationMinutes * 60000 >= Long.MAX_VALUE)
            throw new IllegalArgumentException("运行时长应为大于零的分钟数");
        if (!Double.isFinite(distanceMeters) || distanceMeters <= 0)
            throw new IllegalArgumentException("累计距离应为大于零的米数");
    }
    RoutePlan plan() {
        validateSettings();
        if (vertices.size() < (closed ? 3 : 2))
            throw new IllegalArgumentException(closed ? "闭环至少需要 3 个不同的途经点" : "请在地图上添加至少 2 个途经点");
        double length = length(closed);
        if (!Double.isFinite(length) || length < 1) throw new IllegalArgumentException("路线总长至少需要 1 米");
        long requestedMillis = Math.round(durationMinutes * 60000);
        long duration = (!closed && openByDuration || closed && loopByDuration && !loopDistanceByDuration)
                ? Math.round(durationMinutes * 60000) : 0;
        double minimum = minKmh / 3.6, maximum = maxKmh / 3.6;
        double distance = closed && (!loopByDuration || loopDistanceByDuration) ? distanceMeters : 0;
        if (!closed && openByDuration) minimum = maximum = length / (requestedMillis / 1000.0);
        if (closed && loopDistanceByDuration) minimum = maximum = distance / (requestedMillis / 1000.0);
        if (!Double.isFinite(maximum) || maximum > 100)
            throw new IllegalArgumentException("自动计算速度超过 360 km/h，请增加时长或缩短距离");
        try { return new RoutePlan(points(), closed, minimum, maximum, duration, distance); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("路线或参数超出支持范围，请检查点位、速度、时长和距离");
        }
    }
    void showPlan(RoutePlan plan) {
        vertices.clear(); vertices.addAll(plan.points);
        boolean distanceTimed = loopDistanceByDuration && plan.closed && plan.distanceLimitMeters > 0
                && plan.minSpeedMps == plan.maxSpeedMps;
        closed = plan.closed;
        // Accept both raw-vertex and explicitly closed representations.
        if (closed && vertices.size() > 2 && RoutePlan.distance(vertices.get(0), vertices.get(vertices.size() - 1)) <= 1e-6)
            vertices.remove(vertices.size() - 1);
        minKmh = plan.minSpeedMps * 3.6; maxKmh = plan.maxSpeedMps * 3.6;
        openByDuration = !closed && plan.durationLimitMillis > 0;
        loopByDuration = closed && plan.durationLimitMillis > 0;
        loopDistanceByDuration = distanceTimed;
        if (plan.durationLimitMillis > 0) durationMinutes = plan.durationLimitMillis / 60000.0;
        if (plan.distanceLimitMeters > 0) distanceMeters = plan.distanceLimitMeters;
        if (distanceTimed) durationMinutes = distanceMeters / plan.minSpeedMps / 60;
    }
}
