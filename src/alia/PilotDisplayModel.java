package alia;

import java.util.Locale;

/** Pilot wording and alert priority derived from measured state and executed stage. */
final class PilotDisplayModel {
    enum Tone { NORMAL, INFO, CAUTION, WARNING }
    record Alert(Tone tone,String title,String detail,String action) {}
    record Decision(String action,String reason,String next) {}
    static Alert alert(ExplainabilityModel.Frame f) {
        if(f==null)return new Alert(Tone.INFO,"CONNECTING","Waiting for X-Plane telemetry.","Load ALIA and connect CCAT.");
        var s=f.telemetry();String event=f.systemEvent()==null?"":f.systemEvent();
        if(event.startsWith("Flight fault:"))return new Alert(Tone.WARNING,"TAKE OVER",event.substring("Flight fault:".length()).strip(),"Simulation pause requested. Check X-Plane before taking control.");
        if(!s.fresh())return new Alert(f.active()?Tone.WARNING:Tone.CAUTION,"DATA UNAVAILABLE","Aircraft readings are missing or out of date.",f.active()?"Pause and take over. Verify aircraft state in X-Plane.":"Do not rely on the last displayed position or flight values.");
        if(!s.alia())return new Alert(Tone.CAUTION,"CHECK AIRCRAFT","The loaded aircraft is not confirmed as ALIA-250.","Load ALIA before engaging automatic flight.");
        if(f.active())for(int i=13;i<=17;i++)if(s.refs[i]!=1)return new Alert(Tone.WARNING,"POWER FAULT","An aircraft battery is switched off.","Pause and take over.");
        var b=f.battery();
        if(f.active()&&b.chargeAvailable()&&b.minimumPercent()<15)return new Alert(Tone.WARNING,"BATTERY RESERVE",String.format(Locale.ROOT,"Lowest battery pack %.0f%%.",b.minimumPercent()),"Pause and take over. Review landing options.");
        if(f.level()==ExplainabilityModel.Level.CRITICAL)return new Alert(Tone.WARNING,"TAKE OVER","A flight fault or configured flight limit needs attention.","Pause and take over. Inspect the flight-controls message.");
        if(s.refs[0]!=0)return new Alert(Tone.INFO,"SIMULATION PAUSED","The automatic controller is not advancing.","Review aircraft state before resuming X-Plane.");
        if(b.chargeAvailable()&&b.minimumPercent()<20)return new Alert(Tone.CAUTION,"LOW BATTERY",String.format(Locale.ROOT,"Lowest battery pack %.0f%%.",b.minimumPercent()),"Review the remaining route and landing options.");
        if(f.level()==ExplainabilityModel.Level.CAUTION) {
            if(f.ageMs()>250)return new Alert(Tone.CAUTION,"TELEMETRY DELAY","Aircraft updates are delayed.","Monitor the connection; pause if readings stop updating.");
            if(f.trackingError()>.20)return new Alert(Tone.CAUTION,"CONTROL RESPONSE","Motor settings differ from controller commands.","Check for competing control inputs. Be ready to take over.");
            return new Alert(Tone.CAUTION,"FLIGHT MARGIN", "The aircraft is approaching a configured flight limit.","Monitor the response. Pause and take over if it diverges.");
        }
        return new Alert(Tone.NORMAL,"MONITORING",f.active()?"Automatic flight active":"Automatic flight inactive","");
    }
    static Decision decision(ExplainabilityModel.Frame f) {
        Alert alert=alert(f);
        if(f==null||!f.telemetry().fresh())return new Decision("Awaiting live data","A current aircraft sample is required.","Check the connection.");
        if(alert.tone()==Tone.WARNING)return new Decision(alert.title(),alert.detail(),alert.action());
        if(f.telemetry().refs[0]!=0)return new Decision("Simulation paused","The controller is not issuing new flight steps.","Review the alert before resuming.");
        if(f.phase().equals("LANDED"))return new Decision("Landing complete","Ground contact is confirmed and motors are at idle.","Automatic flight has released the controls.");
        if(!f.active()||f.output()==null||f.output().trace==null)return new Decision("Ready for mission review","Automatic flight is inactive.","Choose a mission in Flight controls.");
        var t=f.output().trace;
        return switch(t.computedPhase) {
            case TAKEOFF -> new Decision("Climbing vertically","Establishing a stable departure before forward flight.",String.format(Locale.ROOT,"Stabilize near %.0f ft above departure.",t.target.height*3.28084));
            case TRANSITION -> new Decision("Accelerating to cruise","The pusher is accelerating the aircraft as wing support increases.","Reach the transition speed and height before cruise.");
            case CRUISE -> new Decision("Following the route","Tracking the selected destination and cruise height.","Slow down before the destination for rotor landing.");
            case APPROACH -> new Decision("Slowing for landing","Reducing pusher power and restoring lift-rotor support.","Reach low forward speed before final capture.");
            case HOVER_CAPTURE -> new Decision("Aligning over the landing site","Position and speed must settle before descent.","Hold within 2.5 m of the selected landing point.");
            case DESCEND -> new Decision(f.output().distance>2.5?"Holding descent":"Landing vertically","Keeping the aircraft centred over the landing point.","Ground contact completes the landing.");
            case WAYPOINTS -> new Decision("Following the next waypoint","Using lift rotors to track the local route.","Stabilize at the waypoint before advancing.");
            case RETURN -> new Decision("Returning to departure","The local hover mission returns to its start point.","Stabilize over home before descent.");
            default -> new Decision("Mission inactive","No automatic control step is running.","Review the aircraft in Flight controls.");
        };
    }
    static double distance(ExplainabilityModel.Frame f,AirportPlan plan) {
        if(f==null||!f.telemetry().fresh()||plan==null)return Double.NaN;
        return plan.distance(f.telemetry().pos);
    }
    static String eta(ExplainabilityModel.Frame f,AirportPlan plan) {
        double distance=distance(f,plan);if(!Double.isFinite(distance))return "—";
        double gs=Math.hypot(f.telemetry().pos.east,f.telemetry().pos.south);if(gs<2||f.telemetry().refs[0]!=0)return "—";
        long seconds=Math.round(distance/gs);return seconds>=3600?String.format(Locale.ROOT,"%dh %02dm",seconds/3600,seconds%3600/60):String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
    }
}
