package alia;

import java.nio.file.Path;
import java.util.*;
import org.jsoar.kernel.*;
import org.jsoar.kernel.io.*;
import org.jsoar.kernel.memory.Wme;

/** Actual JSoar numeric-preference RL. Confined to the cognitive worker thread. */
final class SoarWarningAgent implements AutoCloseable {
    private final Agent agent;
    private InputWme stage,reward;
    private String action,band;
    private boolean pending,training;
    SoarWarningAgent(String name,long seed,Path source) throws Exception {
        agent=new Agent(name);agent.getTrace().disableAll();agent.getRandom().setSeed(seed);
        agent.getInterpreter().source(source.toFile());
        for(String b:List.of("low","high"))for(String a:List.of("warn","quiet")) {
            agent.getInterpreter().eval("sp {q*"+b+"*"+a+" (state <s> ^band "+b+" ^operator <o> +) (<o> ^name "+a+") --> (<s> ^operator <o> = "+(a.equals("warn")?"0.2":"0.0")+")}");
        }
        agent.getInterpreter().eval("rl --set learning-rate 0.3");
        agent.getInterpreter().eval("rl --set discount-rate 0.0");
        agent.getInterpreter().eval("rl --set eligibility-trace-decay-rate 0.0");
        agent.getInterpreter().eval("decide indifferent-selection --epsilon-greedy");
    }
    String decide(double error,boolean available,boolean train) throws Exception {
        agent.initialize();pending=false;training=train;
        agent.getInterpreter().eval("rl --set learning "+(train?"on":"off"));
        agent.getInterpreter().eval("decide indifferent-selection --epsilon "+(train?"0.2":"0.0"));
        InputOutput io=agent.getInputOutput();
        InputWmes.add(io,"error",error);InputWmes.add(io,"available",available?"yes":"no");
        stage=InputWmes.add(io,"stage","decide");reward=InputWmes.add(io,"reward",0.0);
        action=null;band=null;
        for(int n=0;n<8&&action==null;n++) {
            agent.runFor(1,RunType.DECISIONS);
            for(Iterator<Wme> it=io.getOutputLink().getWmes();it.hasNext();) {
                Wme w=it.next();if(w.getAttribute().toString().equals("decision"))action=w.getValue().toString();
            }
        }
        if(action==null)throw new IllegalStateException("Soar produced no decision within 8 cycles");
        band=!available?"unavailable":error<8?"normal":error<9?"low":error<10?"high":"safety";
        pending=(band.equals("low")||band.equals("high"));return action;
    }
    boolean feedback(boolean appropriate) {
        if(!pending||!training)return false;
        pending=false;
        InputWmes.update(reward,appropriate?1.0:-1.0);InputWmes.update(stage,"terminal");
        agent.runFor(3,RunType.DECISIONS);
        return true;
    }
    double value(String b,String a){var r=agent.getProductions().getProduction("q*"+b+"*"+a).rlRuleInfo;return r.rl_ecr+r.rl_efr;}
    long updates(String b){long count=0;for(String a:List.of("warn","quiet"))count+=(long)agent.getProductions().getProduction("q*"+b+"*"+a).rlRuleInfo.rl_update_count;return count;}
    public void close(){agent.dispose();}
}
