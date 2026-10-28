package alia;

import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exercises the pinned real JSoar kernel, not a replacement rule evaluator. */
public final class SoarSensorTests {
    private static int checks;
    private static final Path SOURCE=Path.of("research/soar/sensor-warning.soar");
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void near(double a,double b,String why){check(Math.abs(a-b)<1e-8,why+" ("+a+", "+b+")");}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+12_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);check(condition.getAsBoolean(),"asynchronous worker completed");}
    public static void main(String[] args)throws Exception {
        combinations();learning();host();
        System.out.println("PASS: "+checks+" real JSoar checks: 64 simultaneous band combinations, boundaries, reward lifetime, isolated sensors, frozen evaluation, correlated/duplicate/expired feedback and async tablet interface.");
        System.out.println("Offline synthetic warning demonstration only; no aircraft network connection or control authority.");
    }
    private static void combinations()throws Exception {
        double[] values={0,8.5,9.5,10};String[] expected={"quiet","warn","warn","mandatory-warning"};
        try(var gps=new SoarWarningAgent("GPS",42,SOURCE);var imu=new SoarWarningAgent("IMU",43,SOURCE);var lidar=new SoarWarningAgent("LIDAR",44,SOURCE)){
            var agents=List.of(gps,imu,lidar);
            for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int c=0;c<4;c++){
                int[] bands={a,b,c};for(int s=0;s<3;s++)check(agents.get(s).decide(values[bands[s]],true,false).equals(expected[bands[s]]),"independent simultaneous band combination "+a+b+c+" sensor "+s);
            }
            for(double value:new double[]{0,7.999999,8,8.999999,9,9.999999,10,20}){
                check(gps.decide(value,true,false).equals(value<8?"quiet":value<10?"warn":"mandatory-warning"),"boundary "+value);
            }
            check(gps.decide(10,false,false).equals("unavailable"),"missing data never becomes a normal reading or learned safety suppression");
            for(var a:agents)for(String band:List.of("low","high"))check(a.updates(band)==0,"evaluation never updates preferences");
        }
    }
    private static void learning()throws Exception {
        try(var a=new SoarWarningAgent("learning",42,SOURCE)){
            near(a.value("low","warn"),.2,"initial preference");
            check(a.decide(8.5,true,true).equals("warn"),"seeded first warning");
            check(a.feedback(false),"negative rating accepted");
            near(a.value("low","warn"),-.16,"one contextual-bandit update (alpha .3, reward -1)");
            check(a.updates("low")==1,"one reward pulse produces one update");
            check(!a.feedback(false),"duplicate rating not rewarded");check(a.updates("low")==1,"no repeated reward");
            near(a.value("high","warn"),.2,"other band untouched");near(a.value("low","quiet"),0,"other action untouched");
            check(a.decide(8.5,true,false).equals("quiet"),"learned policy changes on frozen evaluation");
            check(!a.feedback(true),"evaluation cannot train");near(a.value("low","warn"),-.16,"reset working memory retains learned preference");
            for(int n=0;n<20;n++)check(a.decide(9.5,true,false).equals("warn"),"frozen decisions deterministic");
            check(a.updates("low")==1&&a.updates("high")==0,"frozen replay preserves update counts");
            check(a.decide(10,true,true).equals("mandatory-warning")&&!a.feedback(false),"mandatory safety cannot be learned away");
            check(a.decide(0,true,true).equals("quiet")&&!a.feedback(true),"normal state fixed");
        }
        try(var a=new SoarWarningAgent("positive",42,SOURCE)){
            a.decide(9.5,true,true);check(a.feedback(true),"positive feedback accepted");near(a.value("high","warn"),.44,"positive rating increases preference once");
        }
    }
    private static List<SensorResearch.Input> inputs(double gps,SensorResearch.Availability availability){return List.of(new SensorResearch.Input(SensorResearch.Sensor.GPS,gps,availability),new SensorResearch.Input(SensorResearch.Sensor.IMU,0,SensorResearch.Availability.AVAILABLE),new SensorResearch.Input(SensorResearch.Sensor.LIDAR,10,SensorResearch.Availability.AVAILABLE));}
    private static void host()throws Exception {
        try(var research=new SoarSensorResearch()){
            await(()->research.view().sequence()>0&&research.view().fresh());
            long seq=research.view().sequence();research.submit(inputs(8.5,SensorResearch.Availability.AVAILABLE));await(()->research.view().sequence()>seq);
            var initial=research.view();String id=initial.decisions().stream().filter(d->d.sensor()==SensorResearch.Sensor.GPS).findFirst().orElseThrow().id();
            research.feedback("wrong-id",true);await(()->research.view().status().startsWith("Rating ignored"));check(research.view().feedbackCount()==0,"unknown rating ignored");
            research.feedback(id,false);await(()->research.view().feedbackCount()==1);
            research.feedback(id,false);await(()->research.view().status().startsWith("Rating ignored"));check(research.view().feedbackCount()==1,"duplicate ID not counted");
            check(research.view().scores().stream().filter(s->s.sensor()!=SensorResearch.Sensor.GPS).allMatch(s->s.updates()==0),"no cross-sensor credit");
            long previous=research.view().sequence();research.submit(inputs(9.5,SensorResearch.Availability.AVAILABLE));await(()->research.view().sequence()>previous);
            String expires=research.view().decisions().getFirst().id();research.feedbackAt(expires,true,Long.MAX_VALUE);await(()->research.view().status().startsWith("Rating ignored"));check(research.view().feedbackCount()==1,"expired rating gives no reward");
            long before=research.view().sequence();research.training(false);await(()->research.view().sequence()>before);
            check(!research.view().training()&&research.view().decisions().stream().noneMatch(SensorResearch.Decision::rateable),"frozen mode disables all rating controls");
            for(var availability:List.of(SensorResearch.Availability.STALE,SensorResearch.Availability.DROPOUT)){
                long n=research.view().sequence();research.submit(inputs(0,availability));await(()->research.view().sequence()>n);
                check(research.view().decisions().getFirst().action().equals("unavailable"),"stale / dropout explicitly unavailable");
                check(research.view().decisions().getLast().action().equals("mandatory-warning"),"normal or unavailable sensor cannot suppress another safety warning");
            }
            try{research.submit(inputs(0,SensorResearch.Availability.AVAILABLE).subList(0,2));check(false,"reject missing sensor");}catch(IllegalArgumentException expected){check(true,"incomplete input rejected");}
            try{new SensorResearch.Input(SensorResearch.Sensor.GPS,Double.NaN,SensorResearch.Availability.AVAILABLE);check(false,"reject invalid");}catch(IllegalArgumentException expected){check(true,"nonfinite input rejected");}
        }
    }
}
