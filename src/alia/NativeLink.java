package alia;

import java.io.IOException;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** X-Plane's built-in UDP protocol, documented in Instructions/Exchanging Data with X-Plane. */
public final class NativeLink implements AutoCloseable {
    public static final int CORE_COUNT=22;
    private static final String[] CORE_REFS = {
        "sim/time/paused", "sim/flightmodel/failures/onground_any",
        "sim/flightmodel/position/indicated_airspeed", "sim/aircraft/engine/acf_num_engines",
        "beta/alia_250/collective_ratio",
        "sim/cockpit2/engine/actuators/throttle_ratio[0]",
        "sim/cockpit2/engine/actuators/throttle_ratio[1]",
        "sim/cockpit2/engine/actuators/throttle_ratio[2]",
        "sim/cockpit2/engine/actuators/throttle_ratio[3]",
        "sim/cockpit2/engine/actuators/throttle_ratio[4]",
        "sim/operation/override/override_joystick_pitch",
        "sim/operation/override/override_joystick_roll",
        "sim/operation/override/override_joystick_heading",
        "sim/cockpit2/electrical/battery_on[0]",
        "sim/cockpit2/electrical/battery_on[1]",
        "sim/cockpit2/electrical/battery_on[2]",
        "sim/cockpit2/electrical/battery_on[3]",
        "sim/cockpit2/electrical/battery_on[4]",
        "sim/aircraft/view/acf_ICAO[0]", "sim/aircraft/view/acf_ICAO[1]",
        "sim/aircraft/view/acf_ICAO[2]", "sim/aircraft/view/acf_ICAO[3]"
    };
    public static final String[] REFS=buildRefs();
    private static String[] buildRefs() {
        List<String> refs=new ArrayList<>(Arrays.asList(CORE_REFS));
        refs.add("sim/aircraft/electrical/battery_watt_hr_max");
        for(String base:new String[]{"sim/cockpit/electrical/battery_charge_watt_hr",
            "sim/cockpit2/electrical/battery_voltage_actual_volts","sim/cockpit2/electrical/battery_amps"})
            for(int i=0;i<5;i++)refs.add(base+"["+i+"]");
        return refs.toArray(String[]::new);
    }
    private final DatagramSocket socket;
    private final InetSocketAddress destination;
    private final double[] values = new double[REFS.length];
    private final long[] stamps = new long[REFS.length];
    private final Thread reader;
    private volatile boolean closed;
    private volatile Position position;
    private volatile String error = "";

