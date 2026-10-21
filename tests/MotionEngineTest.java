package local.pin;

import java.util.Arrays;

/** Motion lifecycle against a fake platform; not an Android compatibility test. */
public final class MotionEngineTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(double actual,double expected,double tolerance,String message){check(Math.abs(actual-expected)<=tolerance,message+": "+actual);}
    private static final class Clock implements SimulationEngine.Clock {
        long time=1000;
        public long utcMillis(){return 1700000000000L+time;}
        public long elapsedMillis(){return time;}
        public long elapsedNanos(){return time*1000000L;}
    }
    private static final class Queue implements SimulationEngine.Scheduler {
        Runnable pending;long delay;
        public void post(Runnable task,long millis){check(pending==null,"only one refresh scheduled");pending=task;delay=millis;}
        public void remove(Runnable task){if(pending==task)pending=null;}
        void fire(Clock clock){Runnable next=pending;pending=null;clock.time+=delay;if(next!=null)next.run();}
    }
    private static final class Backend implements SimulationEngine.Backend {
        int opens,writes,closes,listens;boolean fail,allocated;
        SimulationEngine.Point point;
        public void open(){opens++;allocated=true;}
        public void write(SimulationEngine.Point value,long utc,long nanos){if(fail)throw new SecurityException("test");point=value;writes++;}
        public void listen(){listens++;}
        public boolean close(){closes++;allocated=false;return true;}
    }
    private static RoutePlan plan(double targetDistance){return new RoutePlan(Arrays.asList(
            new SimulationEngine.Point(0,0),new SimulationEngine.Point(0,.001)),false,5,5,0,targetDistance);}
    public static void main(String[] args){
        Clock clock=new Clock();Queue queue=new Queue();Backend backend=new Backend();
        SimulationEngine engine=new SimulationEngine(backend,clock,queue,s->{});
        RoutePlan plan=plan(20);
        engine.applyRoute(plan,200);
        check(engine.state().running&&engine.state().routePlan==plan,"route begins without a static target");
        check(backend.opens==1&&backend.listens==1&&backend.writes==1,"first route fix before updates");
        near(engine.state().motion.travelledMeters,0,1e-7,"start progress zero");
        queue.fire(clock);
        near(engine.state().motion.travelledMeters,1,1e-6,"route advances by elapsed time");
        check(backend.point.longitude>0&&Double.isFinite(backend.point.speedMps),"backend receives moving fix metadata");
        near(backend.point.speedMps,5,1e-7,"reported speed is route speed");
        MotionSession.Sample old=engine.state().motion;
        engine.pauseMotion();
        check(engine.state().running&&engine.state().motion.paused,"pause keeps providers running");
        near(backend.point.speedMps,0,0,"paused fix has zero speed");
        int before=backend.writes;
        for(int i=0;i<10;i++)queue.fire(clock);
        near(engine.state().motion.travelledMeters,old.travelledMeters,1e-8,"paused position frozen");
        check(engine.state().motion.activeMillis==old.activeMillis,"pause excluded from active time");
        check(backend.writes==before+10,"paused location still refreshes");
        engine.resumeMotion();queue.fire(clock);
        near(engine.state().motion.travelledMeters,2,1e-6,"resume has no catchup jump");
        engine.reconfigureInterval(500);
        check(queue.delay==500&&engine.state().routePlan==plan,"changing refresh does not replace route");
        double distance=engine.state().motion.travelledMeters;
        queue.fire(clock);
        near(engine.state().motion.travelledMeters,distance+2.5,1e-6,"new refresh interval uses elapsed time");
        distance=engine.state().motion.travelledMeters;
        clock.time+=9000;queue.fire(clock);
        check(engine.state().motion.autoPaused&&engine.state().motion.paused,"long gap pauses motion");
        near(engine.state().motion.travelledMeters,distance,1e-8,"long gap does not fast forward");
        check(engine.state().recoveredPauseMillis>=9000,"interruption remains visible");
        engine.resumeMotion();
        for(int i=0;i<30&&!engine.state().motion.completed;i++)queue.fire(clock);
        check(engine.state().motion.completed&&engine.state().running&&backend.allocated,"completion holds final fix");
        near(engine.state().motion.travelledMeters,20,1e-6,"distance end is exact");
        near(backend.point.speedMps,0,0,"completed fix is stationary");
        long active=engine.state().motion.activeMillis;before=backend.writes;
        queue.fire(clock);
        check(backend.writes==before+1&&engine.state().motion.activeMillis==active,"completed fix refreshes without further progress");
        SimulationEngine.Point fixed=new SimulationEngine.Point(1,2);engine.apply(fixed,200);
        check(engine.state().motion==null&&engine.state().routePlan==null&&backend.point==fixed,"static mode clears route state");
        check(Double.isNaN(fixed.speedMps)&&Double.isNaN(fixed.bearingDegrees),"legacy static payload omits new metadata");
        check(backend.opens==1&&backend.listens==1,"mode switching reuses one provider session");
        engine.applyRoute(plan,200);Runnable stale=queue.pending;engine.stop();before=backend.writes;
        stale.run();check(backend.writes==before&&!backend.allocated&&queue.pending==null,"stop wins over stale route refresh");
        engine.applyRoute(plan,200);backend.fail=true;queue.fire(clock);
        check(!engine.state().running&&!backend.allocated&&queue.pending==null,"route write failure cleans up");
        check("SecurityException".equals(engine.state().error),"route failure reported");
        before=backend.writes;engine.pauseMotion();engine.resumeMotion();engine.reconfigureInterval(1000);
        check(backend.writes==before&&queue.pending==null,"idle controls cannot revive failed session");
        System.out.println("PASS: "+checks+" route-engine lifecycle assertions");
    }
}
