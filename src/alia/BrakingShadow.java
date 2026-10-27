package alia;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Recommendation-free research monitor. No NativeLink, actuator handle or mutable controller reference. */
final class BrakingShadow implements AutoCloseable {
    record Sample(double elapsed,boolean active,boolean fresh,boolean paused,boolean airport,String computedPhase,
                  String policy,BrakingModel.Observation observation,String runId) {
        Sample(double elapsed,boolean active,boolean fresh,boolean paused,boolean airport,String computedPhase,String policy,BrakingModel.Observation observation){
            this(elapsed,active,fresh,paused,airport,computedPhase,policy,observation,"UNASSIGNED");
        }
    }
    record Result(String status,String detail,BrakingModel.Prediction prediction,double elapsed) {}
    private final BrakingModel model;
    private final String unavailable;
    private final ThreadPoolExecutor writer;
    private final Path folder;
    private PrintWriter journal;
    private final List<Result> history=new ArrayList<>();
    private volatile Result current=new Result("WAITING","Current approach data is required.",null,0);
    private volatile String recordingError="";
    private long lastRecord;
    private double previousElapsed=-1;
    private JFrame frame;
    private final Canvas canvas=new Canvas();
    private final javax.swing.Timer refresh=new javax.swing.Timer(250,e->canvas.repaint());

