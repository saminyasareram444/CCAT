package alia;

import javax.swing.SwingUtilities;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Offline visual QA only. Never opens a simulator connection or sends aircraft commands. */
public final class DashboardPreview {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try {
                var dashboard=new ExplainabilityDashboard(()->{},()->{},()->{},()->{});
                dashboard.update(sample());dashboard.render(Paths.get("build/alia/dashboard-preview.png"));
                dashboard.showBatteryPower();dashboard.render(Paths.get("build/alia/dashboard-battery-preview.png"));
                System.out.println("Offline UI preview: build/alia/dashboard-preview.png (synthetic data)");
            }catch(Exception e){throw new RuntimeException(e);}
        });
    }
    static ExplainabilityModel.Frame sample() {
        var route=List.of(new HoverMission.Point(0,60,15),new HoverMission.Point(40,60,15),new HoverMission.Point(0,0,15));
        HoverMission mission=new HoverMission(route,0,.60,3);mission.start(0);
        HoverMission.Output out=null;
        for(int i=1;i<=60;i++)out=mission.update(new HoverMission.State(0,0,15,16.5,0,0,0,0,0,0,0,0,0,false),i*.05);
        out=mission.update(new HoverMission.State(0,40,14.5,16,-2,27.8,0,0,2,.2,1,.3,0,false),3.05);
        NativeLink.Position home=position(36.1,-115.2,600,1.5,0,0,0,0,0,0);
        double longitude=-115.2+Math.toDegrees(40/(6371000*Math.cos(Math.toRadians(36.1))));
        NativeLink.Position pos=position(36.1,longitude,614.5,16,-2,27.8,0,2,.2,0);
        double[] refs=new double[NativeLink.REFS.length];long[] times=new long[refs.length];Arrays.fill(times,System.nanoTime());
        refs[3]=5;refs[4]=out.lift;for(int i=5;i<=8;i++)refs[i]=out.lift-.015;
        for(int i=10;i<=12;i++)refs[i]=1;for(int i=13;i<=17;i++)refs[i]=1;
        for(int i=0;i<4;i++)refs[18+i]="ALIA".charAt(i);
        refs[22]=65000;for(int i=0;i<5;i++){refs[23+i]=61000;refs[28+i]=800;refs[33+i]=75;}
        NativeLink.Snapshot snapshot=new NativeLink.Snapshot(pos,refs,times);
        ExplainabilityModel.Frame base;
        try(var model=new ExplainabilityModel(false)){base=model.update(snapshot,"WAYPOINTS",true,43,home,route,out,"Automatic flight: WAYPOINTS");}
        List<ExplainabilityModel.Sample> samples=new ArrayList<>();
        for(int i=0;i<220;i++) {
            double t=i*.2,height=15*(1-Math.exp(-t/8))+.2*Math.sin(t/2),east=Math.max(0,t-22)*1.8;
            samples.add(new ExplainabilityModel.Sample(t,Math.max(0,t-38)*.1,east,height,15,
                2*Math.sin(t/3),27.8*Math.pow(t/44,3),.60+.03*Math.sin(t/4),.59+.025*Math.sin(t/4-.2),.1,45,
                96-t*.08,300+30*Math.sin(t/5)));
        }
        List<ExplainabilityModel.Event> events=List.of(
            new ExplainabilityModel.Event("10:42:43",ExplainabilityModel.Level.CAUTION,"Attitude margin reduced","Roll 27.8° uses 79% of the configured 35° limit."),
            new ExplainabilityModel.Event("10:42:24",ExplainabilityModel.Level.NOMINAL,"Takeoff gate passed","Stable altitude and low vertical speed; tracking waypoint 1."),
            new ExplainabilityModel.Event("10:42:00",ExplainabilityModel.Level.NOMINAL,"Automatic flight started","Aircraft checks passed; vertical takeoff initiated."));
        return new ExplainabilityModel.Frame(snapshot,base.phase(),base.active(),43,home,route,out,base.level(),45,
            base.limitUse(),base.limitMargin(),base.trackingError(),.16,base.headline(),base.rationale(),base.recommendation(),
            base.factors(),samples,events,"OFFLINE PREVIEW — synthetic telemetry, not a live flight",base.limits(),base.battery());
    }
    static NativeLink.Position position(double lat,double lon,double elevation,double agl,double pitch,double roll,
                                        double heading,double east,double up,double south) {
        ByteBuffer b=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.putDouble(lon).putDouble(lat).putDouble(elevation);
        for(double value:new double[]{agl,pitch,heading,roll,east,up,south,0,0,0})b.putFloat((float)value);
        b.flip();return new NativeLink.Position(b);
    }
}
