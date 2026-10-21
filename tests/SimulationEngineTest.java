package local.pin;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fault injection and lifecycle tests; these do not substitute for Android device tests. */
public final class SimulationEngineTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final class Clock implements SimulationEngine.Clock {
        long now=1000;
        public long utcMillis(){return 1700000000000L+now;}
        public long elapsedMillis(){return now;}
        public long elapsedNanos(){return now*1000000L;}
    }
    private static final class Queue implements SimulationEngine.Scheduler {
        Runnable pending;long delay;boolean reject;
        public void post(Runnable task,long delay){
            if(reject)throw new IllegalStateException("scheduler unavailable");
            if(pending!=null)throw new AssertionError("duplicate timer");
            pending=task;this.delay=delay;
        }
        public void remove(Runnable task){if(pending==task)pending=null;}
        void fire(Clock clock){Runnable task=pending;pending=null;clock.now+=delay;if(task!=null)task.run();}
    }
    private static class Backend implements SimulationEngine.Backend {
        final List<String> events=new ArrayList<>();
        int opens,writes,listens,closes;String fail="";boolean clean=true,allocated;
        SimulationEngine.Point point;long wall,nanos;
        public void open(){opens++;events.add("open");allocated=true;maybe("open");}
        public void write(SimulationEngine.Point point,long wall,long nanos){
            maybe("write");writes++;events.add("write");this.point=point;this.wall=wall;this.nanos=nanos;
        }
        public void listen(){listens++;events.add("listen");maybe("listen");}
        public boolean close(){closes++;events.add("close");allocated=false;maybe("close");return clean;}
        void maybe(String stage){if(fail.equals(stage))throw new SecurityException("injected failure");}
    }
    private static final class Fixture {
        final Clock clock=new Clock();final Queue queue=new Queue();final Backend backend;
        final List<SimulationEngine.State> observed=new ArrayList<>();
        final SimulationEngine engine;
        Fixture(){this(new Backend());}
        Fixture(Backend backend){this.backend=backend;engine=new SimulationEngine(backend,clock,queue,observed::add);}
        void start(){engine.apply(new SimulationEngine.Point(41,120),200);}
    }
    public static void main(String[] args)throws Exception {
        Fixture f=new Fixture();
        check(!f.engine.state().running,"starts idle");f.start();
        check(f.backend.events.equals(Arrays.asList("open","write","listen")),"initial fix precedes subscriptions");
        check(f.engine.state().running&&f.backend.allocated,"resources active");
        check(f.queue.delay==200&&f.queue.pending!=null,"default refresh timer");
        check(f.backend.wall==f.clock.utcMillis()&&f.backend.nanos==f.clock.elapsedNanos(),"timestamps reach platform");
        SimulationEngine.State old=f.engine.state();
        f.queue.fire(f.clock);
        check(f.engine.state().writes==2&&f.backend.writes==2,"periodic submission");
        check(old.writes==1&&old.point.latitude==41,"published snapshot does not mutate");
        SimulationEngine.Point next=new SimulationEngine.Point(42,121);f.engine.apply(next,500);
        check(f.backend.opens==1&&f.backend.listens==1,"point change reuses providers and listeners");
        check(f.backend.point==next&&f.backend.point.longitude==121,"point change uses complete pair immediately");
        check(f.queue.delay==500&&f.engine.state().intervalMillis==500,"changed rate replaces timer");
        f.clock.now+=10000;f.queue.fire(f.clock);
        check(f.engine.state().recoveredPauseMillis==10500,"long update gap is exposed");
        Runnable stale=f.queue.pending;int writes=f.backend.writes;f.engine.stop();
        check(!f.engine.state().running&&f.queue.pending==null&&!f.backend.allocated,"stop cancels work and releases resources");
        stale.run();check(f.backend.writes==writes,"already-dequeued callback cannot write after stop");
        f.engine.stop();check(f.backend.closes==1,"repeated stop is safe");
        f.start();check(f.backend.opens==2&&f.engine.state().writes==1,"explicit restart opens fresh session");
        f.engine.stop();

        for(int interval:new int[]{200,500,1000}){
            Fixture rate=new Fixture();rate.engine.apply(new SimulationEngine.Point(0,0),interval);
            for(int i=0;i<20;i++)rate.queue.fire(rate.clock);
            check(rate.backend.writes==21&&rate.clock.now==1000+20L*interval,"continuous cadence "+interval);
            rate.engine.stop();
        }
        for(int interval:new int[]{-1,0,1,100,201,Integer.MAX_VALUE})
            check(SimulationEngine.validInterval(interval)==200,"invalid interval falls back safely");
        for(double[] point:new double[][]{{Double.NaN,0},{0,Double.POSITIVE_INFINITY},{91,0},{0,181}}){
            boolean rejected=false;try{new SimulationEngine.Point(point[0],point[1]);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid destination rejected before platform access");
        }
        for(String stage:new String[]{"open","write","listen"}){
            Fixture failure=new Fixture();failure.backend.fail=stage;failure.start();
            check(!failure.engine.state().running&&failure.queue.pending==null,"no continued run after "+stage+" failure");
            check(failure.backend.closes==1&&!failure.backend.allocated,"partial resources cleaned after "+stage+" failure");
            check(failure.engine.state().error.equals("SecurityException"),"error exposed after "+stage+" failure");
        }
        Fixture failure=new Fixture();failure.start();failure.backend.fail="write";failure.queue.fire(failure.clock);
        check(failure.engine.state().writes==1&&!failure.engine.state().running,"failed write is not counted as success");
        check(failure.backend.closes==1&&failure.queue.pending==null,"runtime failure stops session");
        Fixture rejected=new Fixture();rejected.queue.reject=true;rejected.start();
        check(!rejected.engine.state().running&&!rejected.backend.allocated,"scheduler failure releases platform resources");
        Fixture dirty=new Fixture();dirty.start();dirty.backend.clean=false;dirty.engine.stop();
        check(!dirty.engine.state().cleanupSucceeded,"incomplete platform cleanup is reported");
        Fixture closeFailure=new Fixture();closeFailure.start();closeFailure.backend.fail="close";closeFailure.engine.stop();
        check(!closeFailure.engine.state().cleanupSucceeded&&!closeFailure.engine.state().running,"cleanup exception cannot keep engine running");

        Fixture repeated=new Fixture();repeated.start();
        for(int i=0;i<200;i++)repeated.engine.apply(new SimulationEngine.Point(41+i/100000d,120-i/100000d),200);
        check(repeated.backend.opens==1&&repeated.backend.listens==1,"rapid map taps do not leak subscriptions");
        check(repeated.backend.writes==201&&repeated.queue.pending!=null,"rapid map taps retain one update timer");
        repeated.engine.stop();

        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),stopping=new CountDownLatch(1);
        Backend blocking=new Backend(){
            @Override public void write(SimulationEngine.Point point,long wall,long nanos){
                entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("blocked writer timeout");}
                catch(InterruptedException failure){throw new AssertionError(failure);}
                super.write(point,wall,nanos);
            }
        };
        Fixture concurrent=new Fixture(blocking);
        Thread writer=new Thread(concurrent::start),stopper=new Thread(()->{stopping.countDown();concurrent.engine.stop();});
        writer.setDaemon(true);stopper.setDaemon(true);writer.start();
        check(entered.await(2,TimeUnit.SECONDS),"concurrent write entered");stopper.start();
        check(stopping.await(2,TimeUnit.SECONDS)&&blocking.closes==0,"stop does not close providers during a write");
        release.countDown();writer.join(2000);stopper.join(2000);
        check(!writer.isAlive()&&!stopper.isAlive(),"stop and writer finish");
        check(!concurrent.engine.state().running&&!blocking.allocated&&concurrent.queue.pending==null,"concurrent stop wins over refresh scheduling");
        System.out.println("PASS: "+checks+" lifecycle, timing, failure and concurrency assertions");
    }
}
