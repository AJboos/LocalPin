package local.pin;

/** Monotonic active-time integration with at most five seconds of movement per delivery. */
final class MotionSession {
    private static final long NODE_MILLIS=5000;
    private static final long MAX_ADVANCE_MILLIS=5000;
    final RoutePlan plan;
    private final long seed;
    private long lastMillis,activeMillis,prefixNode;
    private double prefixMeters,travelledMeters;
    private boolean paused,completed;

    static final class Sample {
        final SimulationEngine.Point point;
        final double travelledMeters,speedMps,bearingDegrees;
        final long activeMillis;
        final boolean paused,autoPaused,completed;
        Sample(SimulationEngine.Point point,double travelled,long active,double speed,double bearing,
               boolean paused,boolean automatic,boolean completed) {
            this.point=point;travelledMeters=travelled;activeMillis=active;
            speedMps=speed;bearingDegrees=bearing;this.paused=paused;autoPaused=automatic;this.completed=completed;
        }
    }

    MotionSession(RoutePlan plan,long nowMillis,long seed) {
        if(plan==null||nowMillis<0)throw new IllegalArgumentException("Invalid motion session");
        this.plan=plan;this.lastMillis=nowMillis;this.seed=seed;
    }

    synchronized Sample update(long nowMillis) {
        checkTime(nowMillis);
        long delta=nowMillis-lastMillis;lastMillis=nowMillis;
        if(paused||completed)return snapshot();
        // Android may repeatedly delay background delivery. Advance by a bounded
        // step so sparse ticks still move, without replaying a long wall-time gap
        // or requiring manual resume. Each delivery establishes a fresh timebase.
        long advance=Math.min(delta,MAX_ADVANCE_MILLIS);
        long next=activeMillis>Long.MAX_VALUE-advance?Long.MAX_VALUE:activeMillis+advance;
        if(plan.durationLimitMillis>0)next=Math.min(next,plan.durationLimitMillis);
        double nextDistance=distanceAt(next);
        double end=plan.closed?Double.POSITIVE_INFINITY:plan.lengthMeters;
        if(plan.distanceLimitMeters>0)end=Math.min(end,plan.distanceLimitMeters);
        if(nextDistance>=end) {
            double low=activeMillis,high=next;
            for(int i=0;i<60;i++) {
                double middle=(low+high)/2;
                if(distanceAt(middle)<end)low=middle;else high=middle;
            }
            activeMillis=Math.max(activeMillis,Math.min(next,Math.round((low+high)/2)));
            travelledMeters=end;completed=true;
        } else {
            activeMillis=next;travelledMeters=nextDistance;
            completed=plan.durationLimitMillis>0&&activeMillis>=plan.durationLimitMillis;
        }
        return snapshot();
    }

    synchronized Sample pause(long nowMillis) {
        update(nowMillis);
        if(!completed)paused=true;
        return snapshot();
    }

    synchronized Sample resume(long nowMillis) {
        checkTime(nowMillis);
        if(!paused)return update(nowMillis);
        lastMillis=nowMillis;
        if(!completed)paused=false;
        return snapshot();
    }

    private void checkTime(long now) {
        if(now<lastMillis)throw new IllegalArgumentException("Monotonic clock moved backwards");
    }

    private double nodeSpeed(long node) {
        if(plan.minSpeedMps==plan.maxSpeedMps)return plan.minSpeedMps;
        long value=seed+0x9e3779b97f4a7c15L*(node+1);
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        value^=value>>>31;
        double unit=(value>>>11)*0x1.0p-53;
        return plan.minSpeedMps+(plan.maxSpeedMps-plan.minSpeedMps)*unit;
    }

    /** Complete nodes are accumulated once, always in the same order for every cadence. */
    private double distanceAt(double millis) {
        long node=(long)(millis/NODE_MILLIS);
        // A distance-limit solve may ask about a previous node after the high
        // endpoint was integrated. At most two nodes are involved in one tick.
        while(prefixNode>node) {
            prefixNode--;prefixMeters-=(nodeSpeed(prefixNode)+nodeSpeed(prefixNode+1))*2.5;
        }
        while(prefixNode<node) {
            prefixMeters+=(nodeSpeed(prefixNode)+nodeSpeed(prefixNode+1))*2.5;prefixNode++;
        }
        double seconds=(millis-node*NODE_MILLIS)/1000.0;
        double start=nodeSpeed(node),slope=(nodeSpeed(node+1)-start)/5.0;
        return prefixMeters+start*seconds+0.5*slope*seconds*seconds;
    }

    private Sample snapshot() {
        long node=activeMillis/NODE_MILLIS;
        double fraction=(activeMillis%NODE_MILLIS)/(double)NODE_MILLIS;
        double speed=paused||completed?0:nodeSpeed(node)+(nodeSpeed(node+1)-nodeSpeed(node))*fraction;
        double bearing=plan.bearingAt(travelledMeters);
        SimulationEngine.Point coordinate=plan.pointAt(travelledMeters);
        SimulationEngine.Point point=new SimulationEngine.Point(coordinate.latitude,coordinate.longitude,speed,bearing);
        return new Sample(point,travelledMeters,activeMillis,speed,bearing,paused,false,completed);
    }
}
