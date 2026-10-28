package alia;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic warning research only. Never receives NativeLink or a controller. */
public final class SoarSensorResearch implements SensorResearch {
    static final long FEEDBACK_TTL=30_000_000_000L;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"soar-sensor-research");t.setDaemon(true);return t;});
    private final ArrayBlockingQueue<Runnable> commands=new ArrayBlockingQueue<>(8);
    private final EnumMap<Sensor,SoarWarningAgent> agents=new EnumMap<>(Sensor.class);
    private final Map<String,Decision> pending=new LinkedHashMap<>();
    private List<Input> inputs=List.of(new Input(Sensor.GPS,8.5,Availability.AVAILABLE),new Input(Sensor.IMU,0,Availability.AVAILABLE),new Input(Sensor.LIDAR,0,Availability.AVAILABLE));
    private List<Decision> decisions=List.of();
    private volatile View view=new View(0,0,true,"Starting JSoar…",List.of(),List.of(),List.of(),0);
    private volatile boolean closed;
    private boolean failed;
    private long sequence,expires;
    private boolean training=true;
    private int feedbackCount;
    private String status="SYNTHETIC ONLY · no aircraft authority";
    private PrintWriter log;
    private final Path source=Path.of("research/soar/sensor-warning.soar");
    public SoarSensorResearch() {
        worker.execute(()->{
            try {
                Path folder=Path.of("research/local/sensor-demo");Files.createDirectories(folder);
                log=new PrintWriter(Files.newBufferedWriter(folder.resolve("session-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".jsonl")));
                log("session","\"runtime\":\"JSoar 4.1.0\",\"seed\":42,\"learning_rate\":0.3,\"discount\":0,\"eligibility_decay\":0,\"training_epsilon\":0.2,\"source_sha256\":"+q(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))))+",\"commit\":"+q(System.getProperty("ccat.research.commit","UNKNOWN"))+",\"dirty\":"+q(System.getProperty("ccat.research.dirty","UNKNOWN"))+",\"authority\":\"none\",\"sensor_source\":\"synthetic_dimensionless_error_proxy\"");
                createAgents();evaluate();
                worker.scheduleAtFixedRate(this::tick,0,500,TimeUnit.MILLISECONDS);
            }catch(Exception e){failed(e);}
        });
    }
    private void createAgents() throws Exception {for(var a:agents.values())a.close();agents.clear();for(Sensor s:Sensor.values())agents.put(s,new SoarWarningAgent(s.name(),42+s.ordinal(),source));}
    public View view(){return view;}
    public void submit(List<Input> values){
        var copy=List.copyOf(values);
        if(copy.size()!=3||copy.stream().map(Input::sensor).distinct().count()!=3)throw new IllegalArgumentException("Supply one input for each sensor");
        enqueue(()->{inputs=copy;try{evaluate();}catch(Exception e){failed(e);}});
    }
    public void feedback(String id,boolean appropriate){enqueue(()->applyFeedback(id,appropriate,System.nanoTime()));}
    void feedbackAt(String id,boolean appropriate,long now){enqueue(()->applyFeedback(id,appropriate,now));}
    public void training(boolean enabled){enqueue(()->{training=enabled;try{evaluate();}catch(Exception e){failed(e);}});}
    public void reset(){enqueue(()->{try{pending.clear();feedbackCount=0;createAgents();status="Learning reset for this session";log("reset","\"seed\":42");evaluate();}catch(Exception e){failed(e);}});}
    private void enqueue(Runnable r){if(!closed&&!commands.offer(r))throw new IllegalStateException("Research worker busy; try again");}
    private void tick(){
        try {
            if(failed)return;
            for(int n=0;n<8;n++){Runnable r=commands.poll();if(r==null)break;r.run();}
            if(!pending.isEmpty()&&System.nanoTime()>=expires){pending.clear();decisions=decisions.stream().map(d->new Decision(d.id(),d.sensor(),d.band(),d.action(),false,d.training())).toList();status="Rating expired; no reward applied. Evaluate again.";log("expired","\"sequence\":"+sequence);}
            publish();
        }catch(Exception e){failed(e);}
    }
    private void evaluate() throws Exception {
        pending.clear();sequence++;expires=System.nanoTime()+FEEDBACK_TTL;
        List<Decision> result=new ArrayList<>();
        for(Input i:inputs){
            boolean available=i.availability()==Availability.AVAILABLE;
            String action=agents.get(i.sensor()).decide(i.error(),available,training),band=band(i);
            boolean rateable=training&&(band.equals("low")||band.equals("high"));
            String id=sequence+"-"+i.sensor();var d=new Decision(id,i.sensor(),band,action,rateable,training);result.add(d);if(rateable)pending.put(id,d);
            log("decision","\"id\":"+q(id)+",\"sensor\":"+q(i.sensor().name())+",\"error_proxy\":"+i.error()+",\"availability\":"+q(i.availability().name())+",\"band\":"+q(band)+",\"action\":"+q(action)+",\"training\":"+training);
        }
        decisions=List.copyOf(result);status=training?"TRAIN · pilot ratings update warning preferences":"EVALUATE · learning frozen, greedy policy";publish();
    }
    static String band(Input i){return i.availability()!=Availability.AVAILABLE?"unavailable":i.error()<8?"normal":i.error()<9?"low":i.error()<10?"high":"safety";}
    boolean applyFeedback(String id,boolean appropriate,long now){
        Decision d=pending.remove(id);
        if(d==null||now>=expires||!training){
            if(now>=expires){pending.clear();decisions=decisions.stream().map(x->new Decision(x.id(),x.sensor(),x.band(),x.action(),false,x.training())).toList();}
            status="Rating ignored: expired, replaced, frozen or already rated";log("rating-ignored","\"id\":"+q(id));return false;
        }
        SoarWarningAgent a=agents.get(d.sensor());double before=a.value(d.band(),d.action());
        if(!a.feedback(appropriate))return false;
        feedbackCount++;decisions=decisions.stream().map(x->x.id().equals(id)?new Decision(x.id(),x.sensor(),x.band(),x.action(),false,x.training()):x).toList();
        status="Rating applied once to "+d.sensor()+" / "+d.band()+" / "+d.action()+". Evaluate again to see the next choice.";
        log("rating","\"id\":"+q(id)+",\"appropriate\":"+appropriate+",\"reward\":"+(appropriate?1:-1)+",\"q_before\":"+before+",\"q_after\":"+a.value(d.band(),d.action())+",\"updates\":"+a.updates(d.band()));publish();return true;
    }
    private void publish(){
        List<Score> scores=new ArrayList<>();for(Sensor s:Sensor.values())for(String b:List.of("low","high")){var a=agents.get(s);scores.add(new Score(s,b,a.value(b,"warn"),a.value(b,"quiet"),a.updates(b)));}
        view=new View(sequence,System.nanoTime(),training,status,inputs.stream().map(i->new Reading(i.sensor(),i.error(),i.availability(),band(i))).toList(),decisions,scores,feedbackCount);
    }
    private void failed(Exception e){failed=true;pending.clear();view=new View(sequence,0,training,"Research unavailable: "+e.getMessage(),List.of(),List.of(),List.of(),feedbackCount);System.err.println(view.status());commands.clear();if(log!=null){log.println("{\"type\":\"error\",\"message\":"+q(e.toString())+"}");log.flush();}}
    private static String q(String s){return ExplainabilityModel.quote(s);}
    private void log(String type,String fields){if(log!=null){log.println("{\"time\":"+q(Instant.now().toString())+",\"type\":"+q(type)+","+fields+"}");log.flush();if(log.checkError())throw new IllegalStateException("Sensor research log write failed");}}
    public void close(){if(closed)return;closed=true;worker.execute(()->{for(var a:agents.values())a.close();agents.clear();if(log!=null)log.close();});worker.shutdown();}
}
