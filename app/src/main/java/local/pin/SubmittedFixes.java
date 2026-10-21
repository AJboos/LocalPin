package local.pin;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Match callbacks to the submitted sample, rather than the later moving target. */
final class SubmittedFixes {
    private static final int CAPACITY=64;
    private static final long TIMESTAMP_TOLERANCE_NANOS=1_000_000L;
    private static final class Fix {
        final SimulationEngine.Point point;
        final long elapsedNanos;
        Fix(SimulationEngine.Point point,long elapsedNanos){this.point=point;this.elapsedNanos=elapsedNanos;}
    }
    private final ArrayDeque<Fix> fixes=new ArrayDeque<>();
    synchronized void clear(){fixes.clear();}
    synchronized void record(SimulationEngine.Point point,long elapsedNanos){
        if(point==null||elapsedNanos<0)throw new IllegalArgumentException("Invalid submitted fix");
        fixes.addLast(new Fix(point,elapsedNanos));
        while(fixes.size()>CAPACITY)fixes.removeFirst();
    }
    synchronized boolean matches(double lat,double lon,boolean mock,long elapsedNanos,long nowMillis){
        if(!mock||elapsedNanos<0)return false;
        Iterator<Fix> iterator=fixes.descendingIterator();
        while(iterator.hasNext()){
            Fix fix=iterator.next();
            if(Math.abs(elapsedNanos-fix.elapsedNanos)<=TIMESTAMP_TOLERANCE_NANOS){
                return PositionCheck.evaluate(fix.point.latitude,fix.point.longitude,lat,lon,true,
                        elapsedNanos/1_000_000L,nowMillis).state==PositionCheck.State.MATCH;
            }
        }
        return false;
    }
}
