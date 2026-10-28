package alia;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicitly executed 15 m hover/land trial using the unchanged HoverMission. */
public final class LiveHoverTrial {
    private static final String THROTTLE="sim/cockpit2/engine/actuators/throttle_ratio";
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    public static void main(String[] args)throws Exception {
        boolean execute=Arrays.asList(args).contains("--execute");
        require(execute||Arrays.asList(args).contains("--preflight"),"Use --preflight for read-only checks; flight control requires explicit --execute.");
        Path folder=Path.of("research/local/live-trials",Instant.now().toString().replace(':','-'));Files.createDirectories(folder);
        AtomicBoolean armed=new AtomicBoolean(false);
        try(NativeLink link=new NativeLink("127.0.0.1",49000);Instruments instruments=new Instruments();PrintWriter log=new PrintWriter(Files.newBufferedWriter(folder.resolve("flight.csv")))) {
            Thread cleanup=new Thread(()->{if(armed.get())try{AliaFlight.pauseAndRelease(link);}catch(Exception ignored){}},"trial-pause-release");Runtime.getRuntime().addShutdownHook(cleanup);
            boolean landed=false;String outcome="preflight-rejected";String reason="";
            try {
                long deadline=System.nanoTime()+8_000_000_000L;
                if(Arrays.asList(args).contains("--unpause-ground")){
                    while(!link.snapshot().fresh()&&System.nanoTime()<deadline)Thread.sleep(25);
                    var ready=link.snapshot();require(ready.fresh()&&ready.alia()&&ready.refs[1]==1,"Only stationary ground ALIA may be prepared");
                    require(Math.hypot(ready.pos.east,ready.pos.south)<.3&&Math.abs(ready.pos.up)<.2,"Ground preparation requires stationary aircraft");
                    for(int i=5;i<=9;i++)require(ready.refs[i]<.05,"Ground preparation requires idle throttles");
                    for(int i=10;i<=12;i++)require(ready.refs[i]==0,"Ground preparation cannot take another controller's ownership");
                    if(ready.refs[0]!=0){link.command("sim/operation/pause_off");System.out.println("Prepared stationary ground ALIA: unpause requested.");Thread.sleep(1200);}
                    deadline=System.nanoTime()+8_000_000_000L;
                }
                while((!link.snapshot().fresh()||!instruments.fresh())&&System.nanoTime()<deadline)Thread.sleep(25);
                var s=link.snapshot();require(s.fresh()&&s.alia(),"Fresh ALIA telemetry required");
                require(s.refs[0]==0&&s.refs[1]==1,"Start unpaused and on the ground");
                if(!instruments.fresh())System.out.println(instruments.diagnostic());require(instruments.fresh(),"Fresh trial clock, wind and speed data required");
                require(Math.hypot(s.pos.east,s.pos.south)<.3&&Math.abs(s.pos.up)<.2,"Start stationary");
                require(Math.abs(s.pos.pitch)<5&&Math.abs(s.pos.roll)<3,"Start on level ground");
                require(instruments.get("sim_speed")==1&&instruments.get("wind_mps")<1,"First trial requires 1x speed and calm weather");
                require(instruments.get("sim_speed_actual")>.9&&instruments.get("sim_speed_actual")<1.1,"Simulator must achieve approximately real time");
                for(int i=13;i<=17;i++)require(s.refs[i]==1,"All five batteries must be ON");
                for(int i=5;i<=9;i++)require(s.refs[i]<.05,"Start with lift and pusher at idle");
                for(int i=10;i<=12;i++)require(s.refs[i]==0,"Another controller holds joystick override");
                BatteryStatus b=BatteryStatus.from(s);require(b.chargeAvailable()&&b.minimumPercent()>25,"Measured reserve above 25% required");
                var home=s.pos;var mission=new HoverMission(List.of(new HoverMission.Point(0,0,15)),home.heading,.60,1);
                Files.writeString(folder.resolve("execution-provenance.json"),"{\"wrapper_class_sha256\":"+ExplainabilityModel.quote(classHash(LiveHoverTrial.class))+",\"controller_class_sha256\":"+ExplainabilityModel.quote(classHash(HoverMission.class))+",\"native_link_class_sha256\":"+ExplainabilityModel.quote(classHash(NativeLink.class))+",\"capacity_wh_per_pack\":"+s.refs[22]+"}\n");
                Files.writeString(folder.resolve("metadata.json"),"{\"start\":"+ExplainabilityModel.quote(Instant.now().toString())+",\"controller\":\"unchanged HoverMission\",\"route_m\":[[0,0,15]],\"hover\":0.6,\"max_horizontal_radius_m\":15,\"height_limit_m\":25,\"attitude_limit_deg\":15,\"max_wall_s\":180,\"sensor_source\":\"X-Plane simulated instruments; no physical hardware\",\"commit\":"+ExplainabilityModel.quote(System.getProperty("ccat.research.commit","UNKNOWN"))+",\"latitude\":"+home.lat+",\"longitude\":"+home.lon+",\"instrument_refs\":"+instruments.manifest()+"}\n");
                log.println("wall_utc,elapsed_wall_s,sim_clock_s,phase,north_m,east_m,height_m,agl_m,vup_mps,groundspeed_mps,pitch_deg,roll_deg,lift_command,lift_actual,pusher_actual,battery_pct,battery_kw,ahrs_pitch_pilot_deg,ahrs_pitch_copilot_deg,ahrs_roll_pilot_deg,ahrs_roll_copilot_deg,radio_altimeter_pilot_ft,radio_altimeter_copilot_ft,gps_failure,gps2_failure,sim_speed_actual");
                System.out.println("PREFLIGHT PASS. Explicit trial: vertical takeoff to 15 m, hold, return and land. Log: "+folder);
                if(!execute){outcome="preflight-pass";return;}
                armed.set(true);
                link.write("sim/cockpit/autopilot/autopilot_state",0);link.write("sim/cockpit2/autopilot/flight_director_mode",0);
                for(String ref:AliaFlight.OVERRIDES)link.write(ref,1);
                link.write("sim/flightmodel/controls/parkbrake",0);
                for(int i=0;i<5;i++)link.write("sim/cockpit2/engine/actuators/prop_mode["+i+"]",1);
                mission.start(0);long start=System.nanoTime(),last=start,next=start;double lift=0;int mismatch=0;String phase="";
                while(true){
                    long now=System.nanoTime();double elapsed=(now-start)/1e9,dt=(now-last)/1e9;last=now;
                    require(elapsed<180,"180-second trial deadline reached");require(dt<.5,"Controller scheduling delay exceeded 0.5 s");
                    s=link.snapshot();require(s.fresh()&&s.alia()&&instruments.fresh(),"Aircraft / telemetry / instrument feed lost");
                    require(s.refs[0]==0,"Pilot or simulator paused; ending trial");
                    require(instruments.get("sim_speed")==1,"Simulation speed changed");
                    for(int i=13;i<=17;i++)require(s.refs[i]==1,"Aircraft battery switched off");
                    b=BatteryStatus.from(s);require(b.chargeAvailable()&&b.minimumPercent()>20,"Battery reserve / measurement guard");
                    double north=Math.toRadians(s.pos.lat-home.lat)*6371000,east=Math.toRadians(HoverMission.wrap(s.pos.lon-home.lon))*6371000*Math.cos(Math.toRadians(home.lat)),height=s.pos.elevation-home.elevation,gs=Math.hypot(s.pos.east,s.pos.south);
                    require(Math.hypot(north,east)<15&&height<25&&height> -2,"Trial position envelope exceeded");
                    require(Math.abs(s.pos.pitch)<15&&Math.abs(s.pos.roll)<15&&Math.abs(s.pos.up)<5&&gs<6,"Trial motion / attitude envelope exceeded");
                    var state=new HoverMission.State(north,east,height,s.pos.agl,s.pos.pitch,s.pos.roll,s.pos.heading,-s.pos.south,s.pos.east,s.pos.up,s.pos.p,s.pos.q,s.pos.r,s.refs[1]==1,s.refs[2]);
                    var out=mission.update(state,elapsed);
                    if(elapsed>3){boolean bad=false;for(int i=5;i<=8;i++)bad|=Math.abs(s.refs[i]-lift)>.20;for(int i=10;i<=12;i++)bad|=s.refs[i]!=1;bad|=s.refs[9]>.10;mismatch=bad?mismatch+1:0;require(mismatch<20,"Control readback mismatch");}
                    link.write(AliaFlight.PITCH,out.pitch);link.write(AliaFlight.ROLL,out.roll);link.write(AliaFlight.YAW,out.yaw);link.write("beta/alia_250/collective_ratio",out.lift);
                    for(int i=0;i<4;i++)link.write(THROTTLE+"["+i+"]",out.lift);link.write(THROTTLE+"[4]",0);lift=out.lift;
                    log.printf(Locale.ROOT,"%s,%.3f,%.3f,%s,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.0f,%.0f,%.5f%n",Instant.now(),elapsed,instruments.get("sim_clock_s"),out.phase,north,east,height,s.pos.agl,s.pos.up,gs,s.pos.pitch,s.pos.roll,out.lift,s.refs[5],s.refs[9],b.chargePercent(),b.powerKw(),instruments.get("ahrs_pitch_pilot"),instruments.get("ahrs_pitch_copilot"),instruments.get("ahrs_roll_pilot"),instruments.get("ahrs_roll_copilot"),instruments.get("radio_alt_pilot_ft"),instruments.get("radio_alt_copilot_ft"),instruments.get("gps_failure"),instruments.get("gps2_failure"),instruments.get("sim_speed_actual"));log.flush();require(!log.checkError(),"Flight log write failed");
                    if(!phase.equals(out.phase.name())||elapsed%5<.055){phase=out.phase.name();System.out.printf(Locale.ROOT,"%.1fs %s height %.2fm AGL %.2fm vup %+.2f lift %.3f drift %.2fm%n",elapsed,phase,height,s.pos.agl,s.pos.up,out.lift,Math.hypot(north,east));}
                    if(out.phase==HoverMission.Phase.LANDED){landed=true;outcome="landed";link.write("sim/flightmodel/controls/parkbrake",1);AliaFlight.release(link);armed.set(false);break;}
                    next+=50_000_000L;long wait=next-System.nanoTime();if(wait>0)Thread.sleep(wait/1_000_000L,(int)(wait%1_000_000L));else next=System.nanoTime();
                }
            }catch(Exception ex){reason=ex.getMessage();outcome=armed.get()?"interrupted":"preflight-rejected";System.err.println("TRIAL "+outcome+": "+reason);}
            finally{
                if(armed.get()){AliaFlight.pauseAndRelease(link);armed.set(false);}
                Files.writeString(folder.resolve("outcome.json"),"{\"outcome\":"+ExplainabilityModel.quote(outcome)+",\"reason\":"+ExplainabilityModel.quote(reason)+",\"landed\":"+landed+",\"end\":"+ExplainabilityModel.quote(Instant.now().toString())+"}\n");
                Runtime.getRuntime().removeShutdownHook(cleanup);
                System.out.println("RESULT "+outcome+". "+folder);
            }
        }
    }
    private static String classHash(Class<?> type)throws Exception{try(var input=type.getResourceAsStream(type.getSimpleName()+".class")){if(input==null)throw new IOException("Missing execution class bytes");return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));}}
    /** Own read-only RREF socket: never requests RPOS or writes a simulator value. */
    static final class Instruments implements AutoCloseable {
        private final LinkedHashMap<String,String> refs=new LinkedHashMap<>();
        private final Map<String,Double> values=new HashMap<>();private final Map<String,Long> times=new HashMap<>();
        private final DatagramSocket socket=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));private final Thread reader;private volatile boolean closed;
        Instruments()throws Exception{
            refs.put("sim_clock_s","sim/time/total_running_time_sec");refs.put("sim_speed","sim/time/sim_speed");refs.put("sim_speed_actual","sim/time/sim_speed_actual");refs.put("wind_mps","sim/weather/aircraft/wind_now_speed_msc");
            for(String side:List.of("pilot","copilot")){refs.put("ahrs_pitch_"+side,"sim/cockpit2/gauges/indicators/pitch_AHARS_deg_"+side);refs.put("ahrs_roll_"+side,"sim/cockpit2/gauges/indicators/roll_AHARS_deg_"+side);refs.put("radio_alt_"+side+"_ft","sim/cockpit2/gauges/indicators/radio_altimeter_height_ft_"+side);}
            refs.put("gps_failure","sim/operation/failures/rel_gps");refs.put("gps2_failure","sim/operation/failures/rel_gps2");socket.setSoTimeout(250);
            reader=new Thread(this::receive,"trial-instruments");reader.setDaemon(true);reader.start();subscribe(20);
        }
        private void subscribe(int rate)throws IOException{int i=0;for(String ref:refs.values()){byte[] data=NativeLink.request(10000+i++,rate,ref);socket.send(new DatagramPacket(data,data.length,InetAddress.getByName("127.0.0.1"),49000));}}
        private void receive(){byte[] bytes=new byte[4096];List<String> names=List.copyOf(refs.keySet());while(!closed)try{DatagramPacket p=new DatagramPacket(bytes,bytes.length);socket.receive(p);if(!p.getAddress().isLoopbackAddress()||p.getLength()<5||(p.getLength()-5)%8!=0||bytes[0]!='R'||bytes[1]!='R'||bytes[2]!='E'||bytes[3]!='F')continue;ByteBuffer b=ByteBuffer.wrap(bytes,5,p.getLength()-5).order(ByteOrder.LITTLE_ENDIAN);synchronized(this){while(b.remaining()>=8){int i=b.getInt()-10000;float v=b.getFloat();if(i>=0&&i<names.size()&&Float.isFinite(v)){values.put(names.get(i),(double)v);times.put(names.get(i),System.nanoTime());}}}}catch(SocketTimeoutException ignored){}catch(IOException e){if(!closed)System.err.println(e.getMessage());}}
        synchronized boolean fresh(){long now=System.nanoTime();return refs.keySet().stream().allMatch(k->times.containsKey(k)&&now-times.get(k)<1_000_000_000L);}
        synchronized double get(String name){return values.getOrDefault(name,Double.NaN);}
        synchronized String diagnostic(){return "Instrument readback: "+values+"; expected "+refs.keySet();}
        String manifest(){StringJoiner j=new StringJoiner(",","{","}");refs.forEach((k,v)->j.add(ExplainabilityModel.quote(k)+":"+ExplainabilityModel.quote(v)));return j.toString();}
        public void close(){try{subscribe(0);}catch(IOException ignored){}closed=true;socket.close();}
    }
}
