package alia;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline fault-copy experiment on measured AHRS pairs. Never opens NativeLink. */
public final class SensorReplayExperiment {
    public static void main(String[] args)throws Exception {
        if(args.length==0)throw new IllegalArgumentException("Provide completed trial flight.csv paths");
        Path output=Path.of("research/local/sensor-experiments",String.valueOf(System.currentTimeMillis()));Files.createDirectories(output);
        Path source=Path.of("research/soar/sensor-warning.soar");Random noise=new Random(20261028);
        int checks=0,matched=0;String[] scenarios={"nominal","low-bias","high-bias","critical-bias","dropout","stale"};
        int[] total=new int[6],correct=new int[6];
        try(var learned=new SoarWarningAgent("AHRS-warning-preference",42,source);var baseline=new SoarWarningAgent("untrained-comparator",42,source);PrintWriter log=new PrintWriter(Files.newBufferedWriter(output.resolve("decisions.csv")))){
            // Scripted preference oracle, explicitly not an actual pilot or safety label.
            // Low alerts considered unnecessary; high alerts considered useful for this demo.
            for(int n=0;n<100;n++){
                double error=n%2==0?8.5:9.5;String expected=n%2==0?"quiet":"warn";
                String action=learned.decide(error,true,true);if(!learned.feedback(action.equals(expected)))throw new AssertionError("Training reward was not applied");
            }
            double lowWarn=learned.value("low","warn"),lowQuiet=learned.value("low","quiet"),highWarn=learned.value("high","warn"),highQuiet=learned.value("high","quiet");
            long updates=learned.updates("low")+learned.updates("high");
            log.println("flight,row,elapsed_s,scenario,measured_pitch_1_deg,measured_pitch_2_deg,copied_pitch_1_deg,normalized_proxy,available,expected_scripted_action,baseline_action,learned_action");
            int changed=0;
            for(String arg:args){Path path=Path.of(arg);List<String> lines=Files.readAllLines(path);String[] header=lines.getFirst().split(",");Map<String,Integer> index=new HashMap<>();for(int k=0;k<header.length;k++)index.put(header[k],k);
                String flight=path.getParent().getFileName().toString();
                for(int row=1;row<lines.size();row+=20){String[] values=lines.get(row).split(",");double p1=Double.parseDouble(values[index.get("ahrs_pitch_pilot_deg")]),p2=Double.parseDouble(values[index.get("ahrs_pitch_copilot_deg")]),elapsed=Double.parseDouble(values[index.get("elapsed_wall_s")]);
                    for(int c=0;c<scenarios.length;c++){
                        double bias=switch(c){case 1->4.1;case 2->4.6;case 3->5.5;default->0;};
                        double copied=p1+bias+(c>=1&&c<=3?(noise.nextDouble()-.5)*.04:0);
                        double proxy=Math.min(20,2*Math.abs(copied-p2));boolean available=c<4;
                        String expected=switch(c){case 0,1->"quiet";case 2->"warn";case 3->"mandatory-warning";default->"unavailable";};
                        String base=baseline.decide(proxy,available,false),action=learned.decide(proxy,available,false);
                        checks++;total[c]++;if(action.equals(expected)){matched++;correct[c]++;}if(!base.equals(action))changed++;
                        log.printf(Locale.ROOT,"%s,%d,%.3f,%s,%.6f,%.6f,%.6f,%.6f,%s,%s,%s,%s%n",flight,row,elapsed,scenarios[c],p1,p2,copied,proxy,available,expected,base,action);
                    }
                }
            }
            if(learned.updates("low")+learned.updates("high")!=updates)throw new AssertionError("Evaluation modified learned preferences");
            StringJoiner cases=new StringJoiner(",","[","]");for(int c=0;c<scenarios.length;c++)cases.add("{\"scenario\":\""+scenarios[c]+"\",\"trials\":"+total[c]+",\"matching_scripted_expectation\":"+correct[c]+"}");
            String report="{\"kind\":\"functional_counterfactual_replay_not_detection_accuracy_validation\",\"source\":\"measured X-Plane AHRS pairs with synthetic copied-channel faults\",\"physical_sensor_independence_established\":false,\"faults_written_to_aircraft\":false,\"pilot_feedback\":\"scripted preference oracle, not a human\",\"seed\":20261028,\"normalization\":\"2 * absolute pitch difference in degrees; research convention, not calibrated safety threshold\",\"training_episodes\":100,\"checks\":"+checks+",\"matching_scripted_expectation\":"+matched+",\"actions_changed_from_untrained\":"+changed+",\"evaluation_learning_frozen\":true,\"low_warn_q\":"+lowWarn+",\"low_quiet_q\":"+lowQuiet+",\"high_warn_q\":"+highWarn+",\"high_quiet_q\":"+highQuiet+",\"scenarios\":"+cases+"}";
            Files.writeString(output.resolve("results.json"),report+"\n");System.out.println(report);System.out.println("Saved "+output);
            if(matched!=checks)throw new AssertionError("Replay behavior differed from scripted functional expectation");
        }
    }
}
