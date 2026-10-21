package local.pin;

public final class SubmittedFixesTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args){
        SubmittedFixes history=new SubmittedFixes();
        SimulationEngine.Point first=new SimulationEngine.Point(0,0),next=new SimulationEngine.Point(0,.001);
        history.record(first,1000000000L);history.record(next,1200000000L);
        check(history.matches(0,0,true,1000000000L,1300),"delayed callback matched to earlier moving sample");
        check(!history.matches(0,.001,true,1000000000L,1300),"new target cannot validate old timestamp");
        check(!history.matches(0,0,false,1000000000L,1300),"real fix is not a mock callback");
        check(!history.matches(0,0,true,1000000000L,7000),"stale fix rejected");
        check(!history.matches(0,.001,true,1000000000L,1300),"different coordinate rejected");
        check(!history.matches(0,0,true,1100000000L,1300),"unsubmitted timestamp rejected");
        check(history.matches(0,.001,true,1200000000L,1300),"latest callback matched");
        history.clear();check(!history.matches(0,.001,true,1200000000L,1300),"new session forgets previous callbacks");
        for(int i=0;i<70;i++)history.record(first,1000000000L+i*1000000L);
        check(!history.matches(0,0,true,1000000000L,1300),"history has bounded retention");
        check(history.matches(0,0,true,1069000000L,1300),"latest retained sample accepted");
        System.out.println("PASS: "+checks+" submitted-fix history assertions");
    }
}
