package alia;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import static alia.HoverMission.*;

/** Deterministic attribution, history gating and repeated alert repaint regressions. */
final class PilotAttributionTests {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static int run() throws Exception {
        HoverMission controller=new HoverMission(List.of(new Point(0,20,15)),0,.6,3);controller.start(0);
        State s=new State(0,0,10,11.5,20,10,20,0,0,.2,3,10,2,false);
        Output out=controller.update(s,.05);
        for(var channel:ControlAttribution.Channel.values())check(ControlAttribution.evaluate(out,channel).orElseThrow().errorPp()<1e-10,"actual hover trace closes for "+channel);
        var lift=ControlAttribution.evaluate(out,ControlAttribution.Channel.LIFT).orElseThrow();
        check(lift.contributions().size()==5&&lift.contributions().getLast().value()<0,"spin-up rate limit is explicitly attributed, not hidden in an error term");
        check(Math.abs(lift.command()-.01)<1e-12&&Math.abs(lift.reconstructed()-.01)<1e-12,"lift reconstruction independently applies previous command, dt and rise rate");
        var pitch=ControlAttribution.evaluate(out,ControlAttribution.Channel.PITCH).orElseThrow();
        check(pitch.command()==-.5&&pitch.contributions().getLast().value()>0,"saturated pitch explicitly attributes the output clamp");
        var altered=new Output(out.pitch+.1,out.roll,out.yaw,out.lift,out.pusher,out.distance,out.phase,out.waypoint,out.trace);
        check(Math.abs(ControlAttribution.evaluate(altered,ControlAttribution.Channel.PITCH).orElseThrow().errorPp()-10)<1e-9,"a changed output produces 10 pp attribution error instead of an automatically manufactured zero");
        var alteredLift=new Output(out.pitch,out.roll,out.yaw,out.lift+.1,out.pusher,out.distance,out.phase,out.waypoint,out.trace);
        check(Math.abs(ControlAttribution.evaluate(alteredLift,ControlAttribution.Channel.LIFT).orElseThrow().errorPp()-10)<1e-9,"lift closure detects a mismatch without using observed output to derive contributions");
        var landed=new Output(out.pitch,out.roll,out.yaw,0,0,out.distance,Phase.LANDED,out.waypoint,out.trace);
        check(ControlAttribution.evaluate(landed,ControlAttribution.Channel.LIFT).orElseThrow().errorPp()<1e-10,"touchdown idle is included in the reconstructed command");
        Map<String,Double> incomplete=new LinkedHashMap<>(out.trace.terms);incomplete.remove("previousLift");
        var partial=new Output(out.pitch,out.roll,out.yaw,out.lift,out.pusher,out.distance,out.phase,out.waypoint,new Trace(out.trace.computedPhase,0,out.trace.target,s,incomplete,"",""));
        check(ControlAttribution.evaluate(partial,ControlAttribution.Channel.LIFT).isEmpty(),"missing history instrumentation cannot produce a fabricated lift attribution");
        incomplete.put("pitchP",Double.NaN);
        partial=new Output(out.pitch,out.roll,out.yaw,out.lift,out.pusher,out.distance,out.phase,out.waypoint,new Trace(out.trace.computedPhase,0,out.trace.target,s,incomplete,"",""));
        check(ControlAttribution.evaluate(partial,ControlAttribution.Channel.PITCH).isEmpty(),"non-finite trace contributions are unavailable");
        var home=DashboardPreview.position(36.1,-115.2,600,1.5,0,0,0,0,0,0);
        var site=new AirportDatabase.Site("Fixture",home.lat,home.lon+.1,90,1000,30,1);
        var airport=new AirportDatabase.Airport("TEST","Fixture",site.latitude(),site.longitude(),598.5,"Airport",List.of(site));
        AirportPlan plan=new AirportPlan(airport,site,300,90);AirportMission mission=new AirportMission(plan,home,.6);mission.start(0);
        double time=0,heading=plan.course(home);
        for(int i=0;i<45;i++){time+=.05;out=mission.update(new State(0,0,40,41.5,0,0,heading,0,0,0,0,0,0,false,0),time);}
        time+=.05;out=mission.update(new State(0,0,15,16.5,0,0,heading,0,0,10,0,0,0,false,0),time);
        check(out.trace.terms.get("liftFloor")==.6&&ControlAttribution.evaluate(out,ControlAttribution.Channel.LIFT).orElseThrow().errorPp()<1e-9,"transition lift floor is independently reconstructed");
        for(int i=0;i<65;i++){time+=.05;out=mission.update(new State(0,100,50,51.5,2,-1,heading,0,38,0,.2,.3,.1,false,75),time);}
        time+=.05;out=mission.update(new State(0,500,300,301.5,3,1,heading,0,45,0,.2,.3,.1,false,90),time);
        for(var channel:ControlAttribution.Channel.values())check(ControlAttribution.evaluate(out,channel).orElseThrow().errorPp()<1e-9,"airport cruise reconstruction closes for "+channel);
        history(home,plan,out);alertPresentation();
        javax.swing.SwingUtilities.invokeAndWait(PilotAttributionTests::paintAndLayout);
        System.out.println("PASS: "+checks+" live attribution / banner checks (independent reconstruction, error detection, frozen history, stable layout, clean repaint).");return checks;
    }
    private static ExplainabilityModel.Frame frame(NativeLink.Position home,AirportPlan plan,Output out,double time,boolean active,boolean paused,boolean stale){
        var pos=DashboardPreview.position(home.lat,home.lon,home.elevation+30,31.5,0,0,0,0,0,0);
        double[] refs=new double[NativeLink.REFS.length];long[] times=new long[refs.length];Arrays.fill(times,System.nanoTime());
        refs[0]=paused?1:0;refs[3]=5;for(int i=0;i<4;i++)refs[18+i]="ALIA".charAt(i);for(int i=13;i<=17;i++)refs[i]=1;
        if(stale)Arrays.fill(times,0,NativeLink.CORE_COUNT,System.nanoTime()-2_000_000_000L);
        var snapshot=new NativeLink.Snapshot(pos,refs,times);
        return new ExplainabilityModel.Frame(snapshot,"CRUISE",active,time,home,plan.points(home),out,ExplainabilityModel.Level.NOMINAL,0,0,1,0,0,"","","",List.of(),List.of(),List.of(),"",new ExplainabilityModel.Limits("TEST",20000,-100,500),BatteryStatus.from(snapshot));
    }
    private static void history(NativeLink.Position home,AirportPlan plan,Output out){
        var history=new ControlAttribution();history.update(frame(home,plan,out,1,true,false,false));history.update(frame(home,plan,out,1,true,false,false));
        check(history.samples().size()==1,"the same completed trace is sampled once, not once per repaint");
        Output next=new Output(out.pitch,out.roll,out.yaw,out.lift,out.pusher,out.distance,out.phase,out.waypoint,out.trace);
        history.update(frame(home,plan,next,2,true,true,false));history.update(frame(home,plan,next,2,true,false,true));history.update(frame(home,plan,next,2,false,false,false));
        check(history.samples().size()==1,"paused, stale and inactive states do not extend the live chart");
        history.update(frame(home,plan,next,2,true,false,false));check(history.samples().size()==2,"a new live completed step extends the chart");
        for(int i=3;i<70;i++){Output step=new Output(out.pitch,out.roll,out.yaw,out.lift,out.pusher,out.distance,out.phase,out.waypoint,out.trace);history.update(frame(home,plan,step,i,true,false,false));}
        check(history.samples().getFirst().seconds()>=9&&history.samples().getLast().seconds()==69,"history is bounded to the last 60 seconds of mission time");
        history.update(frame(home,plan,next,.1,true,false,false));check(history.samples().size()==1,"restarting a mission resets chart time and history");
    }
    private static void alertPresentation(){
        var normal=new PilotDisplayModel.Alert(PilotDisplayModel.Tone.NORMAL,"MONITORING","Automatic flight active","");
        var caution=new PilotDisplayModel.Alert(PilotDisplayModel.Tone.CAUTION,"FLIGHT MARGIN","Near a configured limit","Monitor");
        var warning=new PilotDisplayModel.Alert(PilotDisplayModel.Tone.WARNING,"TAKE OVER","Fault","Pause");
        PilotAlertState state=new PilotAlertState();state.update(normal,0);
        check(state.update(caution,1).equals(caution),"caution escalation is immediate");
        check(state.update(normal,2).equals(caution),"one recovered frame cannot flash the caution off");
        state.update(caution,500_000_000L);state.update(normal,600_000_000L);
        check(state.update(normal,1_600_000_000L).equals(caution),"repeated threshold crossing restarts stable recovery");
        check(state.update(warning,1_600_000_001L).equals(warning),"critical escalation bypasses recovery hold");
        state.update(normal,1_700_000_000L);
        check(state.update(normal,3_200_000_000L).equals(normal),"caution clears after a stable 1.5 second recovery");
    }
    private static void paintAndLayout(){
        var warning=new PilotDisplayModel.Alert(PilotDisplayModel.Tone.WARNING,"TAKE OVER","A long old fault message to erase completely","Pause and inspect the aircraft state");
        var normal=new PilotDisplayModel.Alert(PilotDisplayModel.Tone.NORMAL,"MONITORING","Automatic flight active","");
        PilotDisplay.Banner reused=new PilotDisplay.Banner(),fresh=new PilotDisplay.Banner();reused.setSize(668,PilotDisplay.Banner.HEIGHT);fresh.setSize(668,PilotDisplay.Banner.HEIGHT);
        BufferedImage image=new BufferedImage(668,PilotDisplay.Banner.HEIGHT,BufferedImage.TYPE_INT_RGB),expected=new BufferedImage(668,PilotDisplay.Banner.HEIGHT,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();reused.update(warning,0);reused.paint(g);
        Dimension fixed=reused.getPreferredSize();reused.update(normal,1);reused.update(normal,1+PilotAlertState.RECOVERY_NANOS);reused.paint(g);g.dispose();
        Graphics2D eg=expected.createGraphics();fresh.update(normal,0);fresh.paint(eg);eg.dispose();
        boolean equal=Arrays.equals(image.getRGB(0,0,668,PilotDisplay.Banner.HEIGHT,null,0,668),expected.getRGB(0,0,668,PilotDisplay.Banner.HEIGHT,null,0,668));
        check(equal,"warning-to-normal partial repaint matches a clean render, with no old triangle or text remaining");
        check(reused.isOpaque()&&reused.getPreferredSize().equals(fixed),"banner is opaque and does not resize the content when severity changes");
        for(int w:new int[]{508,568,668,1068})for(int h:new int[]{210,300,440,540,620}){
            var l=PilotDisplay.explainLayout(w,h);List<Rectangle> rectangles=List.of(l.intent(),l.controls(),l.contributions(),l.trend(),l.metric());
            boolean inside=true,separate=true;
            for(int i=0;i<rectangles.size();i++){Rectangle r=rectangles.get(i);inside&=r.x>=0&&r.y>=0&&r.width>0&&r.height>=0&&r.x+r.width<=w&&r.y+r.height<=h;for(int j=i+1;j<rectangles.size();j++)if(r.intersects(rectangles.get(j)))separate=false;}
            check(inside&&separate&&l.controls().height==44,"attribution controls and plots fit without overlap at "+w+"×"+h);
        }
    }
}
