package local.pin;

public final class PositionCheckTest {
    public static void main(String[] args) {
        check(PositionCheck.evaluate(41,120,41,120,true,10000,11000).state==PositionCheck.State.MATCH,"fresh exact mock");
        check(PositionCheck.evaluate(41,120,41,120.001,true,10000,11000).state==PositionCheck.State.OFFSET,"another mock point");
        check(PositionCheck.evaluate(41,120,41,120,true,10000,20000).state==PositionCheck.State.STALE,"cached old point");
        check(PositionCheck.evaluate(41,120,41,120,false,10000,11000).state==PositionCheck.State.NOT_MOCK,"real position must not count as mock success");
        check(PositionCheck.evaluate(41,120,Double.NaN,120,true,10000,11000).state==PositionCheck.State.NO_FIX,"missing fix");
        check(PositionCheck.evaluate(41,120,41,120,true,0,11000).state==PositionCheck.State.BAD_TIME,"missing elapsed time");
        check(PositionCheck.evaluate(41,120,41,120,true,20000,11000).state==PositionCheck.State.BAD_TIME,"future fix");
        check(PositionCheck.evaluate(91,120,41,120,true,10000,11000).state==PositionCheck.State.NO_FIX,"invalid request");
        near(PositionCheck.distanceMeters(0,0,0,1),111195.0802335329,.01,"one degree on equator");
        near(PositionCheck.distanceMeters(0,179.999,0,-179.999),222.390160467,.01,"date line wrapping");
        near(PositionCheck.distanceMeters(41,120,41,120),0,.000001,"identical points");
        System.out.println("PASS: 11 system-readback validation assertions");
    }
    private static void near(double a,double b,double tolerance,String message){check(Math.abs(a-b)<=tolerance,message);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
