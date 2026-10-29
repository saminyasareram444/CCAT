package alia;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Standalone ALIA mode; deliberately does not start the legacy Soar agents. */
public final class AliaFlight {
    static final String PITCH="sim/joystick/yoke_pitch_ratio";
    static final String ROLL="sim/joystick/yoke_roll_ratio";
    static final String YAW="sim/joystick/yoke_heading_ratio";
    static final String THROTTLE="sim/cockpit2/engine/actuators/throttle_ratio";
    static final String[] OVERRIDES={NativeLink.REFS[10],NativeLink.REFS[11],NativeLink.REFS[12]};
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"alia-flight");t.setDaemon(true);return t;
    });
    private final JFrame frame=new JFrame("CCAT | ALIA mission flight (experimental)");
    private final JTextArea route=new JTextArea("0, 60, 15\n40, 60, 15\n0, 0, 15",5,30);
    private final JTextArea status=new JTextArea(12,64);
    private final JTextField speed=new JTextField("3",4),hover=new JTextField("0.60",5);
    private final JLabel message=new JLabel("Connect to X-Plane to read aircraft data. No controls are sent on connection.");
    private volatile NativeLink link;
    private MissionController mission;
    private NativeLink.Position home;
    private boolean ownsControls;
    private PrintWriter log;
    private String event="Disconnected";
    private double elapsed;
    private long lastTick=System.nanoTime(),lastSubscription;
    private double desiredLift;
    private int readbackMismatch;
    private final TelemetryView telemetry;
    private final ExplainabilityModel explainability=new ExplainabilityModel();
    private final ExplainabilityDashboard dashboard;
    private final MissionPlannerView planner;
    private final PilotDisplay pilot;
    private AirportPlan selectedAirportPlan;
    private final JLabel missionMode=new JLabel("Mode: local hover test / return to start");
    private HoverMission.Output lastOutput;
    private long lastDashboardUpdate;
    private double desiredPusher;
    private BrakingShadow shadow;
    private SensorResearch sensorResearch;
    private String researchRunId="UNASSIGNED";

    private AliaFlight(boolean openTelemetry,boolean openDashboard,boolean openPilot,boolean openShadow,boolean openSensorLab) {
        if(openShadow)shadow=new BrakingShadow(Paths.get("research/local/models/braking.properties"),Paths.get("research/local/shadow"));
        telemetry=new TelemetryView();
        planner=new MissionPlannerView(()->{NativeLink current=link;return current==null?null:current.snapshot();},
            plan->worker.execute(()->useAirportPlan(plan)));
        pilot=new PilotDisplay(()->worker.execute(()->{try{stop("Paused for manual takeover",true);}catch(IOException ex){fault(ex);}}),
            ()->{frame.setVisible(true);frame.toFront();},planner::installationRoot);
        if(openSensorLab){
            try{sensorResearch=(SensorResearch)Class.forName("alia.SoarSensorResearch").getConstructor().newInstance();pilot.sensorResearch(sensorResearch);pilot.showPage("Sensors");}
            catch(ReflectiveOperationException ex){JOptionPane.showMessageDialog(frame,"Use run-soar-demo.cmd -Live to load the research runtime. "+ex.getMessage());}
        }
        dashboard=new ExplainabilityDashboard(()->{frame.setVisible(true);frame.toFront();},
            ()->telemetry.openBeside(frame),
            ()->worker.execute(()->{try{stop("Paused for manual takeover",true);}catch(IOException ex){fault(ex);}}),
            ()->worker.execute(explainability::clearEvents),()->planner.open(),pilot::open);
        status.setEditable(false);status.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
        route.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
        JPanel settings=new JPanel(new BorderLayout(8,8));
        settings.setBorder(BorderFactory.createTitledBorder("Waypoints: north metres, east metres, height above start metres"));
        settings.add(new JScrollPane(route),BorderLayout.CENTER);
        JPanel numbers=new JPanel();numbers.add(new JLabel("Hover-test speed (m/s):"));numbers.add(speed);
        numbers.add(new JLabel("Initial hover throttle:"));numbers.add(hover);settings.add(numbers,BorderLayout.SOUTH);
        JPanel actions=new JPanel();
        addButton(actions,"Connect / monitor",()->connect());
        addButton(actions,"Start automatic flight",()->start());
        addButton(actions,"Pause & take over",()->stop("Paused for manual takeover",true));
        JButton showTelemetry=new JButton("Show telemetry");actions.add(showTelemetry);
        showTelemetry.addActionListener(e->telemetry.openBeside(frame));
        JButton showDashboard=new JButton("Live dashboard");actions.add(showDashboard);
        showDashboard.addActionListener(e->dashboard.open());
        JButton showPilot=new JButton("Pilot display");actions.add(showPilot);showPilot.addActionListener(e->pilot.open());
        JButton showShadow=new JButton("Braking shadow");actions.add(showShadow);showShadow.addActionListener(e->worker.execute(()->{
            if(shadow==null)shadow=new BrakingShadow(Paths.get("research/local/models/braking.properties"),Paths.get("research/local/shadow"));
            SwingUtilities.invokeLater(shadow::open);
        }));
        JButton planMission=new JButton("Mission planner");actions.add(planMission);planMission.addActionListener(e->planner.open());
        JButton hoverMode=new JButton("Use hover test");actions.add(hoverMode);hoverMode.addActionListener(e->worker.execute(()->{
            if(ownsControls){event="Stop the current mission before changing mission mode";return;}
            selectedAirportPlan=null;SwingUtilities.invokeLater(()->{missionMode.setText("Mode: local hover test / return to start");route.setEditable(true);route.setText("0, 0, 15");});
        }));
        JPanel content=new JPanel(new BorderLayout(10,10));content.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        JPanel top=new JPanel(new BorderLayout());
        top.add(missionMode,BorderLayout.NORTH);
        top.add(settings,BorderLayout.CENTER);top.add(actions,BorderLayout.SOUTH);
        content.add(top,BorderLayout.NORTH);content.add(new JScrollPane(status),BorderLayout.CENTER);
        content.add(message,BorderLayout.SOUTH);frame.setContentPane(content);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){worker.execute(()->{
            try {stop("Window closed",true);} catch(IOException ex) {System.err.println(ex.getMessage());} finally {
                if(link!=null)link.close();explainability.close();worker.shutdown();
                if(shadow!=null)shadow.close();
                if(sensorResearch!=null)sensorResearch.close();
                SwingUtilities.invokeLater(()->{pilot.close();planner.close();dashboard.close();telemetry.close();frame.dispose();});
            }
        });}});
        frame.pack();frame.setLocationRelativeTo(null);frame.setVisible(true);
        if(openTelemetry)telemetry.openBeside(frame);
        if(openDashboard)dashboard.open();
        if(openPilot)pilot.open();
        if(openShadow)shadow.open();
        if(openTelemetry||openDashboard||openPilot||openShadow) {
            // Monitoring only: subscribing does not send flight-control writes.
            worker.execute(()->{try{connect();}catch(IOException ex){fault(ex);}});
        }
        worker.scheduleAtFixedRate(this::tick,0,50,TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            if(link!=null && ownsControls) try {pauseAndRelease(link);}catch(IOException ignored){}
            if(log!=null)log.close();
            explainability.close();
        },"alia-release"));
    }
    private void addButton(JPanel p,String text,CheckedAction action) {
        JButton b=new JButton(text);p.add(b);b.addActionListener(e->{
            // Read all Swing input on the event thread; flight work stays on one worker thread.
            String routeText=route.getText(),speedText=speed.getText(),hoverText=hover.getText();
            pendingRoute=routeText;pendingSpeed=speedText;pendingHover=hoverText;
            worker.execute(()->{try{action.run();}catch(Exception ex){fault(ex);}});
        });
    }
    private volatile String pendingRoute,pendingSpeed,pendingHover;
    private interface CheckedAction {void run() throws Exception;}
    private void useAirportPlan(AirportPlan plan) {
        if(ownsControls){event="Stop the current mission before changing its destination";return;}
        selectedAirportPlan=plan;NativeLink.Snapshot s=link==null?null:link.snapshot();
        StringBuilder preview=new StringBuilder();
        if(s!=null&&s.pos!=null)for(var p:plan.points(s.pos))preview.append(String.format(Locale.ROOT,"%.1f, %.1f, %.1f%n",p.north,p.east,p.height));
        SwingUtilities.invokeLater(()->{missionMode.setText("Airport mission: "+plan.airport().id()+" / "+plan.site().name()+" — "+(int)plan.cruiseKnots()+" kt");route.setEditable(false);route.setText(preview.toString());});
        event="Destination selected: "+plan.label()+". Press Start automatic flight when ready.";
        try {
            Path folder=Paths.get("missions");Files.createDirectories(folder);
            Files.writeString(folder.resolve("airport-plan.json"),"{\"airport\":"+ExplainabilityModel.quote(plan.airport().id())+
                ",\"name\":"+ExplainabilityModel.quote(plan.airport().name())+",\"arrival\":"+ExplainabilityModel.quote(plan.site().name())+
                ",\"latitude\":"+plan.site().latitude()+",\"longitude\":"+plan.site().longitude()+",\"elevation_m\":"+plan.airport().elevationM()+
                ",\"cruise_clearance_m\":"+plan.cruiseClearanceM()+",\"cruise_knots\":"+plan.cruiseKnots()+
                ",\"surface\":"+ExplainabilityModel.quote(plan.site().surfaceName())+",\"length_m\":"+plan.site().lengthM()+",\"width_m\":"+plan.site().widthM()+
                ",\"scenery\":"+ExplainabilityModel.quote(plan.airport().scenery())+",\"source\":"+ExplainabilityModel.quote(plan.airport().source())+"}\n");
        }catch(IOException ex){event+=" Mission file could not be saved: "+ex.getMessage();}
    }
    private void connect() throws IOException {
        if(ownsControls) throw new IllegalStateException("Pause the active flight before reconnecting");
        if(link!=null)link.close();
        link=new NativeLink("127.0.0.1",49000);lastSubscription=System.nanoTime();
        event="Waiting for X-Plane on 127.0.0.1:49000";
    }
    static List<HoverMission.Point> parseRoute(String text) {
        List<HoverMission.Point> points=new ArrayList<>();
        int line=0;
        for(String row:text.split("\\R")) {
            line++;row=row.trim();if(row.isEmpty()||row.startsWith("#"))continue;
            String[] columns=row.split(",");
            if(columns.length!=3)throw new IllegalArgumentException("Waypoint line "+line+": enter north, east, height");
            points.add(new HoverMission.Point(Double.parseDouble(columns[0].trim()),
                Double.parseDouble(columns[1].trim()),Double.parseDouble(columns[2].trim())));
        }
        return points;
    }
    private void start() throws IOException {
        if(ownsControls)throw new IllegalStateException("A mission is already running");
        if(link==null)throw new IllegalStateException("Connect first");
        NativeLink.Snapshot s=link.snapshot();
        if(!s.fresh())throw new IllegalStateException("Missing or stale telemetry. Load ALIA and wait for READY");
        if(!s.alia())throw new IllegalStateException("Load the ALIA-250 (ICAO ALIA, five motors)");
        if(s.refs[0]!=0)throw new IllegalStateException("Unpause X-Plane before starting");
        if(s.refs[1]!=1||Math.hypot(s.pos.east,s.pos.south)>0.5||Math.abs(s.pos.up)>0.3)
            throw new IllegalStateException("Start stationary on the ground");
        if(Math.abs(s.pos.roll)>5||Math.abs(s.pos.pitch)>8)throw new IllegalStateException("Use a level takeoff area");
        for(int i=13;i<=17;i++)if(s.refs[i]!=1)throw new IllegalStateException("Start ALIA with all five batteries on");
        for(int i=5;i<=9;i++)if(s.refs[i]>0.05)throw new IllegalStateException("Set lift and pusher throttles to idle first");
        for(int i=10;i<=12;i++)if(s.refs[i]!=0)throw new IllegalStateException("Another controller holds the joystick override; release it first");
        MissionController next;
        if(selectedAirportPlan!=null) {
            BatteryStatus battery=BatteryStatus.from(s);
            if(!battery.chargeAvailable())throw new IllegalStateException("Wait for measured battery charge before starting an airport mission");
            if(battery.minimumPercent()<25)throw new IllegalStateException("Airport mission requires at least 25% charge in every pack");
            next=new AirportMission(selectedAirportPlan,s.pos,Double.parseDouble(pendingHover));
        }else next=new HoverMission(parseRoute(pendingRoute),s.pos.heading,Double.parseDouble(pendingHover),Double.parseDouble(pendingSpeed));
        Path folder=Paths.get("logs","alia");Files.createDirectories(folder);
        Path flightLog=folder.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))+".csv");
        log=new PrintWriter(Files.newBufferedWriter(flightLog));
        researchRunId=flightLog.getFileName().toString().replace(".csv","");
        try{ResearchRunMetadata.write(flightLog,selectedAirportPlan);}catch(Exception ex){System.err.println("Research metadata unavailable: "+ex.getMessage());}
        log.println("elapsed_s,phase,waypoint,latitude_deg,longitude_deg,north_m,east_m,height_m,agl_m,vnorth_mps,veast_mps,vup_mps,pitch_deg,roll_deg,pitch_control,roll_control,yaw_control,lift_command,lift_actual,pusher_actual,distance_m,airspeed_kt,pusher_command,battery_percent,minimum_pack_percent,battery_power_kw");
        home=s.pos;mission=next;elapsed=0;readbackMismatch=0;desiredLift=0;desiredPusher=0;
        lastOutput=null;
        // Mark ownership before any mutation so a partially failed start is also cleaned up.
        ownsControls=true;
        link.write("sim/cockpit/autopilot/autopilot_state",0);
        link.write("sim/cockpit2/autopilot/flight_director_mode",0);
        for(String ref:OVERRIDES)link.write(ref,1);
        link.write("sim/flightmodel/controls/parkbrake",0);
        for(int i=0;i<5;i++)link.write("sim/cockpit2/engine/actuators/prop_mode["+i+"]",1);
        next.start(0);event="Automatic flight started";
    }
    private void tick() {
        long now=System.nanoTime();double dt=Math.min((now-lastTick)/1e9,0.15);lastTick=now;
        try {
            if(link==null)return;
            NativeLink.Snapshot s=link.snapshot();
            if(!s.fresh()) {
                if(ownsControls)throw new IOException("Telemetry lost; pause requested and controls released");
                if(now-lastSubscription>3_000_000_000L){link.subscribe();lastSubscription=now;}
            } else if(ownsControls) {
                if(!s.alia())throw new IOException("Aircraft changed during flight");
                if(s.refs[0]!=0){event="X-Plane paused; mission clock stopped";show(s,null);return;}
                for(int i=13;i<=17;i++)if(s.refs[i]!=1)throw new IOException("Aircraft battery switched off");
                BatteryStatus battery=BatteryStatus.from(s);
                if(mission instanceof AirportMission&&(!battery.chargeAvailable()||battery.minimumPercent()<15))
                    throw new IOException("Airport mission battery reserve below 15% or charge telemetry unavailable; pause and take over");
                elapsed+=dt;
                double n=Math.toRadians(s.pos.lat-home.lat)*6371000;
                double e=Math.toRadians(HoverMission.wrap(s.pos.lon-home.lon))*6371000*Math.cos(Math.toRadians(home.lat));
                HoverMission.State state=new HoverMission.State(n,e,s.pos.elevation-home.elevation,s.pos.agl,
                    s.pos.pitch,s.pos.roll,s.pos.heading,-s.pos.south,s.pos.east,s.pos.up,s.pos.p,s.pos.q,s.pos.r,s.refs[1]==1,s.refs[2]);
                HoverMission.Output out=mission.update(state,elapsed);
                link.write(PITCH,out.pitch);link.write(ROLL,out.roll);link.write(YAW,out.yaw);
                // The ALIA custom collective callback drives lift motors 0..3. Write the actuator
                // array too, so the motor mapping is explicit. Airport missions also drive pusher 4.
                link.write("beta/alia_250/collective_ratio",out.lift);
                for(int i=0;i<4;i++)link.write(THROTTLE+"["+i+"]",out.lift);
                link.write(THROTTLE+"[4]",out.pusher);
                if(elapsed>3) {
                    boolean mismatch=false;
                    for(int i=5;i<=8;i++)mismatch|=Math.abs(s.refs[i]-desiredLift)>0.20;
                    mismatch|=Math.abs(s.refs[9]-desiredPusher)>0.20;
                    for(int i=10;i<=12;i++)mismatch|=s.refs[i]!=1;
                    readbackMismatch=mismatch?readbackMismatch+1:0;
                    if(readbackMismatch>20)throw new IOException("Control readback differs from commands; check competing joystick/automation inputs");
                }
                desiredLift=out.lift;
                desiredPusher=out.pusher;
                log.printf(Locale.ROOT,"%.3f,%s,%d,%.8f,%.8f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f,%.1f,%.4f,%.1f,%.1f,%.3f%n",
                    elapsed,out.phase,out.waypoint+1,s.pos.lat,s.pos.lon,n,e,state.height,s.pos.agl,
                    state.vNorth,state.vEast,state.vUp,s.pos.pitch,s.pos.roll,out.pitch,out.roll,out.yaw,out.lift,s.refs[5],s.refs[9],out.distance,
                    s.refs[2],out.pusher,battery.chargePercent(),battery.minimumPercent(),battery.powerKw());
                log.flush();
                event="Automatic flight: "+out.phase;
                show(s,out);
                if(out.phase==HoverMission.Phase.LANDED) {
                    link.write("sim/flightmodel/controls/parkbrake",1);
                    release(link);ownsControls=false;log.close();log=null;event="Landed. Controls released.";
                }
                return;
            }
            show(s,null);
        }catch(Exception e){fault(e);}
    }
    private void show(NativeLink.Snapshot s,HoverMission.Output out) {
        if(out!=null)lastOutput=out;
        if(shadow!=null){
            // The observer receives value-only inputs after the controller step. It cannot modify flight outputs.
            try {
                HoverMission.Trace trace=out==null?null:out.trace;
                BrakingModel.Observation observation=trace==null?null:new BrakingModel.Observation(
                    Math.hypot(trace.state.vNorth,trace.state.vEast),trace.state.airspeed,trace.state.vUp,trace.state.pitch,
                    out.lift,out.pusher,trace.terms.getOrDefault("phaseSeconds",Double.NaN));
                String computed=trace!=null&&out.phase==trace.computedPhase?trace.computedPhase.name():"";
                shadow.observe(new BrakingShadow.Sample(elapsed,ownsControls,s.fresh(),s.refs[0]!=0,mission instanceof AirportMission,
                    computed,mission instanceof AirportMission?AirportMission.BRAKING_POLICY_ID:"",observation,researchRunId));
            }catch(RuntimeException ex){System.err.println("Research observer unavailable: "+ex.getMessage());}
        }
        StringBuilder b=new StringBuilder();
        b.append("Connection: ").append(s.fresh()?"LIVE":"waiting / stale").append("\n");
        b.append("Aircraft:   ").append(s.alia()?"ALIA-250":"not confirmed").append("\n");
        b.append("Mission:    ").append(mission==null?"not started":mission.phase()).append("\n");
        if(s.pos!=null) {
            b.append(String.format(Locale.ROOT,"Position:   %.7f, %.7f\nAGL:        %.1f m     Vertical speed: %+.2f m/s\nPitch/roll: %+.1f / %+.1f deg\nLift:       %.2f    Pusher: %.2f\nPaused:     %s     On ground: %s\n",
                s.pos.lat,s.pos.lon,s.pos.agl,s.pos.up,s.pos.pitch,s.pos.roll,s.refs[5],s.refs[9],s.refs[0]==1,s.refs[1]==1));
        }
        if(out!=null)b.append(String.format(Locale.ROOT,"Target:     %s    distance %.1f m\nElapsed:    %.1f s\n",
            mission instanceof AirportMission?mission.destination():out.phase==HoverMission.Phase.WAYPOINTS?"waypoint "+(out.waypoint+1):"home",out.distance,elapsed));
        b.append("\nExperimental controller; actual X-Plane flight is unverified.\n");
        b.append("Keep X-Plane's pause key available during the first flight.");
        String display=b.toString(),notice=event;
        if(s.fresh()&&s.alia()&&!ownsControls&&mission==null&&event.startsWith("Waiting"))
            notice="READY — review the route, then start automatic flight.";
        final String label=notice;
        String telemetryText=TelemetryView.format(s,mission==null?"MONITORING":mission.phase().name(),elapsed,home,out);
        SwingUtilities.invokeLater(()->{status.setText(display);message.setText(label);telemetry.update(telemetryText);});
        long now=System.nanoTime();
        if(now-lastDashboardUpdate>=200_000_000L) {
            lastDashboardUpdate=now;
            ExplainabilityModel.Frame view=explainability.update(s,mission==null?"MONITORING":mission.phase().name(),
                ownsControls,elapsed,home,mission==null?Collections.emptyList():mission.route(),lastOutput,event,mission);
            AirportPlan displayPlan=selectedAirportPlan;
            SwingUtilities.invokeLater(()->{dashboard.update(view);pilot.update(view,displayPlan);});
        }
    }
    private void fault(Exception ex) {
        boolean flightWasActive=ownsControls;
        String reason=ex.getMessage()==null?ex.toString():ex.getMessage();
        try{stop(reason,true);}catch(IOException cleanup){reason+="; could not confirm pause/release: "+cleanup.getMessage();}
        event=flightWasActive?"Flight fault: "+reason:reason;final String text=event;SwingUtilities.invokeLater(()->message.setText(text));
        System.err.println(reason);
    }
    private void stop(String reason,boolean pause) throws IOException {
        IOException failure=null;
        if(link!=null&&ownsControls) {
            try {if(pause)pauseAndRelease(link);else release(link);}catch(IOException e){failure=e;}
        }
        ownsControls=false;if(mission!=null)mission.stop();
        if(log!=null){log.close();log=null;}event=reason;
        if(failure!=null)throw failure;
    }
    static void release(NativeLink connection) throws IOException {
        IOException failure=null;
        for(String ref:new String[]{PITCH,ROLL,YAW,OVERRIDES[0],OVERRIDES[1],OVERRIDES[2]}) {
            try{connection.write(ref,0);}catch(IOException e){failure=e;}
        }
        if(failure!=null)throw failure;
    }
    static void pauseAndRelease(NativeLink connection) throws IOException {
        IOException failure=null;
        // Repeat because UDP does not acknowledge writes. Motor settings are retained for takeover.
        for(int i=0;i<3;i++) {
            try{connection.command("sim/operation/pause_on");}catch(IOException e){failure=e;}
            try{release(connection);}catch(IOException e){failure=e;}
        }
        if(failure!=null)throw failure;
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        if(args.length>0&&args[0].equals("--recover")) {
            try(NativeLink connection=new NativeLink("127.0.0.1",49000)){pauseAndRelease(connection);}
            System.out.println("Pause and joystick-release packets sent. Verify X-Plane is paused before taking over.");return;
        }
        if(args.length>0&&args[0].equals("--probe")) {
            try(NativeLink connection=new NativeLink("127.0.0.1",49000)) {
                long deadline=System.nanoTime()+5_000_000_000L;
                while(System.nanoTime()<deadline) {
                    NativeLink.Snapshot s=connection.snapshot();
                    if(s.fresh()) {System.out.println("Live telemetry. ALIA confirmed: "+s.alia());return;}
                    Thread.sleep(100);
                }
                throw new IOException("No complete telemetry. Load ALIA in X-Plane; UDP receive port must be 49000.");
            }
        }
        boolean openTelemetry=Arrays.asList(args).contains("--telemetry");
        boolean openDashboard=Arrays.asList(args).contains("--dashboard");
        boolean openPilot=Arrays.asList(args).contains("--pilot");
        boolean openShadow=Arrays.asList(args).contains("--braking-shadow");
        boolean openSensorLab=Arrays.asList(args).contains("--sensor-lab");
        SwingUtilities.invokeLater(()->{var application=new AliaFlight(openTelemetry,openDashboard,openPilot,openShadow,openSensorLab);if(Arrays.asList(args).contains("--navigation"))application.pilot.showPage("Navigation");});
    }
}
