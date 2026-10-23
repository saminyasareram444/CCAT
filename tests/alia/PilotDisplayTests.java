package alia;

import java.util.*;

/** Alert priority, optional weather and pilot-safe navigation semantics. No aircraft connection. */
final class PilotDisplayTests {
    private static int checks;
    private static void check(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
    static int run() {
        var site=new AirportDatabase.Site("Runway 4/22 midpoint",41.5144,-74.2647,22,1527,30.5,1);
        var airport=new AirportDatabase.Airport("KMGJ","Orange County",site.latitude(),site.longitude(),112.8,"Airport",List.of(site));
        var plan=new AirportPlan(airport,site,300,90);var frame=PilotPreview.sample(plan,false);var s=frame.telemetry();
        check(PilotDisplayModel.alert(frame).tone()==PilotDisplayModel.Tone.NORMAL,"fresh nominal flight has no enlarged caution");
        check(PilotDisplayModel.decision(frame).action().equals("Following the route"),"pilot intent follows the executed cruise stage");
        check(PilotDisplayModel.decision(frame).reason().indexOf("confidence")==-1,"pilot intent invents no model confidence");
        check(Math.abs(PilotDisplayModel.distance(frame,plan)-plan.distance(s.pos))<.001,"pilot distance is to destination, not the controller's current leg");
        check(!PilotDisplayModel.eta(frame,plan).equals("—"),"ETA uses live ground speed and is explicitly an estimate");
        check(PilotDisplayModel.alert(null).title().equals("CONNECTING"),"startup has no fabricated flight values or critical fault");
        check(Double.isNaN(PilotDisplayModel.distance(null,plan)),"unknown current position has no invented distance");
        PilotWeather weather=PilotWeather.from(s);
        check(weather.complete()&&Math.abs(weather.windKnots()-10)<.001,"effective local wind is converted from m/s to knots");
        check(weather.windFromTrue()==240&&weather.visibilitySm()==12&&weather.temperatureC()==18&&weather.rainRatio()==0,"weather units and measured zero rainfall are retained");
        check(NativeLink.REFS[NativeLink.WEATHER_OFFSET].endsWith("wind_now_direction_degt"),"wind comes from effective aircraft conditions, not an arbitrary weather layer");
        check(NativeLink.REFS[NativeLink.WEATHER_OFFSET+1].endsWith("wind_now_speed_msc"),"weather subscription uses documented m/s wind dataref");
        long[] times=s.times.clone();Arrays.fill(times,NativeLink.WEATHER_OFFSET,times.length,0);
        var noWeather=new NativeLink.Snapshot(s.pos,s.refs.clone(),times);
        check(noWeather.fresh()&&!PilotWeather.from(noWeather).complete(),"missing optional weather never breaks aircraft telemetry");
        check(Double.isNaN(PilotWeather.from(noWeather).windKnots()),"missing wind is not presented as calm");
        check(PilotDisplayModel.alert(with(frame,noWeather,ExplainabilityModel.Level.NOMINAL,"Automatic flight: CRUISE",true)).tone()==PilotDisplayModel.Tone.NORMAL,"unconnected optional feeds do not cause an unnecessary master caution");
        times=s.times.clone();times[NativeLink.WEATHER_OFFSET+2]=System.nanoTime()-3_000_000_000L;
        var partial=PilotWeather.from(new NativeLink.Snapshot(s.pos,s.refs.clone(),times));
        check(partial.windAvailable()&&Double.isNaN(partial.visibilitySm()),"stale weather fields are hidden individually without hiding fresh wind");
        double[] refs=s.refs.clone();refs[NativeLink.WEATHER_OFFSET+1]=-1;refs[NativeLink.WEATHER_OFFSET+4]=1.5;
        var invalid=PilotWeather.from(new NativeLink.Snapshot(s.pos,refs,s.times));
        check(!invalid.windAvailable()&&Double.isNaN(invalid.rainRatio()),"out-of-range weather cannot be displayed as valid");
        refs=s.refs.clone();refs[0]=1;
        var paused=with(frame,new NativeLink.Snapshot(s.pos,refs,s.times),ExplainabilityModel.Level.PAUSED,"X-Plane paused",true);
        check(PilotDisplayModel.alert(paused).tone()==PilotDisplayModel.Tone.INFO&&PilotDisplayModel.decision(paused).action().contains("paused"),"pause is informational and does not explain a stale control step");
        check(PilotDisplayModel.eta(paused,plan).equals("—"),"paused simulation does not show a running arrival estimate");
        var fault=with(frame,new NativeLink.Snapshot(s.pos,refs,s.times),ExplainabilityModel.Level.CRITICAL,"Flight fault: Control response mismatch",false);
        check(PilotDisplayModel.alert(fault).tone()==PilotDisplayModel.Tone.WARNING&&PilotDisplayModel.alert(fault).detail().contains("Control response mismatch"),"actual flight fault takes priority over paused status and preserves its reason");
        check(PilotDisplayModel.decision(fault).action().equals("TAKE OVER"),"fault explanation prioritizes pilot action over an old cruise trace");
        times=s.times.clone();Arrays.fill(times,0,NativeLink.CORE_COUNT,System.nanoTime()-2_000_000_000L);
        var stale=with(frame,new NativeLink.Snapshot(s.pos,s.refs.clone(),times),ExplainabilityModel.Level.NOMINAL,"Automatic flight: CRUISE",true);
        check(PilotDisplayModel.alert(stale).tone()==PilotDisplayModel.Tone.WARNING&&PilotDisplayModel.alert(stale).action().contains("take over"),"UI independently detects stale telemetry even if the worker's last frame was nominal");
        check(Double.isNaN(PilotDisplayModel.distance(stale,plan))&&PilotDisplayModel.eta(stale,plan).equals("—"),"stale position cannot produce live navigation metrics");
        check(PilotDisplayModel.decision(stale).action().contains("Awaiting"),"stale telemetry cannot continue a live cruise explanation");
        refs=s.refs.clone();refs[13]=0;
        check(PilotDisplayModel.alert(with(frame,new NativeLink.Snapshot(s.pos,refs,s.times),ExplainabilityModel.Level.NOMINAL,"Automatic flight: CRUISE",true)).title().equals("POWER FAULT"),"battery switch fault receives a prominent action alert");
        refs=s.refs.clone();refs[23]=refs[22]*.18;
        var low=with(frame,new NativeLink.Snapshot(s.pos,refs,s.times),ExplainabilityModel.Level.CAUTION,"Automatic flight: CRUISE",true);
        check(PilotDisplayModel.alert(low).title().equals("LOW BATTERY"),"weakest pack, not average charge, drives reserve caution");
        refs[23]=refs[22]*.14;
        var reserve=with(frame,new NativeLink.Snapshot(s.pos,refs,s.times),ExplainabilityModel.Level.CRITICAL,"Automatic flight: CRUISE",true);
        check(PilotDisplayModel.alert(reserve).tone()==PilotDisplayModel.Tone.WARNING&&PilotDisplayModel.alert(reserve).title().equals("BATTERY RESERVE"),"reserve breach escalates the visible caution");
        var expected=plan.points(frame.origin()).getLast();var actual=frame.route().getLast();
        check(frame.telemetry().refs[0]==0&&frame.telemetry().refs[13]==1&&actual.north==expected.north&&actual.east==expected.east,"pilot processing does not mutate source telemetry or the active route");
        System.out.println("PASS: "+checks+" pilot display checks (alert priority, stale data, navigation semantics, optional measured weather).");return checks;
    }
    private static ExplainabilityModel.Frame with(ExplainabilityModel.Frame f,NativeLink.Snapshot s,ExplainabilityModel.Level level,String event,boolean active) {
        return new ExplainabilityModel.Frame(s,f.phase(),active,f.elapsed(),f.origin(),f.route(),f.output(),level,f.ageMs(),f.limitUse(),f.limitMargin(),f.trackingError(),f.residual(),f.headline(),f.rationale(),f.recommendation(),f.factors(),f.samples(),f.events(),event,f.limits(),BatteryStatus.from(s));
    }
}
