package alia;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Measured telemetry + deterministic controller traces. No learned confidence or independent sensors are inferred. */
final class ExplainabilityModel implements AutoCloseable {
    enum Level { WAITING, NOMINAL, CAUTION, CRITICAL, PAUSED }
    record Event(String time,Level level,String title,String detail) {}
    record Sample(double seconds,double north,double east,double height,double targetHeight,
                  double pitch,double roll,double lift,double actualLift,double residual,double age,
                  double batteryPercent,double batteryPowerKw) {}
    record Factor(String name,double value,String explanation) {}
    record Limits(String destination,double radius,double minimumHeight,double maximumHeight) {}
    record Frame(NativeLink.Snapshot telemetry,String phase,boolean active,double elapsed,
                 NativeLink.Position origin,List<HoverMission.Point> route,HoverMission.Output output,
                 Level level,double ageMs,double limitUse,double limitMargin,double trackingError,
                 double residual,String headline,String rationale,String recommendation,List<Factor> factors,
                 List<Sample> samples,List<Event> events,String systemEvent,Limits limits,BatteryStatus battery) {}

    private final Deque<Sample> samples=new ArrayDeque<>();
    private final Deque<Event> events=new ArrayDeque<>();
    private NativeLink.Position monitorOrigin,previousPosition;
    private double residual=Double.NaN;
    private long lastSample,start=System.nanoTime();
    private String previousConnection="",previousPhase="",previousSystemEvent="";
    private Level previousLevel;
    private PrintWriter journal;
    private boolean journalAttempted;
    private String journalError="";
    private final boolean record;
    private final Path journalFolder;
    private NativeLink.Position lastHome;
    private String previousTransition="";

    ExplainabilityModel(){this(true);}
    ExplainabilityModel(boolean record){this(record,Paths.get("logs","alia"));}
    ExplainabilityModel(boolean record,Path journalFolder){this.record=record;this.journalFolder=journalFolder;}

