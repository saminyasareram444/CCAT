package alia;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Pure numerical observer: has no transport, aircraft reference or control-output interface. */
final class BrakingModel {
    static final String POLICY="alia-airport-brake-hold-v1";
    static final String FEATURES="ground_speed_mps,ground_speed_squared,forward_ias_kt,vertical_speed_mps,pitch_deg,lift_command,pusher_command,approach_age_s";
    record Observation(double groundSpeed,double airspeed,double vUp,double pitch,double lift,double pusher,double approachAge) {
        double[] features(){return new double[]{groundSpeed,groundSpeed*groundSpeed,Math.max(0,airspeed),vUp,pitch,lift,pusher,approachAge};}
    }
    record Prediction(String modelId,double distanceM,double timeS,List<String> outsideSupport) {
        boolean supported(){return outsideSupport.isEmpty();}
    }
    private final String id;
    private final double[] means,scales,minimum,maximum,padding,distance,time;
    private BrakingModel(Properties p) throws IOException {
        if(!"1".equals(p.getProperty("schema_version"))||!POLICY.equals(p.getProperty("policy"))||!FEATURES.equals(p.getProperty("features"))||
           !"PILOT_ONLY".equals(p.getProperty("qualification"))||!"false".equals(p.getProperty("flight_control_authorized"))||!"false".equals(p.getProperty("uncertainty_qualified")))
            throw new IOException("Unsupported shadow-model contract");
        id=p.getProperty("model_id","");if(!id.matches("[0-9a-f]{16}"))throw new IOException("Invalid model identifier");
        means=values(p,"means",8);scales=values(p,"scales",8);minimum=values(p,"minimum",8);maximum=values(p,"maximum",8);padding=values(p,"padding",8);
        distance=values(p,"distance_m_coefficients",9);time=values(p,"time_s_coefficients",9);
        for(int i=0;i<8;i++)if(scales[i]<=0||minimum[i]>maximum[i]||padding[i]<0)throw new IOException("Invalid model feature bounds");
    }
    static BrakingModel load(Path path) throws IOException {
        if(Files.size(path)>64*1024)throw new IOException("Shadow-model file is too large");
        Properties properties=new Properties();try(Reader r=Files.newBufferedReader(path)){properties.load(r);}return new BrakingModel(properties);
    }
    private static double[] values(Properties p,String key,int count) throws IOException {
        try {
            String[] values=p.getProperty(key,"").split(",");if(values.length!=count)throw new NumberFormatException();double[] result=new double[count];
            for(int i=0;i<count;i++){result[i]=Double.parseDouble(values[i]);if(!Double.isFinite(result[i]))throw new NumberFormatException();}return result;
        }catch(NumberFormatException ex){throw new IOException("Invalid model field: "+key,ex);}
    }
    Prediction predict(Observation observation) {
        double[] x=observation.features();String[] names=FEATURES.split(",");List<String> outside=new ArrayList<>();double d=distance[0],t=time[0];
        if(observation.groundSpeed()<0||observation.approachAge()<0||observation.lift()<0||observation.lift()>1||observation.pusher()<0||observation.pusher()>1)
            throw new IllegalArgumentException("Invalid physical feature range");
        for(int i=0;i<x.length;i++){
            if(!Double.isFinite(x[i]))throw new IllegalArgumentException("Missing/nonfinite feature: "+names[i]);
            if(x[i]<minimum[i]-padding[i]||x[i]>maximum[i]+padding[i])outside.add(names[i]);
            double z=(x[i]-means[i])/scales[i];d+=distance[i+1]*z;t+=time[i+1]*z;
        }
        if(!Double.isFinite(d)||!Double.isFinite(t))throw new IllegalArgumentException("Nonfinite prediction");
        return new Prediction(id,Math.max(0,d),Math.max(0,t),List.copyOf(outside));
    }
    String id(){return id;}
}
