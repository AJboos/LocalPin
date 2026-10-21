package local.pin;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private draft persistence only. Loading a draft never starts a simulation. */
final class RoutePreferences {
    private static final String PREFS = "route-draft", PAYLOAD = "routeDraft";
    private RoutePreferences() { }

    static RouteEditor load(Context context, Bundle state) {
        String payload = state != null ? state.getString(PAYLOAD)
                : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PAYLOAD, null);
        RouteEditor result = new RouteEditor();
        if (payload == null || payload.length() > 65536) return result;
        try {
            JSONObject value = new JSONObject(payload);
            if (value.getInt("version") != 1) return result;
            result.closed = value.getBoolean("closed");
            result.openByDuration = value.getBoolean("openByDuration");
            result.loopByDuration = value.getBoolean("loopByDuration");
            result.loopDistanceByDuration = value.optBoolean("loopDistanceByDuration", false);
            result.minKmh = value.getDouble("minKmh"); result.maxKmh = value.getDouble("maxKmh");
            result.durationMinutes = value.getDouble("durationMinutes"); result.distanceMeters = value.getDouble("distanceMeters");
            result.validateSettings();
            JSONArray points = value.getJSONArray("points");
            if (points.length() > RouteEditor.MAX_POINTS * 2 || points.length() % 2 != 0) return new RouteEditor();
            for (int index = 0; index < points.length(); index += 2) result.add(points.getDouble(index), points.getDouble(index + 1));
            return result;
        } catch (Exception invalid) { return new RouteEditor(); }
    }
    static boolean routeMode(Context context, Bundle state) {
        return state != null ? state.getBoolean("routeMode", false)
                : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("routeMode", false);
    }
    private static String encode(RouteEditor draft) {
        try {
            JSONArray points = new JSONArray();
            for (SimulationEngine.Point point : draft.points()) { points.put(point.latitude); points.put(point.longitude); }
            return new JSONObject().put("version", 1).put("closed", draft.closed)
                    .put("openByDuration", draft.openByDuration).put("loopByDuration", draft.loopByDuration)
                    .put("loopDistanceByDuration", draft.loopDistanceByDuration)
                    .put("minKmh", draft.minKmh).put("maxKmh", draft.maxKmh)
                    .put("durationMinutes", draft.durationMinutes).put("distanceMeters", draft.distanceMeters)
                    .put("points", points).toString();
        } catch (Exception invalid) { return null; }
    }
    static void save(Context context, RouteEditor draft, boolean routeMode) {
        String value = encode(draft);
        if (value != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PAYLOAD, value).putBoolean("routeMode", routeMode).apply();
    }
    static void save(Bundle state, RouteEditor draft, boolean routeMode) {
        String value = encode(draft);
        if (value != null) state.putString(PAYLOAD, value);
        state.putBoolean("routeMode", routeMode);
    }
}