    BrakingShadow(Path modelFile,Path folder) {
        this.folder=folder;BrakingModel loaded=null;String issue="";
        try{loaded=BrakingModel.load(modelFile);}catch(Exception ex){issue=ex.getMessage();}
        model=loaded;unavailable=issue;
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"braking-shadow-log");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    static Result evaluate(BrakingModel model,Sample s,String missing) {
        if(model==null)return new Result("MODEL_UNAVAILABLE",missing,null,s.elapsed());
        if(!s.fresh())return new Result("STALE","Fresh telemetry is required.",null,s.elapsed());
        if(s.paused())return new Result("PAUSED","Predictions frozen while the simulator is paused.",null,s.elapsed());
        if(!s.active()||!s.airport()||!"APPROACH".equals(s.computedPhase()))return new Result("WAITING_FOR_APPROACH","This pilot model applies during the existing airport braking procedure.",null,s.elapsed());
        if(!BrakingModel.POLICY.equals(s.policy()))return new Result("POLICY_MISMATCH","The observed braking procedure differs from the training contract.",null,s.elapsed());
        try {
            var prediction=model.predict(s.observation());
            return new Result(prediction.supported()?"PILOT_SHADOW":"OUTSIDE_TRAINING_SUPPORT",prediction.supported()?"Point estimate only; uncertainty is unqualified.":String.join(", ",prediction.outsideSupport()),prediction,s.elapsed());
        }catch(RuntimeException ex){return new Result("INVALID_INPUT",ex.getMessage(),null,s.elapsed());}
    }
    void observe(Sample sample) {
        try {
            Result next=evaluate(model,sample,unavailable);current=next;
            synchronized(history){
                if(sample.elapsed()<previousElapsed){history.clear();previousElapsed=-1;}
                if(next.prediction()!=null&&(history.isEmpty()||sample.elapsed()-history.getLast().elapsed()>=.2)){
                    history.add(next);while(history.size()>600)history.removeFirst();
                }
                previousElapsed=sample.elapsed();
            }
            long now=System.nanoTime();
            if(now-lastRecord>=500_000_000L){
                lastRecord=now;
                try{writer.execute(()->record(next,sample));}catch(RejectedExecutionException ex){recordingError="Shadow recording queue full/closed; records dropped";}
            }
        }catch(RuntimeException ex){current=new Result("OBSERVER_ERROR",ex.getMessage(),null,sample.elapsed());}
    }
    Result current(){return current;}
    String recordingError(){return recordingError;}
    List<Result> history(){synchronized(history){return List.copyOf(history);}}
    private void record(Result result,Sample sample) {
        try {
            if(journal==null){Files.createDirectories(folder);Path file=folder.resolve("braking-shadow-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))+".jsonl");journal=new PrintWriter(Files.newBufferedWriter(file));}
            var p=result.prediction();
            String features="null";
            if(sample.observation()!=null){StringJoiner values=new StringJoiner(",","[","]");for(double value:sample.observation().features())values.add(ExplainabilityModel.number(value));features=values.toString();}
            journal.println("{\"schema_version\":1,\"kind\":\"live_shadow\",\"time\":"+ExplainabilityModel.quote(Instant.now().toString())+",\"elapsed_s\":"+ExplainabilityModel.number(result.elapsed())+
                ",\"run_id\":"+ExplainabilityModel.quote(sample.runId())+",\"computed_phase\":"+ExplainabilityModel.quote(sample.computedPhase())+",\"policy\":"+ExplainabilityModel.quote(sample.policy())+
                ",\"active\":"+sample.active()+",\"fresh\":"+sample.fresh()+",\"paused\":"+sample.paused()+",\"feature_order\":"+ExplainabilityModel.quote(BrakingModel.FEATURES)+",\"features\":"+features+
                ",\"status\":"+ExplainabilityModel.quote(result.status())+",\"detail\":"+ExplainabilityModel.quote(result.detail())+
                ",\"model_id\":"+ExplainabilityModel.quote(model==null?"":model.id())+",\"predicted_remaining_distance_m\":"+ExplainabilityModel.number(p==null?Double.NaN:p.distanceM())+
                ",\"predicted_remaining_time_s\":"+ExplainabilityModel.number(p==null?Double.NaN:p.timeS())+",\"uncertainty_qualified\":false,\"flight_control_authorized\":false}");
            journal.flush();if(journal.checkError())recordingError="Could not write shadow journal";
        }catch(Exception ex){recordingError="Shadow recording unavailable: "+ex.getMessage();}
    }
    void open() {
        if(frame==null){frame=new JFrame("CCAT | Braking research - shadow only");frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);frame.setContentPane(canvas);frame.setSize(760,650);frame.setMinimumSize(new Dimension(560,520));frame.setLocationRelativeTo(null);}
        frame.setVisible(true);frame.toFront();refresh.start();
    }
    void render(Path file,int width,int height) throws IOException {
        canvas.setSize(width,height);BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();canvas.paint(g);g.dispose();Files.createDirectories(file.getParent());ImageIO.write(image,"png",file.toFile());
    }
    @Override public void close(){refresh.stop();if(frame!=null)frame.dispose();writer.shutdown();try{if(!writer.awaitTermination(2,TimeUnit.SECONDS))writer.shutdownNow();}catch(InterruptedException ex){Thread.currentThread().interrupt();}if(journal!=null)journal.close();}
    private final class Canvas extends JPanel {
        Canvas(){setBackground(new Color(10,22,36));}
        @Override protected void paintComponent(Graphics graphics){
            super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(),h=getHeight();Result result=current;Color ink=new Color(231,240,250),muted=new Color(155,176,196),cyan=new Color(74,218,233),amber=new Color(255,194,84);
            line(g,"BRAKING PREDICTION / RESEARCH",24,34,21,ink);line(g,"SHADOW ONLY - NO FLIGHT CONTROL AUTHORITY",24,61,14,amber);
            line(g,result.status().replace('_',' '),24,99,19,cyan);line(g,shorten(result.detail(),w/8),24,123,13,muted);
            var p=result.prediction();boolean visible=p!=null&&p.supported();
            line(g,visible?String.format(Locale.ROOT,"%.0f m",p.distanceM()):"-- m",24,173,34,ink);line(g,visible?String.format(Locale.ROOT,"%.1f s",p.timeS()):"-- s",w/2,173,34,ink);
            line(g,"Remaining ground travel to rotor capture",24,198,13,muted);line(g,"GS <10 m/s; forward IAS <30 kt",24,220,13,muted);
            line(g,"Uncertainty unavailable: validation bands failed held-out coverage.",24,251,13,amber);
            line(g,"Conditional on the current procedure; not a full stop / landing prediction.",24,273,12,muted);
            int plotHeight=Math.max(70,(h-370)/2);plot(g,24,300,w-48,plotHeight,true,cyan);plot(g,24,320+plotHeight,w-48,plotHeight,false,amber);
            line(g,shorten(recordingError.isEmpty()?"Models and journals stay in research/local/. Unsupported estimates are hidden.":recordingError,w/7),24,h-24,12,muted);g.dispose();
        }
        private void plot(Graphics2D g,int x,int y,int w,int h,boolean distance,Color color){
            g.setColor(new Color(22,42,61));g.fillRoundRect(x,y,w,h,12,12);line(g,distance?"PREDICTED REMAINING DISTANCE (m) / LAST 60 s":"PREDICTED REMAINING TIME (s) / LAST 60 s",x+12,y+20,11,color);
            List<Result> values=history();if(values.size()<2)return;double end=values.getLast().elapsed(),max=1;
            for(var r:values)if(r.elapsed()>=end-60&&r.prediction().supported())max=Math.max(max,distance?r.prediction().distanceM():r.prediction().timeS());
            Path2D path=new Path2D.Double();boolean begin=true;
            for(var r:values){if(r.elapsed()<end-60||!r.prediction().supported()){begin=true;continue;}double px=x+12+(r.elapsed()-(end-60))/60*(w-24),py=y+h-12-(distance?r.prediction().distanceM():r.prediction().timeS())/max*(h-42);if(begin){path.moveTo(px,py);begin=false;}else path.lineTo(px,py);}
            g.setColor(color);g.setStroke(new BasicStroke(2));g.draw(path);
        }
    }
    private static void line(Graphics2D g,String text,int x,int y,int size,Color color){g.setFont(new Font("Segoe UI",Font.PLAIN,size));g.setColor(color);g.drawString(text,x,y);}
    private static String shorten(String value,int length){if(value==null)return "";return value.length()<=length?value:value.substring(0,Math.max(0,length-3))+"...";}
}
