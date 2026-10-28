package alia;

import java.util.List;

/** Value-only synthetic research interface. No aircraft or controller reference. */
interface SensorResearch extends AutoCloseable {
    enum Sensor { GPS, IMU, LIDAR }
    enum Availability { AVAILABLE, DROPOUT, STALE }
    record Input(Sensor sensor,double error,Availability availability) {
        public Input { if(sensor==null||availability==null||!Double.isFinite(error)||error<0||error>20)throw new IllegalArgumentException("Synthetic error must be 0..20"); }
    }
    record Reading(Sensor sensor,double error,Availability availability,String band) {}
    record Decision(String id,Sensor sensor,String band,String action,boolean rateable,boolean training) {}
    record Score(Sensor sensor,String band,double warn,double quiet,long updates) {}
    record View(long sequence,long receivedNanos,boolean training,String status,List<Reading> readings,List<Decision> decisions,List<Score> scores,int feedbackCount) {
        public View {readings=List.copyOf(readings);decisions=List.copyOf(decisions);scores=List.copyOf(scores);}
        boolean fresh(){return receivedNanos>0&&System.nanoTime()-receivedNanos<2_000_000_000L;}
    }
    View view();
    void submit(List<Input> inputs);
    void feedback(String decisionId,boolean appropriate);
    void training(boolean enabled);
    void reset();
    void close();
}
