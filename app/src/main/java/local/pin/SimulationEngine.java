package local.pin;

/** Platform-independent, serialized lifecycle; a target is always an immutable coordinate pair. */
final class SimulationEngine {
    static final int DEFAULT_INTERVAL=200;
    static final class Point {
        final double latitude,longitude;
        Point(double lat,double lon) {
            if(!MapMath.valid(lat,lon))throw new IllegalArgumentException("Invalid point");
            latitude=lat;longitude=lon;
        }
    }
    interface Backend {
        void open();
        void write(Point point,long utcMillis,long elapsedNanos);
        void listen();
        boolean close();
    }
    interface Clock { long utcMillis();long elapsedMillis();long elapsedNanos(); }
    interface Scheduler { void post(Runnable task,long delayMillis);void remove(Runnable task); }
    interface Observer { void changed(State state); }
    static final class State {
        final boolean running,cleanupSucceeded;
        final Point point;
        final int intervalMillis;
        final long writes,lastWriteMillis,recoveredPauseMillis;
        final String error;
        State(boolean running,boolean clean,Point point,int interval,long writes,long last,long pause,String error) {
            this.running=running;cleanupSucceeded=clean;this.point=point;intervalMillis=interval;
            this.writes=writes;lastWriteMillis=last;recoveredPauseMillis=pause;this.error=error;
        }
    }
    private final Backend backend;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Observer observer;
    private boolean running,opened,cleanupSucceeded=true;
    private Point target;
    private int interval=DEFAULT_INTERVAL;
    private long writes,lastWriteMillis,pauseMillis;
    private String error="";
    private State state;
    private final Runnable tick=new Runnable(){public void run(){refresh();}};
    SimulationEngine(Backend backend,Clock clock,Scheduler scheduler,Observer observer) {
        this.backend=backend;this.clock=clock;this.scheduler=scheduler;this.observer=observer;state=snapshot();
    }
    static int validInterval(int requested) {
        return requested==200||requested==500||requested==1000?requested:DEFAULT_INTERVAL;
    }
    synchronized State state(){return state;}
    synchronized void apply(Point point,int requestedInterval) {
        if(point==null)throw new IllegalArgumentException("Missing point");
        scheduler.remove(tick);target=point;interval=validInterval(requestedInterval);error="";
        try {
            boolean first=!opened;
            if(first){
                writes=0;lastWriteMillis=0;pauseMillis=0;
                // Even a partially failed open must release its allocated providers.
                opened=true;cleanupSucceeded=false;backend.open();
            }
            running=true;write();
            if(first)backend.listen();
            scheduler.post(tick,interval);publish();
        }catch(RuntimeException failure){fail(failure);}
    }
    private synchronized void refresh() {
        if(!running)return;
        try{write();scheduler.post(tick,interval);publish();}
        catch(RuntimeException failure){fail(failure);}
    }
    private void write() {
        backend.write(target,clock.utcMillis(),clock.elapsedNanos());
        long now=clock.elapsedMillis();
        if(writes>0&&now-lastWriteMillis>5000)pauseMillis=now-lastWriteMillis;
        lastWriteMillis=now;writes++;
    }
    synchronized void stop(){running=false;scheduler.remove(tick);close();publish();}
    private void fail(RuntimeException failure) {
        error=failure.getClass().getSimpleName();running=false;scheduler.remove(tick);close();publish();
    }
    private void close() {
        if(!opened)return;
        try{cleanupSucceeded=backend.close();}catch(RuntimeException failure){cleanupSucceeded=false;}
        opened=false;
    }
    private State snapshot(){return new State(running,cleanupSucceeded,target,interval,writes,lastWriteMillis,pauseMillis,error);}
    private void publish(){state=snapshot();observer.changed(state);}
}
