package alia;

import java.util.*;
import static alia.HoverMission.*;

/** Exact rule-term decomposition, not Integrated Gradients or a learned confidence estimate. */
final class ControlAttribution {
    enum Channel {
        LIFT("Lift",0,1),PITCH("Pitch",-.5,.5),ROLL("Roll",-.5,.5),YAW("Yaw",-.4,.4);
        final String label;final double minimum,maximum;
        Channel(String label,double minimum,double maximum){this.label=label;this.minimum=minimum;this.maximum=maximum;}
    }
    record Contribution(String name,double value) {}
    record Result(List<Contribution> contributions,double reconstructed,double command,double errorPp) {
        Result {contributions=List.copyOf(contributions);}
    }
    record Sample(double seconds,Map<Channel,Result> channels) {Sample{channels=Map.copyOf(channels);}}
    private final Deque<Sample> history=new ArrayDeque<>();
    private Output previousOutput;
    private NativeLink.Position origin;
    private double lastTime=-1;

    void update(ExplainabilityModel.Frame frame) {
        if(frame==null)return;
        if(origin!=frame.origin()||frame.elapsed()<lastTime){history.clear();previousOutput=null;origin=frame.origin();lastTime=-1;}
        if(!live(frame)||frame.output()==previousOutput)return;
        previousOutput=frame.output();lastTime=frame.elapsed();
        Map<Channel,Result> values=new EnumMap<>(Channel.class);
        for(var channel:Channel.values())evaluate(frame.output(),channel).ifPresent(value->values.put(channel,value));
        history.addLast(new Sample(frame.elapsed(),values));
        while(!history.isEmpty()&&(frame.elapsed()-history.getFirst().seconds()>60||history.size()>1201))history.removeFirst();
    }
    List<Sample> samples(){return List.copyOf(history);}
    static boolean live(ExplainabilityModel.Frame f){return f!=null&&f.active()&&f.telemetry().fresh()&&f.telemetry().refs[0]==0&&f.output()!=null&&f.output().trace!=null;}
    static String status(ExplainabilityModel.Frame f) {
        if(f==null||!f.telemetry().fresh())return "NO FRESH DATA · HISTORY FROZEN";
        if(f.telemetry().refs[0]!=0)return "PAUSED · HISTORY FROZEN";
        if(!f.active())return "INACTIVE · HISTORY ONLY";
        return live(f)?"LIVE · LAST 60 SECONDS":"WAITING FOR CONTROL TRACE";
    }
    static Optional<Result> evaluate(Output out,Channel channel) {
        if(out==null||out.trace==null)return Optional.empty();
        var t=out.trace;var v=t.terms;List<Contribution> parts=new ArrayList<>();double command,reconstructed;
        try {
            if(channel==Channel.LIFT) {
                double share=t.computedPhase==Phase.CRUISE?0:1-v.getOrDefault("wingSupportFraction",0.0);
                double base=required(v,"baseLift")*share,p=required(v,"verticalP")*share,i=required(v,"verticalI")*share;
                double raw=base+p+i;
                double requested=Math.max(clamp(raw,required(v,"liftMinimum"),required(v,"liftMaximum")),required(v,"liftFloor"));
                double previous=required(v,"previousLift"),dt=required(v,"stepSeconds");
                reconstructed=previous+clamp(requested-previous,-required(v,"liftFallRate")*dt,required(v,"liftRiseRate")*dt);
                if(out.phase==Phase.LANDED)reconstructed=0;
                command=out.lift;
                parts.add(new Contribution("Base lift",base));parts.add(new Contribution("Climb correction",p));parts.add(new Contribution("Accumulated",i));
                parts.add(new Contribution("Output limits",requested-raw));parts.add(new Contribution("Ramp / idle",reconstructed-requested));
            } else {
                String prefix=channel.name().toLowerCase(Locale.ROOT);
                double proportional=required(v,prefix+"P"),damping=required(v,prefix+"D");
                reconstructed=clamp(proportional+damping,channel.minimum,channel.maximum);
                command=switch(channel){case PITCH->out.pitch;case ROLL->out.roll;case YAW->out.yaw;default->throw new AssertionError();};
                parts.add(new Contribution("Correction",proportional));parts.add(new Contribution("Rate damping",damping));parts.add(new Contribution("Output limits",reconstructed-proportional-damping));
            }
            if(!Double.isFinite(reconstructed)||!Double.isFinite(command)||parts.stream().anyMatch(p->!Double.isFinite(p.value())))return Optional.empty();
            // Rebuild from trace terms and recorded limits/rates. The observed output
            // is only used for comparison, never to manufacture a residual contribution.
            double sum=parts.stream().mapToDouble(Contribution::value).sum();
            return Optional.of(new Result(parts,sum,command,Math.abs(sum-command)*100));
        }catch(IllegalArgumentException ex){return Optional.empty();}
    }
    private static double required(Map<String,Double> values,String key){Double v=values.get(key);if(v==null||!Double.isFinite(v))throw new IllegalArgumentException("Missing trace term: "+key);return v;}
}
