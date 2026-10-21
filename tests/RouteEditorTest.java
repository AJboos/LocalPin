package local.pin;

import java.util.List;

/** Real production draft/plan conversions; no Android or mirrored editor implementation. */
public final class RouteEditorTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void near(double actual,double expected,double tolerance,String label){check(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,label+": "+actual+" != "+expected);}
    private static void invalid(Runnable task,String label){boolean bad=false;try{task.run();}catch(IllegalArgumentException expected){bad=true;}check(bad,label);}
    private static RouteEditor editor(){
        RouteEditor value=new RouteEditor();value.add(0,0);value.add(0,.001);value.add(.001,.001);
        value.minKmh=3.6;value.maxKmh=7.2;value.durationMinutes=10;value.distanceMeters=1200;
        return value;
    }
    private static void limits(RoutePlan plan,long duration,double distance,String label){
        check(plan.durationLimitMillis==duration,label+" duration");
        near(plan.distanceLimitMeters,distance,0,label+" distance");
        check(!(plan.durationLimitMillis>0&&plan.distanceLimitMeters>0),label+" exclusive limits");
    }
    private static void samePlan(RoutePlan a,RoutePlan b,String label){
        check(a.closed==b.closed,label+" closed");check(a.points.size()==b.points.size(),label+" vertex count");
        for(int i=0;i<a.points.size();i++){
            near(a.points.get(i).latitude,b.points.get(i).latitude,0,label+" latitude order");
            near(a.points.get(i).longitude,b.points.get(i).longitude,0,label+" longitude order");
        }
        near(a.minSpeedMps,b.minSpeedMps,1e-12,label+" minimum speed");
        near(a.maxSpeedMps,b.maxSpeedMps,1e-12,label+" maximum speed");
        near(a.distanceLimitMeters,b.distanceLimitMeters,0,label+" distance limit");
        check(a.durationLimitMillis==b.durationLimitMillis,label+" time limit");
    }

    private static void fiveModesAndUnits(){
        RouteEditor draft=editor();RoutePlan open=draft.plan();
        check(!open.closed,"open speed mode");limits(open,0,0,"open speed mode");
        near(open.minSpeedMps,1,1e-15,"km/h minimum converted to m/s");
        near(open.maxSpeedMps,2,1e-15,"km/h maximum converted to m/s");
        draft.openByDuration=true;RoutePlan openTimed=draft.plan();
        limits(openTimed,600000,0,"open duration mode");
        near(openTimed.minSpeedMps,open.lengthMeters/600,1e-15,"open duration speed L/T");
        near(openTimed.maxSpeedMps,openTimed.minSpeedMps,0,"open duration uniform speed");

        draft.closed=true;draft.openByDuration=false;
        RoutePlan loopDistance=draft.plan();
        check(loopDistance.closed,"closed distance mode");limits(loopDistance,0,1200,"closed distance mode");
        near(loopDistance.minSpeedMps,1,1e-15,"closed distance keeps speed range min");
        near(loopDistance.maxSpeedMps,2,1e-15,"closed distance keeps speed range max");
        check(loopDistance.lengthMeters>open.lengthMeters,"closed route adds return leg");

        draft.loopByDuration=true;RoutePlan loopTime=draft.plan();
        limits(loopTime,600000,0,"closed active-time mode");
        near(loopTime.minSpeedMps,1,1e-15,"closed active time keeps min");
        near(loopTime.maxSpeedMps,2,1e-15,"closed active time keeps max");

        draft.loopByDuration=false;draft.loopDistanceByDuration=true;
        RoutePlan distanceTime=draft.plan();limits(distanceTime,0,1200,"closed distance plus duration mode");
        near(distanceTime.minSpeedMps,2,0,"D/T uses meters and active seconds");
        near(distanceTime.maxSpeedMps,2,0,"D/T uniform speed");
        check(distanceTime.distanceLimitMeters>distanceTime.lengthMeters,"D/T can cover multiple laps");
        draft.loopByDuration=true;
        samePlan(distanceTime,draft.plan(),"D/T wins over stale loop-time flag");

        draft=editor();draft.durationMinutes=1.234567;draft.openByDuration=true;
        RoutePlan rounded=draft.plan();long millis=Math.round(1.234567*60000);
        check(rounded.durationLimitMillis==millis,"minutes round once to milliseconds");
        near(rounded.minSpeedMps,rounded.lengthMeters/(millis/1000.0),1e-15,"derived speed uses rounded exact duration");
    }

    private static void editingAndCopies(){
        RouteEditor draft=editor();List<SimulationEngine.Point> snapshot=draft.points();
        RoutePlan plan=draft.plan();
        draft.undo();check(draft.size()==2,"undo removes final vertex");
        check(snapshot.size()==3&&plan.points.size()==3,"snapshots and plans do not alias mutable draft");
        draft.add(.002,.003);
        near(draft.points().get(0).longitude,0,0,"first vertex retained");
        near(draft.points().get(1).longitude,.001,0,"second vertex retained");
        near(draft.points().get(2).latitude,.002,0,"replacement final vertex order");
        boolean immutable=false;try{snapshot.clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"published point snapshots are unmodifiable");
        draft.clear();check(draft.size()==0,"clear removes all vertices");draft.undo();draft.clear();
        check(draft.size()==0,"empty undo and repeated clear harmless");
        check(plan.points.size()==3&&snapshot.size()==3,"clear cannot alter built plan or previous snapshot");
        draft.closed=true;draft.minKmh=7;draft.maxKmh=8;draft.durationMinutes=12;draft.distanceMeters=1400;
        draft.clear();check(draft.closed&&draft.minKmh==7&&draft.durationMinutes==12&&draft.distanceMeters==1400,"clear preserves settings");

        RouteEditor full=new RouteEditor();
        for(int i=0;i<RouteEditor.MAX_POINTS;i++)full.add(0,i*.001);
        check(full.size()==200,"200 draft vertices accepted");
        invalid(()->full.add(0,.201),"201st draft vertex rejected");check(full.size()==200,"limit rejection leaves draft intact");
        RouteEditor adjacent=new RouteEditor();adjacent.add(0,180);
        invalid(()->adjacent.add(0,-180),"equivalent date-line adjacent point rejected");
        check(adjacent.size()==1,"duplicate rejection does not insert a point");
        for(double[] coordinates:new double[][]{{Double.NaN,0},{91,0},{0,181},{0,Double.POSITIVE_INFINITY}})
            invalid(()->adjacent.add(coordinates[0],coordinates[1]),"invalid coordinates rejected");
        RouteEditor acrossDateLine=new RouteEditor();acrossDateLine.add(0,179.999);acrossDateLine.add(0,-179.999);
        near(acrossDateLine.length(false),222.390160467,.00001,"draft length uses short date-line route");
    }

    private static void restoreWithoutMutatingPlanOrMotion(){
        RouteEditor source=editor();source.closed=true;source.loopDistanceByDuration=true;
        RoutePlan running=source.plan();MotionSession motion=new MotionSession(running,0,5);
        MotionSession.Sample before=motion.update(1000);
        RouteEditor restored=new RouteEditor();restored.loopDistanceByDuration=true;restored.showPlan(running);
        check(restored.closed&&restored.loopDistanceByDuration&&!restored.loopByDuration,"D/T UI choice restored from retained draft mode");
        near(restored.durationMinutes,10,1e-12,"D/T restores inferred total minutes");
        near(restored.distanceMeters,1200,0,"D/T restores target cumulative distance");
        samePlan(running,restored.plan(),"D/T plan round trip");
        restored.undo();check(running.points.size()==3,"restored draft editing cannot change running plan");
        MotionSession.Sample after=motion.update(2000);
        near(before.travelledMeters,2,1e-12,"sample preceding restoration immutable");
        near(after.travelledMeters,4,1e-12,"editor restoration does not reset active motion");
        check(after.activeMillis==2000,"editor restoration does not reset active clock");

        source=editor();source.closed=true;source.loopByDuration=true;
        RoutePlan timed=source.plan();restored.showPlan(timed);
        check(restored.closed&&restored.loopByDuration&&!restored.loopDistanceByDuration,"closed time mode restores without stale D/T");
        samePlan(timed,restored.plan(),"closed time plan round trip");
        source=editor();source.openByDuration=true;RoutePlan openTimed=source.plan();restored.showPlan(openTimed);
        check(!restored.closed&&restored.openByDuration&&!restored.loopDistanceByDuration,"open time mode restored");
        samePlan(openTimed,restored.plan(),"open time plan round trip");
        source=editor();source.closed=true;RoutePlan distance=source.plan();restored.showPlan(distance);
        check(restored.closed&&!restored.loopByDuration&&!restored.loopDistanceByDuration,"ordinary closed distance stays range mode");
        samePlan(distance,restored.plan(),"closed range plan round trip");
        source=editor();RoutePlan open=source.plan();restored.showPlan(open);
        check(!restored.closed&&!restored.openByDuration,"ordinary open speed mode restored");
        samePlan(open,restored.plan(),"open range plan round trip");
    }

    private static void invalidSettings(){
        for(double[] speeds:new double[][]{{0,1},{-1,1},{4,3},{3,361},{Double.NaN,5},{3,Double.POSITIVE_INFINITY}}){
            RouteEditor draft=editor();draft.minKmh=speeds[0];draft.maxKmh=speeds[1];
            invalid(draft::plan,"invalid speed units/range");
        }
        RouteEditor maximum=editor();maximum.minKmh=maximum.maxKmh=360;
        near(maximum.plan().minSpeedMps,100,0,"360km/h converts to supported 100m/s boundary");
        for(double minutes:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,1e-8,Long.MAX_VALUE/60000.0}){
            RouteEditor draft=editor();draft.durationMinutes=minutes;invalid(draft::plan,"invalid duration value or resolution");
        }
        for(double distance:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}){
            RouteEditor draft=editor();draft.distanceMeters=distance;invalid(draft::plan,"invalid distance value");
        }
        RouteEditor empty=new RouteEditor();invalid(empty::plan,"open empty draft cannot start");
        empty.add(0,0);invalid(empty::plan,"open single vertex cannot start");
        empty.add(0,.001);empty.closed=true;invalid(empty::plan,"closed two vertices cannot start");
        empty.add(0,0);invalid(empty::plan,"closed three entries but only two distinct points cannot start");
        RouteEditor tiny=new RouteEditor();tiny.add(0,0);tiny.add(0,.000001);invalid(tiny::plan,"sub-meter draft cannot start");
        RouteEditor fast=editor();fast.closed=true;fast.loopDistanceByDuration=true;fast.durationMinutes=1.0/60;
        invalid(fast::plan,"D/T rejects automatic speed above 100m/s");
        RouteEditor fastOpen=new RouteEditor();fastOpen.add(0,0);fastOpen.add(0,1);fastOpen.openByDuration=true;fastOpen.durationMinutes=1.0/60;
        invalid(fastOpen::plan,"open duration rejects automatic speed above 100m/s");
    }

    public static void main(String[] args){
        fiveModesAndUnits();editingAndCopies();restoreWithoutMutatingPlanOrMotion();invalidSettings();
        System.out.println("PASS: "+checks+" route-editor mode, unit, restoration and draft assertions");
    }
}