    synchronized Frame update(NativeLink.Snapshot s,String phase,boolean active,double elapsed,
                              NativeLink.Position home,List<HoverMission.Point> route,
                              HoverMission.Output output,String systemEvent) {
        return update(s,phase,active,elapsed,home,route,output,systemEvent,null);
    }
    synchronized Frame update(NativeLink.Snapshot s,String phase,boolean active,double elapsed,
                              NativeLink.Position home,List<HoverMission.Point> route,
                              HoverMission.Output output,String systemEvent,MissionController controller) {
        long now=System.nanoTime();boolean live=s.fresh();
        Limits limits=controller==null?new Limits("Hover test / return to start",650,-5,85):
            new Limits(controller.destination(),controller.radiusLimit(),controller.minimumHeight(),controller.maximumHeight());
        BatteryStatus battery=BatteryStatus.from(s);
        if(home!=null&&home!=lastHome){samples.clear();previousPosition=null;residual=Double.NaN;lastHome=home;}
        if(monitorOrigin==null&&s.pos!=null)monitorOrigin=s.pos;
        NativeLink.Position origin=home!=null?home:monitorOrigin;
        if(live&&s.pos!=null&&s.pos!=previousPosition) {
            if(previousPosition!=null) {
                double dt=(s.pos.received-previousPosition.received)/1e9;
                if(dt>.005&&dt<1) {
                    double dn=north(s.pos,previousPosition),de=east(s.pos,previousPosition);
                    residual=Math.hypot(dn+previousPosition.south*dt,de-previousPosition.east*dt);
                }else residual=Double.NaN;
            }
            previousPosition=s.pos;
        }
        double age=ageMs(s,now),use=Double.NaN,margin=Double.NaN,tracking=Double.NaN;
        if(live&&s.pos!=null) {
            use=Math.max(Math.abs(s.pos.pitch)/35,Math.abs(s.pos.roll)/35);
            if(home!=null) {
                double height=s.pos.elevation-home.elevation;
                use=Math.max(use,Math.max(height/limits.maximumHeight(),height<0?-height/Math.abs(limits.minimumHeight()):0));
                use=Math.max(use,Math.hypot(north(s.pos,home),east(s.pos,home))/limits.radius());
            }
            margin=Math.max(0,1-use);
            if(active&&output!=null) {
                tracking=0;
                for(int i=5;i<=8;i++)tracking=Math.max(tracking,Math.abs(output.lift-s.refs[i]));
                tracking=Math.max(tracking,Math.abs(output.pusher-s.refs[9]));
            }
        }
        Level level=!live?Level.WAITING:s.refs[0]!=0?Level.PAUSED:!s.alia()?Level.CAUTION:
            use>=1?Level.CRITICAL:use>=.75||age>250||active&&tracking>.20?Level.CAUTION:Level.NOMINAL;
        for(int i=13;i<=17;i++)if(live&&active&&s.refs[i]!=1)level=Level.CRITICAL;
        if(live&&level==Level.NOMINAL&&battery.chargeAvailable()&&battery.minimumPercent()<20)level=Level.CAUTION;
        if(live&&active&&battery.chargeAvailable()&&battery.minimumPercent()<15)level=Level.CRITICAL;
        if(systemEvent!=null&&(systemEvent.startsWith("Flight fault:")||systemEvent.contains("lost")||systemEvent.contains("exceeded")||systemEvent.contains("timed out")))
            level=Level.CRITICAL;
        String connection=live?"LIVE":"STALE";
        if(!connection.equals(previousConnection)) {
            event(live?Level.NOMINAL:Level.WAITING,live?"Telemetry connected":"Telemetry unavailable",
                live?"Position and all required aircraft values are fresh.":"No complete sample within 1 second. Flight start remains blocked.");
            previousConnection=connection;
        }
        if(!phase.equals(previousPhase)) {
            event(Level.NOMINAL,"Flight stage: "+phase,output!=null&&output.trace!=null&&!output.trace.transition.isEmpty()
                ?output.trace.transition:"Current flight application stage.");previousPhase=phase;
        }
        if(output!=null&&output.trace!=null&&!output.trace.transition.isEmpty()&&!output.trace.transition.equals(previousTransition)) {
            event(Level.NOMINAL,"Controller stage gate passed",output.trace.transition);previousTransition=output.trace.transition;
        }
        if(level!=previousLevel) {
            event(level,"Runtime status: "+level,switch(level) {
                case WAITING -> "Waiting for complete telemetry; values on screen may be old.";
                case PAUSED -> "X-Plane is paused. Controller time does not advance.";
                case CAUTION -> battery.chargeAvailable()&&battery.minimumPercent()<20?
                    String.format(Locale.ROOT,"Weakest battery pack is %.1f%%, below the 20%% dashboard caution threshold.",battery.minimumPercent()):
                    "Check aircraft identity, sample age, configured-limit use and command tracking.";
                case CRITICAL -> battery.chargeAvailable()&&battery.minimumPercent()<15?
                    String.format(Locale.ROOT,"Weakest battery pack is %.1f%%, below the airport mission's 15%% reserve guard.",battery.minimumPercent()):
                    "A configured limit, power condition or reported flight fault needs attention.";
                default -> "Fresh data; monitored quantities are within their configured thresholds.";
            });previousLevel=level;
        }
        if(systemEvent!=null&&!systemEvent.equals(previousSystemEvent)) {
            if(!systemEvent.startsWith("Automatic flight:")&&!systemEvent.startsWith("Waiting for"))
                event(level,"Flight application",systemEvent);
            previousSystemEvent=systemEvent;
        }
        String headline,rationale,recommendation;
        List<Factor> factors=new ArrayList<>();
        if(!live) {
            headline="Waiting for a fresh aircraft sample";
            rationale="Explanations are suspended because telemetry is missing or older than 1 second.";
        } else if(s.refs[0]!=0) {
            headline="Simulation paused";rationale="The flight controller is not advancing. Any displayed command trace is the last completed step.";
        } else if(!active||output==null||output.trace==null) {
            headline="Monitoring aircraft; automatic control is inactive";
            rationale="No current automatic control step is available to explain. Start a mission in the flight-controls window to see exact decision terms.";
        } else {
            HoverMission.Trace t=output.trace;Map<String,Double> v=t.terms;
            headline=switch(t.computedPhase) {
                case TAKEOFF -> "Climb vertically and stabilize over the takeoff point";
                case WAYPOINTS -> "Track waypoint "+(t.computedWaypoint+1)+" using rotor power";
                case RETURN -> "Return to the takeoff location before descent";
                case TRANSITION -> "Accelerate with the pusher and transfer lift to the wings";
                case CRUISE -> "Cruise toward the selected destination airport";
                case APPROACH -> "Reduce pusher power, slow down and restore lift-rotor support";
                case HOVER_CAPTURE -> "Capture the destination landing point before descent";
                case DESCEND -> output.distance>2.5||Math.hypot(t.state.vNorth,t.state.vEast)>.9
                    ?"Hold descent until position and speed are stable":"Descend slowly over the takeoff point";
                default -> "Automatic control step";
            };
            rationale=String.format(Locale.ROOT,
                "Target height %.1f m; current height %.1f m. Desired climb %+.2f m/s versus measured %+.2f m/s. Lift command %.1f%% after output and ramp limits.",
                t.target.height,t.state.height,v.get("targetClimb"),t.state.vUp,output.lift*100);
            double rotorShare=t.computedPhase==HoverMission.Phase.CRUISE?0:1-v.getOrDefault("wingSupportFraction",0.0);
            if(v.containsKey("wingSupportFraction"))rationale=String.format(Locale.ROOT,
                "IAS %.1f kt; cruise setting %.0f kt. Target height %.1f m above origin; measured %.1f m. Lift %.1f%%, pusher %.1f%%. Rotor support blend %.0f%% before rate limits.",
                v.get("airspeedKnots"),v.get("targetAirspeedKnots"),t.target.height,t.state.height,output.lift*100,output.pusher*100,rotorShare*100);
            if(v.containsKey("forwardAirspeedKnots")&&v.get("airspeedKnots")<0)
                rationale+=" Signed IAS is below zero; forward-speed scheduling uses 0 kt.";
            if(t.computedPhase==HoverMission.Phase.APPROACH&&v.containsKey("approachSpeedTarget"))
                rationale+=String.format(Locale.ROOT," Braking speed target %.1f m/s; signed distance along approach %.0f m. Height is held until slow.",v.get("approachSpeedTarget"),v.get("approachAlongRemaining"));
            factors.add(new Factor("Base hover contribution",v.get("baseLift")*rotorShare,"Operator hover setting × rotor-support share; output limits may apply."));
            factors.add(new Factor("Vertical-speed correction",v.get("verticalP")*rotorShare,"0.10 × (desired climb − measured climb) × rotor share."));
            factors.add(new Factor("Accumulated correction",v.get("verticalI")*rotorShare,"Integrated climb error × rotor share; output limits may apply."));
            factors.add(new Factor("Pitch proportional term",v.get("pitchP"),"0.075 × (target pitch − measured pitch)."));
            factors.add(new Factor("Pitch rate damping",v.get("pitchD"),"−0.025 × measured pitch rate."));
            factors.add(new Factor("Roll proportional term",v.get("rollP"),"0.075 × (target roll − measured roll)."));
            factors.add(new Factor("Roll rate damping",v.get("rollD"),"−0.025 × measured roll rate."));
            factors.add(new Factor("Heading correction",v.get("yawP"),"0.025 × wrapped heading error."));
            factors.add(new Factor("Yaw rate damping",v.get("yawD"),"−0.018 × measured yaw rate."));
        }
        recommendation=switch(level) {
            case WAITING -> "Keep monitoring. Verify X-Plane is loaded and reconnect if needed.";
            case PAUSED -> "Review the readings before unpausing X-Plane. This dashboard does not resume a flight.";
            case CAUTION -> "Review the highlighted measurements. Pause and take over if the aircraft is diverging.";
            case CRITICAL -> "Use Pause & take over and inspect the flight application's fault message.";
            default -> active?"Monitor the next stage gate and the measured response. No operator intervention is currently requested."
                :TelemetryView.readiness(s);
        };
        if(now-lastSample>=200_000_000L) {
            if(live&&origin!=null) {
                double target=output!=null&&output.trace!=null?output.trace.target.height:Double.NaN;
                samples.addLast(new Sample((now-start)/1e9,north(s.pos,origin),east(s.pos,origin),s.pos.elevation-origin.elevation,
                    target,s.pos.pitch,s.pos.roll,output!=null&&active?output.lift:Double.NaN,s.refs[5],residual,age,
                    battery.chargePercent(),battery.powerKw()));
                while(samples.size()>600)samples.removeFirst();
            }
            lastSample=now;
            if(live&&record)writeJournal(phase,level,s,output,age,use,tracking,limits,home);
        }
        return new Frame(s,phase,active,elapsed,origin,List.copyOf(route),output,level,age,use,margin,tracking,residual,
            headline,rationale,recommendation,List.copyOf(factors),List.copyOf(samples),List.copyOf(events),
            systemEvent+(journalError.isEmpty()?"":" | Explainability log unavailable: "+journalError),limits,battery);
    }
    private void event(Level level,String title,String detail) {
        events.addFirst(new Event(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),level,title,detail));
        while(events.size()>100)events.removeLast();
        if(journal!=null) {journal.println("{\"type\":\"event\",\"time\":"+quote(Instant.now().toString())+
            ",\"level\":"+quote(level.name())+",\"title\":"+quote(title)+",\"detail\":"+quote(detail)+"}");journal.flush();}
    }
    synchronized void clearEvents() {events.clear();event(Level.NOMINAL,"History cleared","Dashboard history cleared by the operator.");}
    private void writeJournal(String phase,Level level,NativeLink.Snapshot s,HoverMission.Output out,double age,double use,double tracking,Limits limits,NativeLink.Position home) {
        if(!journalAttempted) {
            journalAttempted=true;
            try {
                Path folder=journalFolder;Files.createDirectories(folder);
                journal=new PrintWriter(Files.newBufferedWriter(folder.resolve("xai-"+
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))+".jsonl")));
                journal.println("{\"type\":\"session\",\"position_source\":\"X-Plane native telemetry\",\"explanation_source\":\"executed deterministic controller trace\",\"calibrated_confidence_available\":false,\"independent_sensor_comparison_available\":false}");
                for(var e:events)journal.println("{\"type\":\"event\",\"time\":"+quote(e.time())+
                    ",\"level\":"+quote(e.level().name())+",\"title\":"+quote(e.title())+",\"detail\":"+quote(e.detail())+"}");
            }catch(IOException e){journalError=e.getMessage();}
        }
        if(journal==null)return;
        StringBuilder b=new StringBuilder("{\"type\":\"sample\",\"time\":").append(quote(Instant.now().toString()))
            .append(",\"phase\":").append(quote(phase)).append(",\"level\":").append(quote(level.name()))
            .append(",\"destination\":").append(quote(limits.destination()))
            .append(",\"radius_limit_m\":").append(number(limits.radius()))
            .append(",\"origin_lat\":").append(number(home==null?Double.NaN:home.lat))
            .append(",\"origin_lon\":").append(number(home==null?Double.NaN:home.lon))
            .append(",\"origin_elevation_m\":").append(number(home==null?Double.NaN:home.elevation))
            .append(",\"sample_age_ms\":").append(number(age)).append(",\"limit_use\":").append(number(use))
            .append(",\"tracking_error\":").append(number(tracking)).append(",\"lat\":").append(number(s.pos.lat))
            .append(",\"lon\":").append(number(s.pos.lon))
            .append(",\"elevation_m\":").append(number(s.pos.elevation)).append(",\"agl_m\":").append(number(s.pos.agl))
            .append(",\"pitch_deg\":").append(number(s.pos.pitch)).append(",\"roll_deg\":").append(number(s.pos.roll))
            .append(",\"north_mps\":").append(number(-s.pos.south)).append(",\"east_mps\":").append(number(s.pos.east))
            .append(",\"up_mps\":").append(number(s.pos.up)).append(",\"aircraft_values\":{");
        for(int i=0;i<s.refs.length;i++) {
            if(i>0)b.append(',');b.append(quote(NativeLink.REFS[i])).append(':').append(number(s.refs[i]));
        }
        b.append('}');
        if(out!=null&&out.trace!=null) {
            b.append(",\"computed_phase\":").append(quote(out.trace.computedPhase.name()))
                .append(",\"gate\":").append(quote(out.trace.gate))
                .append(",\"target_north_m\":").append(number(out.trace.target.north))
                .append(",\"target_east_m\":").append(number(out.trace.target.east))
                .append(",\"target_height_m\":").append(number(out.trace.target.height)).append(",\"terms\":{");
            boolean comma=false;
            for(var entry:out.trace.terms.entrySet()) {
                if(comma)b.append(',');comma=true;b.append(quote(entry.getKey())).append(':').append(number(entry.getValue()));
            }
            b.append('}');
        }
        journal.println(b.append('}'));journal.flush();
        if(journal.checkError())journalError="Could not write the explainability journal";
    }
    static double ageMs(NativeLink.Snapshot s,long now) {
        if(s.pos==null)return Double.NaN;long oldest=s.pos.received;
        for(int i=0;i<NativeLink.CORE_COUNT;i++){long time=s.times[i];if(time==0)return Double.NaN;oldest=Math.min(oldest,time);}
        return Math.max(0,(now-oldest)/1e6);
    }
    static double north(NativeLink.Position p,NativeLink.Position origin) {return Math.toRadians(p.lat-origin.lat)*6371000;}
    static double east(NativeLink.Position p,NativeLink.Position origin) {
        return Math.toRadians(HoverMission.wrap(p.lon-origin.lon))*6371000*Math.cos(Math.toRadians(origin.lat));
    }
    static String number(double n) {return Double.isFinite(n)?Double.toString(n):"null";}
    static String quote(String s) {
        StringBuilder b=new StringBuilder("\"");
        for(char ch:s.toCharArray())switch(ch) {
            case '\\' -> b.append("\\\\");case '"' -> b.append("\\\"");
            case '\n' -> b.append("\\n");case '\r' -> b.append("\\r");case '\t' -> b.append("\\t");
            default -> {if(ch<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)ch));else b.append(ch);}
        }
        return b.append('"').toString();
    }
    @Override public synchronized void close() {if(journal!=null)journal.close();}
}