    public static final class Position {
        public final double lon, lat, elevation, agl, pitch, heading, roll, east, up, south, p, q, r;
        public final long received;
        Position(ByteBuffer b) {
            lon=b.getDouble(); lat=b.getDouble(); elevation=b.getDouble(); agl=b.getFloat();
            pitch=b.getFloat(); heading=b.getFloat(); roll=b.getFloat();
            east=b.getFloat(); up=b.getFloat(); south=b.getFloat();
            p=Math.toDegrees(b.getFloat()); q=Math.toDegrees(b.getFloat()); r=Math.toDegrees(b.getFloat());
            received=System.nanoTime();
        }
    }
    public static final class Snapshot {
        public final Position pos;
        public final double[] refs;
        public final long[] times;
        Snapshot(Position p, double[] v, long[] t) {pos=p; refs=v; times=t;}
        public boolean fresh() {
            long now=System.nanoTime();
            if(pos==null || now-pos.received>1_000_000_000L) return false;
            for(int i=0;i<CORE_COUNT;i++)
                if(times[i]==0 || now-times[i]>1_000_000_000L || !Double.isFinite(refs[i])) return false;
            return true;
        }
        public boolean alia() {
            return refs[3]==5 && refs[18]=='A' && refs[19]=='L' && refs[20]=='I' && refs[21]=='A';
        }
    }
    public NativeLink(String host, int port) throws IOException {
        destination=new InetSocketAddress(InetAddress.getByName(host),port);
        socket=new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));
        // X-Plane receives on 49000 but normally replies from 49001. Connecting
        // a DatagramSocket would silently discard those replies as the wrong peer.
        socket.setSoTimeout(250);
        reader=new Thread(this::receive,"alia-udp"); reader.setDaemon(true); reader.start();
        try { subscribe(); } catch(IOException e) {close(); throw e;}
    }
    public synchronized Snapshot snapshot() {return new Snapshot(position,values.clone(),stamps.clone());}
    public String error() {return error;}
    public void subscribe() throws IOException {
        send(textPacket("RPOS","20"));
        for(int i=0;i<REFS.length;i++) send(request(i,20,REFS[i]));
    }
    static byte[] request(int index,int frequency,String ref) {
        ByteBuffer b=packet("RREF",413); b.putInt(frequency).putInt(index); putString(b,ref,400); return b.array();
    }
    static byte[] writePacket(String ref,double value) {
        if(!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite control value");
        ByteBuffer b=packet("DREF",509); b.putFloat((float)value); putString(b,ref,500); return b.array();
    }
    static byte[] textPacket(String tag,String text) {
        ByteBuffer b=packet(tag,6+text.length()); putString(b,text,text.length()+1); return b.array();
    }
    private static ByteBuffer packet(String tag,int size) {
        ByteBuffer b=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.put(tag.getBytes(StandardCharsets.US_ASCII)).put((byte)0); return b;
    }
    private static void putString(ByteBuffer b,String text,int size) {
        byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);
        if(bytes.length>=size) throw new IllegalArgumentException("UDP string too long");
        b.put(bytes).put((byte)0);
        while(b.hasRemaining()) b.put((byte)' ');
    }
    public void write(String ref,double value) throws IOException {send(writePacket(ref,value));}
    public void command(String command) throws IOException {send(textPacket("CMND",command));}
    private void send(byte[] bytes) throws IOException {
        socket.send(new DatagramPacket(bytes,bytes.length,destination));
    }
    private void receive() {
        byte[] data=new byte[4096];
        while(!closed) {
            DatagramPacket datagram=new DatagramPacket(data,data.length);
            try {
                socket.receive(datagram);
                // Validate the simulator's address, while allowing its separate send port.
                if(!datagram.getAddress().equals(destination.getAddress()))continue;
                ingest(data,datagram.getLength()); error="";
            }
            catch(SocketTimeoutException ignored) {}
            catch(IOException e) {if(!closed) error=e.getMessage();}
        }
    }
    synchronized void ingest(byte[] bytes,int length) {
        if(length<5) return;
        String tag=new String(bytes,0,4,StandardCharsets.US_ASCII);
        ByteBuffer b=ByteBuffer.wrap(bytes,0,length).order(ByteOrder.LITTLE_ENDIAN); b.position(5);
        if(tag.equals("RPOS") && length==69) {
            Position p=new Position(b);
            double[] fields={p.lon,p.lat,p.elevation,p.agl,p.pitch,p.heading,p.roll,p.east,p.up,p.south,p.p,p.q,p.r};
            for(double v:fields) if(!Double.isFinite(v)) return;
            if(Math.abs(p.lat)>90 || Math.abs(p.lon)>180) return;
            position=p;
        } else if(tag.equals("RREF") && (length-5)%8==0) {
            while(b.remaining()>=8) {
                int index=b.getInt(); float value=b.getFloat();
                if(index>=0 && index<values.length) {values[index]=value; stamps[index]=System.nanoTime();}
            }
        }
    }
    @Override public void close() {
        if(closed) return;
        try {
            send(textPacket("RPOS","0"));
            for(int i=0;i<REFS.length;i++) send(request(i,0,REFS[i]));
        } catch(IOException ignored) {} finally {closed=true;socket.close();}
    }
}
