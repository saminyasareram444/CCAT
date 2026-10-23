package alia;

import javax.swing.SwingUtilities;
import java.nio.file.*;
import java.util.*;

/** Explicitly synthetic preview. Never opens a UDP socket or sends simulator commands. */
public final class PilotPreview {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(()->{try {
            var pilot=new PilotDisplay(()->{},()->{},()->Paths.get("C:/X-Plane 12"));
            var site=new AirportDatabase.Site("Runway 04/22 midpoint",41.5144,-74.2647,22,1527,30.5,1);
            var airport=new AirportDatabase.Airport("KMGJ","Orange County",site.latitude(),site.longitude(),112.8,"Airport",List.of(site),"3D","Synthetic preview");
            AirportPlan plan=new AirportPlan(airport,site,300,90);
            var altSite=new AirportDatabase.Site("Runway 06/24 midpoint",41.6265,-73.8842,60,1500,30,1);
            var altAirport=new AirportDatabase.Airport("KPOU","Hudson Valley",altSite.latitude(),altSite.longitude(),50,"Airport",List.of(altSite));
            pilot.previewAlternate(new AirportPlan(altAirport,altSite,300,90));pilot.update(sample(plan,false),plan);
            pilot.render(Paths.get("build/alia/pilot-navigation-preview.png"),700,1000);
            pilot.render(Paths.get("build/alia/pilot-landscape-preview.png"),1100,760);
            pilot.showPage("Sensors");pilot.render(Paths.get("build/alia/pilot-sensors-preview.png"),700,1000);
            pilot.showPage("Weather");pilot.render(Paths.get("build/alia/pilot-weather-preview.png"),700,1000);
            pilot.showPage("Navigation");pilot.update(sample(plan,true),plan);pilot.render(Paths.get("build/alia/pilot-warning-preview.png"),700,1000);
            pilot.render(Paths.get("build/alia/pilot-compact-warning-preview.png"),600,760);
            pilot.showPage("Sensors");pilot.render(Paths.get("build/alia/pilot-compact-sensors-preview.png"),600,760);
            pilot.showPage("Weather");pilot.render(Paths.get("build/alia/pilot-compact-weather-preview.png"),600,760);
            pilot.showPage("Navigation");
            var waiting=new PilotDisplay(()->{},()->{},()->Paths.get("C:/X-Plane 12"));waiting.render(Paths.get("build/alia/pilot-waiting-preview.png"),600,760);waiting.close();pilot.close();
            var graphs=new PilotDisplay(()->{},()->{},()->Paths.get("C:/X-Plane 12"));graphs.showPage("Explain");
            feedExplainPreview(graphs,plan);
            graphs.render(Paths.get("build/alia/pilot-explain-preview.png"),700,1000);
            graphs.render(Paths.get("build/alia/pilot-explain-landscape-preview.png"),1100,760);
            graphs.render(Paths.get("build/alia/pilot-explain-compact-preview.png"),600,760);
            graphs.render(Paths.get("build/alia/pilot-explain-small-preview.png"),540,680);
            graphs.showAttributionChannel(ControlAttribution.Channel.LIFT);graphs.render(Paths.get("build/alia/pilot-attribution-lift-preview.png"),700,1000);graphs.close();
            System.out.println("Offline pilot previews: build/alia/pilot-*-preview.png (synthetic, no aircraft connection)");
        }catch(Exception ex){throw new RuntimeException(ex);}});
    }
    static void feedExplainPreview(PilotDisplay pilot,AirportPlan plan) {
        var initial=DashboardPreview.position(41.500896,-74.130286,147.7,.5,0,0,0,0,0,0);
        var home=DashboardPreview.position(initial.lat,initial.lon,initial.elevation,initial.agl,0,0,plan.course(initial),0,0,0);
        AirportMission mission=new AirportMission(plan,home,.6);mission.start(0);
        try(var model=new ExplainabilityModel(false)){
            for(int step=1;step<=600;step++){
                double t=step*.05,h=20+.5*Math.sin(t*.3),pitch=4*Math.sin(t*.7),roll=2*Math.sin(t*.4);
                var state=new HoverMission.State(0,0,h,h+.5,pitch,roll,home.heading,0,0,.15*Math.cos(t*.3),.8*Math.cos(t*.4),2.8*Math.cos(t*.7),0,false,0);
                var out=mission.update(state,t);if(step%4!=0)continue;
                var pos=DashboardPreview.position(home.lat,home.lon,home.elevation+h,h+.5,pitch,roll,home.heading,0,state.vUp,0);
                double[] refs=new double[NativeLink.REFS.length];long[] times=new long[refs.length];Arrays.fill(times,System.nanoTime());
                refs[3]=5;refs[4]=out.lift;for(int i=5;i<=8;i++)refs[i]=out.lift;refs[9]=out.pusher;
                for(int i=10;i<=17;i++)refs[i]=1;for(int i=0;i<4;i++)refs[18+i]="ALIA".charAt(i);
                refs[22]=65000;for(int i=0;i<5;i++){refs[23+i]=52000;refs[28+i]=800;refs[33+i]=-40;}
                var snapshot=new NativeLink.Snapshot(pos,refs,times);
                pilot.update(model.update(snapshot,mission.phase().name(),true,t,home,mission.route(),out,"Automatic flight: "+mission.phase(),mission),plan);
            }
        }
    }
    static ExplainabilityModel.Frame sample(AirportPlan plan,boolean fault) {
        var home=DashboardPreview.position(41.500896,-74.130286,147.7,.5,0,0,270,0,0,0);
        var pos=DashboardPreview.position(41.5078,-74.1984,410,277,3,0,280,-43,0,-3);
        double[] refs=new double[NativeLink.REFS.length];long[] times=new long[refs.length];Arrays.fill(times,System.nanoTime());
        refs[0]=fault?1:0;refs[2]=88;refs[3]=5;refs[9]=.55;for(int i=10;i<=17;i++)refs[i]=1;for(int i=0;i<4;i++)refs[18+i]="ALIA".charAt(i);
        refs[22]=65000;for(int i=0;i<5;i++){refs[23+i]=52000;refs[28+i]=800;refs[33+i]=-40;}
        refs[38]=240;refs[39]=5.14444;refs[40]=12;refs[41]=18;refs[42]=0;
        double n=Math.toRadians(pos.lat-home.lat)*6371000,e=Math.toRadians(pos.lon-home.lon)*6371000*Math.cos(Math.toRadians(home.lat));
        var state=new HoverMission.State(n,e,pos.elevation-home.elevation,pos.agl,3,0,280,3,-43,0,0,0,0,false,88);
        Map<String,Double> terms=new LinkedHashMap<>();for(String key:new String[]{"targetClimb","baseLift","verticalP","verticalI","wingSupportFraction","pitchP","pitchD","rollP","rollD","yawP","yawD"})terms.put(key,0.0);
        terms.put("airspeedKnots",88.0);terms.put("targetAirspeedKnots",90.0);terms.put("wingSupportFraction",1.0);
        var target=plan.points(home).get(0);var trace=new HoverMission.Trace(HoverMission.Phase.CRUISE,0,target,state,terms,"Slow down before final approach.","");
        var out=new HoverMission.Output(0,0,0,0,.55,plan.distance(pos),HoverMission.Phase.CRUISE,0,trace);
        var snapshot=new NativeLink.Snapshot(pos,refs,times);
        try(var model=new ExplainabilityModel(false)){return model.update(snapshot,fault?"STOPPED":"CRUISE",!fault,180,home,plan.points(home),out,
            fault?"Flight fault: Control readback differs from commands":"Automatic flight: CRUISE",new MissionController(){public HoverMission.Phase phase(){return HoverMission.Phase.CRUISE;}public List<HoverMission.Point> route(){return plan.points(home);}public void start(double t){}public void stop(){}public HoverMission.Output update(HoverMission.State s,double t){return out;}public String destination(){return plan.label();}public double radiusLimit(){return 20000;}public double maximumHeight(){return 500;}public double minimumHeight(){return -100;}});}
    }
}
