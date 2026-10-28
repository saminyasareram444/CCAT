package alia;

import java.util.*;

final class SimulatorSensorsTests {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static int run(NativeLink.Snapshot base){
        var refs=base.refs.clone();var times=base.times.clone();Arrays.fill(times,SimulatorSensors.OFFSET,times.length,System.nanoTime());
        refs[SimulatorSensors.OFFSET+SimulatorSensors.Key.PITCH_1.ordinal()]=4;
        refs[SimulatorSensors.OFFSET+SimulatorSensors.Key.PITCH_2.ordinal()]=1;
        refs[SimulatorSensors.OFFSET+SimulatorSensors.Key.ROLL_1.ordinal()]=179;
        refs[SimulatorSensors.OFFSET+SimulatorSensors.Key.ROLL_2.ordinal()]=-179;
        var sample=new NativeLink.Snapshot(base.pos,refs,times);
        check(SimulatorSensors.attitudeDisagreement(sample)==3,"observed attitude disagreement uses wrapped roll difference");
        check(SimulatorSensors.formatted(sample,SimulatorSensors.Key.PITCH_1).equals("4.00 deg"),"instrument units retained");
        int stale=SimulatorSensors.OFFSET+SimulatorSensors.Key.PITCH_2.ordinal();times[stale]=System.nanoTime()-2_000_000_000L;
        sample=new NativeLink.Snapshot(base.pos,refs,times);
        check(Double.isNaN(SimulatorSensors.attitudeDisagreement(sample)),"stale instrument cannot give zero disagreement");
        check(SimulatorSensors.formatted(sample,SimulatorSensors.Key.PITCH_2).contains("UNAVAILABLE"),"stale instrument labelled unavailable");
        check(sample.fresh(),"optional stale sensor does not break core flight telemetry");
        refs[SimulatorSensors.OFFSET]=Double.NaN;check(Double.isNaN(SimulatorSensors.value(new NativeLink.Snapshot(base.pos,refs,times),SimulatorSensors.Key.PITCH_1)),"nonfinite instrument hidden");
        check(Double.isNaN(SimulatorSensors.value(null,SimulatorSensors.Key.RADIO_ALT_1)),"no fabricated startup range");
        check(NativeLink.REFS[SimulatorSensors.OFFSET].equals(SimulatorSensors.Key.PITCH_1.ref),"stable optional instrument offset");
        check(!Arrays.stream(SimulatorSensors.Key.values()).anyMatch(k->k.ref.toLowerCase(Locale.ROOT).contains("lidar")),"radio altitude never mislabeled LiDAR");
        System.out.println("PASS: "+checks+" simulated instrument checks (provenance, timestamps, units, missing data).");return checks;
    }
}
