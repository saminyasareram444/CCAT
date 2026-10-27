package alia;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** No third-party dependencies. A simplified plant checks logic, not ALIA flight-model fidelity. */
public final class AliaTests {
    private static int checks;
    private static void check(boolean value,String text) {
        checks++;if(!value)throw new AssertionError(text);
    }
    private static void rejects(Runnable work,String text) {
        boolean rejected=false;try{work.run();}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,text);
    }
    public static void main(String[] args) throws Exception {
        protocol();validation();network();
        plant(0.60,0,0);plant(0.67,0.12,90);plant(0.53,-0.10,-170);
        timeoutAndLimits();
        System.out.println("PASS: "+checks+" checks. Protocol, local UDP fixture, three simplified flight missions, and fault limits.");
        System.out.println("These tests do not validate actual X-Plane/ALIA dynamics, motor response, terrain, or wind.");
    }
    private static void protocol() {
        byte[] request=NativeLink.request(7,20,"sim/test");
        ByteBuffer b=ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
        check(request.length==413,"RREF fixed length");
        check(new String(request,0,5,StandardCharsets.US_ASCII).equals("RREF\0"),"RREF header");
        check(b.getInt(5)==20&&b.getInt(9)==7,"RREF little-endian frequency/index");
        byte[] write=NativeLink.writePacket("sim/test",0.375);
        check(write.length==509,"DREF fixed length");
        check(ByteBuffer.wrap(write).order(ByteOrder.LITTLE_ENDIAN).getFloat(5)==0.375f,"DREF float");
        check(write[17]==0&&write[18]==' ',"DREF null and space padding");
        rejects(()->NativeLink.writePacket("sim/test",Double.NaN),"reject NaN control");
        byte[] command=NativeLink.textPacket("CMND","sim/operation/pause_on");
        check(command[4]==0&&command[command.length-1]==0,"command null terminators");
    }
    private static void validation() {
        List<HoverMission.Point> route=AliaFlight.parseRoute("# north,east,height\n0,20,15\n\n10,20,15");
        check(route.size()==2,"parse waypoint comments and blanks");
        rejects(()->AliaFlight.parseRoute("0,1"),"reject incomplete waypoint");
        rejects(()->new HoverMission(Collections.emptyList(),0,0.6,3),"reject empty mission");
        rejects(()->new HoverMission(Arrays.asList(new HoverMission.Point(501,0,15)),0,.6,3),"range limit");
        rejects(()->new HoverMission(Arrays.asList(new HoverMission.Point(0,0,0)),0,.6,3),"height limit");
        rejects(()->new HoverMission(Arrays.asList(new HoverMission.Point(Double.NaN,0,15)),0,.6,3),"reject non-finite route");
        rejects(()->new HoverMission(route,0,.99,3),"hover throttle limit");
        rejects(()->new HoverMission(route,0,.6,40),"speed limit");
        check(HoverMission.wrap(359)==-1&&HoverMission.wrap(-359)==1,"heading wrap");
    }
    private static void network() throws Exception {
        try(FakePlane server=new FakePlane();NativeLink link=new NativeLink("127.0.0.1",server.port())) {
            check(server.port()!=server.replies.getLocalPort(),"fixture replies from a different UDP port like X-Plane");
            long end=System.nanoTime()+3_000_000_000L;
            while(!link.snapshot().fresh()&&System.nanoTime()<end)Thread.sleep(20);
            NativeLink.Snapshot s=link.snapshot();
            check(s.fresh(),"receive complete UDP telemetry");check(s.alia(),"confirm ALIA ICAO and engine count");
            check(Math.abs(s.pos.lat-36.1)<1e-9,"RPOS preserves double latitude");
            check(s.pos.pitch==2&&s.pos.heading==30&&s.pos.roll==3,"RPOS orientation order");
            check(Math.abs(s.pos.q-Math.toDegrees(.2))<0.001,"RPOS radians converted to degrees");
            check(server.writes.isEmpty(),"connection sends no control writes");
            check(PilotWeather.from(s).complete()&&Math.abs(PilotWeather.from(s).windKnots()-9.71922)<.001,"optional aircraft weather is received through the native UDP subscription");
            checks+=ExplainabilityTests.run(s);
            checks+=PilotDisplayTests.run();
            checks+=PilotAttributionTests.run();
            checks+=AirportMissionTests.run(s);
            checks+=AirportPlantTests.run(s.pos);
            checks+=BrakingShadowTests.run();
            check(server.writes.isEmpty()&&server.commands.isEmpty(),"shadow observer/tests cannot issue simulator controls");
            s=link.snapshot(); // Numerical/rendering checks can outlive the original telemetry snapshot's freshness window.
            String telemetry=TelemetryView.format(s,"MONITORING",0,null,null);
            check(telemetry.contains("Connection: LIVE")&&telemetry.contains("ALIA-250 confirmed"),"telemetry connection and aircraft labels");
            check(telemetry.contains("Lift motor 4")&&telemetry.contains("Pusher motor"),"telemetry shows all five motor throttles");
            check(telemetry.contains("Battery 1: ON")&&telemetry.contains("Battery 5: ON"),"telemetry shows all five battery states");
            check(telemetry.contains("Vertical speed:")&&telemetry.contains("Heading:"),"telemetry flight instruments");
            check(TelemetryView.readiness(s).contains("readings OK"),"preflight ready from complete idle telemetry");
            double[] changed=s.refs.clone();changed[17]=0;
            check(TelemetryView.readiness(new NativeLink.Snapshot(s.pos,changed,s.times)).contains("START/power"),"battery-off preflight guidance");
            changed=s.refs.clone();changed[9]=.3;
            check(TelemetryView.readiness(new NativeLink.Snapshot(s.pos,changed,s.times)).contains("F4"),"pusher idle uses user's saved key");
            String waiting=TelemetryView.format(new NativeLink.Snapshot(null,new double[NativeLink.REFS.length],
                new long[NativeLink.REFS.length]),"MONITORING",0,null,null);
            check(waiting.contains("Battery 1: --")&&!waiting.contains("Battery 1: OFF"),"missing telemetry is not displayed as battery OFF");
            // Malformed packets and unknown indexes must not corrupt telemetry.
            link.ingest(new byte[2],2);
            link.ingest(new byte[]{'R','R','E','F',0,1},6);
            check(link.snapshot().alia(),"ignore malformed datagrams");
            AliaFlight.pauseAndRelease(link);
            end=System.nanoTime()+1_000_000_000L;
            while(server.writes.size()<18&&System.nanoTime()<end)Thread.sleep(10);
            check(server.writes.size()==18,"takeover releases three axes and three overrides, repeated three times");
            check(server.commands.size()==3,"takeover requests pause three times");
            for(String ref:server.writes)check(!ref.contains("throttle")&&!ref.contains("collective"),"takeover preserves motor settings");
            server.transmit=false;Thread.sleep(1150);
            check(!link.snapshot().fresh(),"stale telemetry detected");
            link.close();end=System.nanoTime()+1_000_000_000L;
            while(server.unsubscribed<NativeLink.REFS.length&&System.nanoTime()<end)Thread.sleep(10);
            check(server.unsubscribed==NativeLink.REFS.length,"close unsubscribes datarefs");
            check(server.stoppedRpos,"close stops RPOS stream");
        }
    }
    private static HoverMission.State state(double n,double e,double h,double pitch,double roll,double heading,
                                            double vn,double ve,double vu,double p,double q,double r) {
        return new HoverMission.State(n,e,h,h+1.5,pitch,roll,heading,vn,ve,vu,p,q,r,h<=0.001);
    }
    private static void plant(double actualHover,double disturbance,double initialHeading) {
        HoverMission controller=new HoverMission(Arrays.asList(new HoverMission.Point(0,60,15),
            new HoverMission.Point(40,60,15),new HoverMission.Point(0,0,15)),initialHeading,0.60,3);
        controller.start(0);double n=0,e=0,h=0,vn=0,ve=0,vu=0,pitch=0,roll=0,heading=initialHeading,p=0,q=0,r=0;
        double actuator=0;EnumSet<HoverMission.Phase> seen=EnumSet.noneOf(HoverMission.Phase.class);
        final double dt=.05;
        for(int i=1;i<10000;i++) {
            HoverMission.Output o=controller.update(state(n,e,h,pitch,roll,heading,vn,ve,vu,p,q,r),i*dt);
            seen.add(o.phase);
            check(o.lift>=0&&o.lift<=.95,"motor output bounded");
            if(o.phase==HoverMission.Phase.LANDED) {
                check(Math.hypot(n,e)<2.5,"touchdown near home");check(h<.1,"landed on ground");
                check(o.lift==0,"lift idles after landing");
                check(seen.containsAll(EnumSet.of(HoverMission.Phase.TAKEOFF,HoverMission.Phase.WAYPOINTS,
                    HoverMission.Phase.RETURN,HoverMission.Phase.DESCEND,HoverMission.Phase.LANDED)),"all mission phases reached");
                System.out.printf(Locale.ROOT,"Simplified plant: hover %.2f, disturbance %+.2f, heading %.0f, landed in %.1fs, error %.2fm%n",
                    actualHover,disturbance,initialHeading,i*dt,Math.hypot(n,e));return;
            }
            // Delayed thrust, nonlinear lift, attitude dynamics, horizontal drag and disturbance.
            actuator+=(o.lift-actuator)*dt/.25;
            p+=(65*o.roll-2.5*p)*dt;q+=(65*o.pitch-2.5*q)*dt;r+=(50*o.yaw-2*r)*dt;
            pitch+=q*dt;roll+=p*dt;heading+=r*dt;
            double thrust=9.80665*Math.pow(actuator/actualHover,2);
            double aForward=-Math.sin(Math.toRadians(pitch))*thrust;
            double aRight=Math.sin(Math.toRadians(roll))*thrust;
            double angle=Math.toRadians(heading);
            vn+=(aForward*Math.cos(angle)-aRight*Math.sin(angle)-.15*vn+disturbance)*dt;
            ve+=(aForward*Math.sin(angle)+aRight*Math.cos(angle)-.15*ve)*dt;
            vu+=(thrust*Math.cos(Math.toRadians(pitch))*Math.cos(Math.toRadians(roll))-9.80665-.25*vu)*dt;
            n+=vn*dt;e+=ve*dt;h+=vu*dt;
            if(h<0){h=0;vu=Math.max(0,vu);vn*=.9;ve*=.9;}
        }
        throw new AssertionError("Mission did not land");
    }
    private static void timeoutAndLimits() {
        HoverMission mission=new HoverMission(Arrays.asList(new HoverMission.Point(0,20,15)),0,.6,3);
        mission.start(0);boolean rejected=false;
        try{mission.update(state(0,0,0,0,0,0,0,0,0,0,0,0),31);}catch(IllegalStateException e){rejected=true;}
        check(rejected,"detect no liftoff");
        mission=new HoverMission(Arrays.asList(new HoverMission.Point(0,20,15)),0,.6,3);mission.start(0);rejected=false;
        try{mission.update(state(0,0,10,0,40,0,0,0,0,0,0,0),1);}catch(IllegalStateException e){rejected=true;}
        check(rejected,"detect excessive attitude");
        mission.stop();check(mission.update(state(0,0,10,0,0,0,0,0,0,0,0,0),2).lift==0,"stopped controller returns no thrust command");
    }
    private static final class FakePlane implements AutoCloseable {
        final DatagramSocket socket=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));
        final DatagramSocket replies=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));
        final List<String> writes=new CopyOnWriteArrayList<>(),commands=new CopyOnWriteArrayList<>();
        final Map<Integer,String> subscriptions=new ConcurrentHashMap<>();
        volatile boolean transmit=true,closed,stoppedRpos;
        volatile int unsubscribed;
        private InetSocketAddress client;
        private final Thread thread;
        FakePlane() throws Exception {
            socket.setSoTimeout(10);thread=new Thread(this::run);thread.setDaemon(true);thread.start();
        }
        int port(){return socket.getLocalPort();}
        private void run() {
            long sent=0;byte[] bytes=new byte[2048];
            while(!closed) {
                DatagramPacket packet=new DatagramPacket(bytes,bytes.length);
                try {
                    socket.receive(packet);client=new InetSocketAddress(packet.getAddress(),packet.getPort());
                    String tag=new String(bytes,0,4,StandardCharsets.US_ASCII);
                    ByteBuffer b=ByteBuffer.wrap(bytes,0,packet.getLength()).order(ByteOrder.LITTLE_ENDIAN);b.position(5);
                    if(tag.equals("RREF")) {
                        int frequency=b.getInt(),index=b.getInt();
                        if(frequency==0){subscriptions.remove(index);unsubscribed++;}else subscriptions.put(index,string(bytes,13,packet.getLength()));
                    }else if(tag.equals("DREF"))writes.add(string(bytes,9,packet.getLength()));
                    else if(tag.equals("CMND"))commands.add(string(bytes,5,packet.getLength()));
                    else if(tag.equals("RPOS")&&bytes[5]=='0')stoppedRpos=true;
                }catch(SocketTimeoutException ignored){}catch(IOException e){if(!closed)throw new RuntimeException(e);}
                if(transmit&&client!=null&&System.nanoTime()-sent>50_000_000L) {
                    try {
                        ByteBuffer p=ByteBuffer.allocate(69).order(ByteOrder.LITTLE_ENDIAN);
                        p.put(new byte[]{'R','P','O','S',0}).putDouble(-115.2).putDouble(36.1).putDouble(600);
                        for(float v:new float[]{1.5f,2,30,3,0,0,0,.1f,.2f,.3f})p.putFloat(v);
                        replies.send(new DatagramPacket(p.array(),69,client));
                        ByteBuffer r=ByteBuffer.allocate(5+subscriptions.size()*8).order(ByteOrder.LITTLE_ENDIAN);
                        r.put(new byte[]{'R','R','E','F',0});
                        for(int index:subscriptions.keySet()) {
                            float value=index==1?1:index==3?5:index>=13&&index<=17?1:0;
                            if(index>=18&&index<22)value="ALIA".charAt(index-18);
                            if(index==22)value=65000;
                            if(index>=23&&index<28)value=52000;
                            if(index>=28&&index<33)value=800;
                            if(index>=33&&index<38)value=100;
                            if(index==NativeLink.WEATHER_OFFSET)value=270;
                            if(index==NativeLink.WEATHER_OFFSET+1)value=5;
                            if(index==NativeLink.WEATHER_OFFSET+2)value=15;
                            if(index==NativeLink.WEATHER_OFFSET+3)value=12;
                            if(index==NativeLink.WEATHER_OFFSET+4)value=.2f;
                            r.putInt(index).putFloat(value);
                        }
                        replies.send(new DatagramPacket(r.array(),r.position(),client));sent=System.nanoTime();
                    }catch(IOException ignored){}
                }
            }
        }
        private static String string(byte[] bytes,int start,int length) {
            int end=start;while(end<length&&bytes[end]!=0)end++;return new String(bytes,start,end-start,StandardCharsets.US_ASCII);
        }
        public void close() throws Exception {closed=true;socket.close();replies.close();thread.join(1000);}
    }
}
