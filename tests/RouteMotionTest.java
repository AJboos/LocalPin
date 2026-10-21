package local.pin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure JVM behavioral tests using real route/session production classes. */
public final class RouteMotionTest {
    private static int assertions;
    private static SimulationEngine.Point p(double lat,double lon){return new SimulationEngine.Point(lat,lon);}
    private static List<SimulationEngine.Point> line(){return Arrays.asList(p(0,0),p(0,1));}
    private static List<SimulationEngine.Point> triangle(){return Arrays.asList(p(0,0),p(0,.001),p(.001,.001));}
    private static RoutePlan route(List<SimulationEngine.Point> points,boolean closed,double min,double max,long time,double distance){return new RoutePlan(points,closed,min,max,time,distance);}
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static void near(double actual,double expected,double tolerance,String label){check(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,label+": "+actual+" != "+expected);}
    private static void rejected(Runnable task,String label){boolean rejected=false;try{task.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,label);}
    private static void pointNear(SimulationEngine.Point a,SimulationEngine.Point b,double meters,String label){near(RoutePlan.distance(a,b),0,meters,label);}

    private static void geometryAndValidation() {
        RoutePlan open=route(line(),false,1,5,0,0);
        near(open.lengthMeters,111195.0802335329,.000001,"independent one-degree equator length");
        pointNear(open.pointAt(open.lengthMeters/2),p(0,.5),.000001,"equator halfway");
        pointNear(open.pointAt(open.lengthMeters+100),p(0,1),0,"open progress clamps to endpoint");
        near(open.bearingAt(0),90,.000001,"eastbound initial course");
        near(open.bearingAt(open.lengthMeters),90,.000001,"eastbound final course");

        RoutePlan dateline=route(Arrays.asList(p(0,179.999),p(0,-179.999)),false,1,2,0,0);
        near(dateline.lengthMeters,222.390160467,.00001,"date line shortest distance");
        SimulationEngine.Point middle=dateline.pointAt(dateline.lengthMeters/2);
        check(Math.abs(middle.longitude)>179.99,"short arc stays at date line, not Greenwich");
        near(middle.latitude,0,1e-10,"date line latitude");
        near(RoutePlan.distance(p(0,179.999),middle),dateline.lengthMeters/2,.00001,"date line half length");
        near(dateline.bearingAt(dateline.lengthMeters/2),90,1e-7,"date line eastbound course");

        RoutePlan polar=route(Arrays.asList(p(80,-90),p(80,90)),false,1,1,0,0);
        check(polar.pointAt(polar.lengthMeters/2).latitude>89.999,"great circle passes near pole");
        check(Double.isFinite(polar.bearingAt(polar.lengthMeters/2)),"polar bearing finite");
        RoutePlan antipodal=route(Arrays.asList(p(0,0),p(0,180)),false,1,1,0,0);
        check(MapMath.valid(antipodal.pointAt(antipodal.lengthMeters/2).latitude,
                antipodal.pointAt(antipodal.lengthMeters/2).longitude),"deterministic antipodal arc finite");

        ArrayList<SimulationEngine.Point> editable=new ArrayList<>(triangle());
        RoutePlan loop=route(editable,true,2,2,0,0);
        editable.clear();
        check(loop.points.size()==3,"route defensively owns original vertices");
        boolean immutable=false;try{loop.points.clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"published vertices unmodifiable");
        RoutePlan alreadyClosed=route(Arrays.asList(p(0,0),p(0,.001),p(.001,.001),p(0,0)),true,2,2,0,0);
        check(alreadyClosed.points.size()==3,"explicit return to first normalized away");
        near(loop.lengthMeters,alreadyClosed.lengthMeters,0,"one implicit closing segment");
        near(loop.lengthMeters,RoutePlan.distance(loop.points.get(0),loop.points.get(1))
                +RoutePlan.distance(loop.points.get(1),loop.points.get(2))
                +RoutePlan.distance(loop.points.get(2),loop.points.get(0)),1e-10,"closed length includes return leg");
        pointNear(loop.pointAt(loop.lengthMeters),loop.points.get(0),0,"one full lap returns to start");
        pointNear(loop.pointAt(loop.lengthMeters*3+12),loop.pointAt(12),1e-7,"cumulative multiple laps wrap");

        rejected(()->route(null,false,1,2,0,0),"null route");
        rejected(()->route(Collections.emptyList(),false,1,2,0,0),"empty route");
        rejected(()->route(Collections.singletonList(p(0,0)),false,1,2,0,0),"one vertex");
        ArrayList<SimulationEngine.Point> excess=new ArrayList<>();for(int i=0;i<201;i++)excess.add(p(0,i*.001));
        rejected(()->route(excess,false,1,2,0,0),"201 vertices rejected");
        check(route(excess.subList(0,200),false,1,2,0,0).points.size()==200,"200 vertices accepted");
        rejected(()->route(Arrays.asList(p(0,0),null),false,1,2,0,0),"null vertex");
        rejected(()->route(Arrays.asList(p(0,0),p(0,0)),false,1,2,0,0),"adjacent duplicate");
        rejected(()->route(Arrays.asList(p(0,180),p(0,-180)),false,1,2,0,0),"equivalent date line duplicate");
        rejected(()->route(Arrays.asList(p(0,0),p(0,.000001)),false,1,2,0,0),"route shorter than one meter");
        rejected(()->route(line(),true,1,2,0,0),"closed needs three different vertices");
        rejected(()->route(Arrays.asList(p(0,0),p(0,.001),p(0,0)),true,1,2,0,0),"closed two unique vertices");
        for(double[] range:new double[][]{{0,1},{-1,1},{3,2},{1,101},{Double.NaN,2},{1,Double.POSITIVE_INFINITY}})
            rejected(()->route(line(),false,range[0],range[1],0,0),"invalid speed range");
        check(route(line(),false,100,100,0,0).maxSpeedMps==100,"100m/s upper bound accepted");
        rejected(()->route(line(),false,1,2,-1,0),"negative time");
        for(double limit:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})
            rejected(()->route(line(),false,1,2,0,limit),"invalid distance");
        rejected(()->route(line(),false,1,2,1,1),"mutually exclusive limits");
        rejected(()->route(line(),false,1,2,0,open.lengthMeters+1),"open distance cannot exceed route");
        rejected(()->open.pointAt(-1),"negative progress");
        rejected(()->open.bearingAt(Double.NaN),"nonfinite progress");
    }

    private static void analyticSpeedAndCadence() {
        RoutePlan variable=route(line(),false,1,9,0,0);
        MotionSession motion=new MotionSession(variable,0,12345);
        MotionSession.Sample start=motion.update(0),half=motion.update(2500),node=motion.update(5000);
        near(half.speedMps,(start.speedMps+node.speedMps)/2,1e-12,"linear node interpolation");
        near(half.travelledMeters,(start.speedMps+half.speedMps)*1.25,1e-12,"half-node trapezoid integral");
        near(node.travelledMeters,(start.speedMps+node.speedMps)*2.5,1e-12,"whole-node trapezoid integral");
        MotionSession.Sample next=motion.update(10000);
        near(next.travelledMeters,node.travelledMeters+(node.speedMps+next.speedMps)*2.5,1e-11,"next node integral");
        check(start.travelledMeters==0&&start.activeMillis==0,"old sample immutable");

        MotionSession fine=new MotionSession(variable,0,77),coarse=new MotionSession(variable,0,77),uneven=new MotionSession(variable,0,77);
        MotionSession.Sample a=null,b=null,c=null;
        for(int t=100;t<=60000;t+=100){a=fine.update(t);check(a.speedMps>=1&&a.speedMps<=9,"speed in configured range");}
        for(int t=5000;t<=60000;t+=5000)b=coarse.update(t);
        for(int t=733;t<60000;t+=733)c=uneven.update(t);c=uneven.update(60000);
        near(a.travelledMeters,b.travelledMeters,0,"100ms and 5000ms cadence exact integral equality");
        near(a.travelledMeters,c.travelledMeters,0,"irregular cadence exact integral equality");
        near(a.speedMps,b.speedMps,0,"cadence independent speed");
        pointNear(a.point,b.point,0,"cadence independent position");
        near(a.point.speedMps,a.speedMps,0,"point carries actual speed");
        near(a.point.bearingDegrees,a.bearingDegrees,0,"point carries course");
        MotionSession other=new MotionSession(variable,0,78);
        check(other.update(0).speedMps!=new MotionSession(variable,0,77).update(0).speedMps,"different seeds yield different profiles");
        RoutePlan fixed=route(line(),false,4,4,0,0);
        MotionSession constant=new MotionSession(fixed,0,1);
        near(constant.update(4321).travelledMeters,17.284,1e-12,"fixed speed exact physical distance");
    }

    private static void limitsAndLaps() {
        MotionSession timed=new MotionSession(route(line(),false,4,4,6250,0),0,1);
        timed.update(5000);MotionSession.Sample end=timed.update(10000);
        check(end.completed&&end.activeMillis==6250,"duration truncates active time exactly");
        near(end.travelledMeters,25,1e-12,"duration stops at exact integrated distance");
        near(end.speedMps,0,0,"completed speed zero");
        near(timed.update(100000).travelledMeters,25,0,"completed route never advances or auto pauses");

        MotionSession limited=new MotionSession(route(line(),false,4,4,0,33.333),0,1);
        limited.update(5000);end=limited.update(10000);
        near(end.travelledMeters,33.333,0,"distance truncated exactly without overshoot");
        check(end.completed&&end.activeMillis==8333,"distance crossing time rounded to nearest millisecond");
        near(RoutePlan.distance(p(0,0),end.point),33.333,.000001,"distance stop point follows route");

        RoutePlan shortOpen=route(Arrays.asList(p(0,0),p(0,.0001)),false,10,10,0,0);
        MotionSession open=new MotionSession(shortOpen,0,1);end=open.update(5000);
        check(end.completed,"open route automatically stops at endpoint");
        near(end.travelledMeters,shortOpen.lengthMeters,0,"open route endpoint distance exact");
        pointNear(end.point,shortOpen.points.get(1),0,"open endpoint coordinate exact");

        RoutePlan base=route(triangle(),true,10,10,0,0);
        RoutePlan limit=route(triangle(),true,10,10,0,base.lengthMeters*2.25);
        MotionSession laps=new MotionSession(limit,0,2);end=laps.update(0);
        for(int t=5000;!end.completed&&t<200000;t+=5000)end=laps.update(t);
        check(end.completed,"closed cumulative distance completes after multiple laps");
        near(end.travelledMeters,base.lengthMeters*2.25,0,"closed distance is cumulative, not modulo");
        pointNear(end.point,base.pointAt(base.lengthMeters*.25),.000001,"closed distance wraps only geometry");
        MotionSession manual=new MotionSession(base,0,1);
        for(int t=5000;t<=200000;t+=5000)end=manual.update(t);
        check(!end.completed&&end.travelledMeters>base.lengthMeters*4,"unlimited closed route continues multiple laps");
        MotionSession loopTime=new MotionSession(route(triangle(),true,10,10,7500,0),0,1);
        loopTime.update(5000);end=loopTime.update(10000);
        check(end.completed&&end.activeMillis==7500,"closed active time limit exact");
        near(end.travelledMeters,75,0,"closed time limit integrated distance");
    }

    private static void pauseGapAndResume() {
        RoutePlan plan=route(line(),false,4,4,10000,0);
        MotionSession motion=new MotionSession(plan,1000,0);
        MotionSession.Sample before=motion.update(2000),pause=motion.pause(2500);
        near(pause.travelledMeters,6,0,"pause integrates elapsed running time");
        check(pause.paused&&!pause.autoPaused&&!pause.completed,"explicit pause flags");
        near(pause.speedMps,0,0,"pause speed zero");
        MotionSession.Sample still=motion.update(100000);
        near(still.travelledMeters,pause.travelledMeters,0,"paused progress frozen over long wait");
        check(still.activeMillis==1500,"pause excludes wall time from active duration");
        pointNear(still.point,pause.point,0,"paused fix stays in place");
        MotionSession.Sample resumed=motion.resume(200000);
        check(!resumed.paused&&!resumed.autoPaused&&resumed.speedMps==4,"explicit resume restarts motion");
        still=motion.update(200500);
        near(still.travelledMeters,8,0,"resume resets timebase without a jump");
        check(before.travelledMeters==4&&before.activeMillis==1000,"previous active sample unchanged");

        MotionSession gap=new MotionSession(route(line(),false,4,4,20000,0),0,0);
        MotionSession.Sample sample=gap.update(5000);
        check(!sample.paused&&sample.activeMillis==5000,"exact 5s scheduling delay permitted");
        sample=gap.update(10001);
        check(!sample.paused&&!sample.autoPaused&&!sample.completed,"gap over 5s keeps motion enabled");
        near(sample.travelledMeters,40,0,"5001ms gap advances at most five seconds of movement");
        check(sample.activeMillis==10000,"5001ms gap counts only the bounded five-second step");
        near(sample.point.speedMps,4,0,"gap retains configured moving speed without artificial pause");
        sample=gap.update(10201);
        near(sample.travelledMeters,40.8,1e-12,"next normal tick continues without manual resume");
        check(sample.activeMillis==10200&&!sample.paused&&!sample.autoPaused,"normal update counts only its own interval");
        sample=gap.update(200000);
        check(sample.activeMillis==15200&&!sample.completed&&!sample.paused,"another long gap counts five seconds without latching pause");
        near(sample.travelledMeters,60.8,1e-12,"repeated long gap has bounded movement instead of freezing");
        sample=gap.update(204800);
        check(sample.completed&&sample.activeMillis==20000,"duration finishes after enough bounded active time");
        near(sample.travelledMeters,80,0,"duration excludes background time beyond each five-second cap");
        sample=gap.update(500000);
        check(sample.completed&&!sample.paused&&!sample.autoPaused&&sample.activeMillis==20000,"completed state unchanged by later interruption");
        near(sample.point.speedMps,0,0,"completed point remains stationary after long gap");
        rejected(()->gap.update(499999),"backwards monotonic clock rejected");
        rejected(()->new MotionSession(null,0,0),"missing plan rejected");
        rejected(()->new MotionSession(plan,-1,0),"negative clock rejected");

        MotionSession shortGap=new MotionSession(plan,1000,0);
        sample=shortGap.update(2200);
        check(sample.activeMillis==1200&&!sample.paused&&!sample.autoPaused,"ordinary short background delay integrates normally");
        near(sample.travelledMeters,4.8,1e-12,"short background delay follows physical elapsed time");

        MotionSession sparse=new MotionSession(route(line(),false,4,4,0,0),0,0);
        for(int tick=1;tick<=5;tick++){
            sample=sparse.update(tick*8000L);
            check(!sample.paused&&!sample.autoPaused&&!sample.completed,"repeated 8s delivery never needs manual recovery");
            check(sample.activeMillis==tick*5000L,"each sparse delivery contributes at most five seconds");
            near(sample.travelledMeters,tick*20,1e-12,"repeated sparse delivery continues making bounded progress");
        }

        MotionSession distanceGap=new MotionSession(route(line(),false,4,4,0,25),0,0);
        distanceGap.update(5000);sample=distanceGap.update(10001);
        check(sample.completed&&!sample.paused&&sample.activeMillis==6250,"distance limit may complete within capped background step");
        near(sample.travelledMeters,25,0,"capped step truncates to distance limit without overshoot");
        sample=distanceGap.update(10201);
        near(sample.travelledMeters,25,0,"completed distance-limited route remains at endpoint");
        sample=distanceGap.update(12401);
        check(sample.completed&&sample.activeMillis==6250,"distance completion consumes only the required part of capped time");
        near(sample.travelledMeters,25,0,"distance after a gap still truncates exactly");

        RoutePlan varying=route(line(),false,1,9,0,0);
        MotionSession background=new MotionSession(varying,0,77),control=new MotionSession(varying,0,77);
        background.update(1000);background.update(20000);sample=background.update(20500);
        control.update(5000);MotionSession.Sample expected=control.update(6500);
        near(sample.travelledMeters,expected.travelledMeters,0,"random-speed integration counts only capped background time");
        near(sample.speedMps,expected.speedMps,0,"random-speed phase follows bounded active time");
        pointNear(sample.point,expected.point,0,"random-speed point matches equivalent uninterrupted active time");
        sample=background.pause(100000);
        check(sample.paused&&!sample.autoPaused&&sample.activeMillis==11500,"manual pause integrates its capped arrival step then persists");
        MotionSession.Sample explicitPause=sample;
        sample=background.update(200000);
        near(sample.travelledMeters,explicitPause.travelledMeters,0,"manual pause does not auto-resume on subsequent updates");
        check(sample.paused&&sample.activeMillis==11500,"manual pause excludes long wait from duration");
    }

    public static void main(String[] args) {
        geometryAndValidation();analyticSpeedAndCadence();limitsAndLaps();pauseGapAndResume();
        System.out.println("PASS: "+assertions+" route geometry, speed integration, limit and pause assertions");
    }
}
