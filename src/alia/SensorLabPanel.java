package alia;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Touch-sized synthetic warning lab. The real master caution remains separate. */
final class SensorLabPanel extends JPanel {
    private final SensorResearch research;
    private final JLabel nativeState=new JLabel("Native aircraft telemetry: unavailable"),status=new JLabel("Starting research worker…"),mode=new JLabel();
    private final EnumMap<SensorResearch.Sensor,JSpinner> errors=new EnumMap<>(SensorResearch.Sensor.class);
    private final EnumMap<SensorResearch.Sensor,JComboBox<SensorResearch.Availability>> availability=new EnumMap<>(SensorResearch.Sensor.class);
    private final EnumMap<SensorResearch.Sensor,JLabel> result=new EnumMap<>(SensorResearch.Sensor.class);
    private final EnumMap<SensorResearch.Sensor,JButton> yes=new EnumMap<>(SensorResearch.Sensor.class),no=new EnumMap<>(SensorResearch.Sensor.class);
    private final Scores scores=new Scores();
    private SensorResearch.View current;
    private final JTabbedPane pages=new JTabbedPane();
    private final JLabel liveInstruments=new JLabel();
    private boolean selectedLive;
    SensorLabPanel(SensorResearch research){
        this.research=research;setLayout(new BorderLayout());setBackground(PilotDisplay.BG);
        JPanel content=new Content();content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS));content.setBackground(PilotDisplay.CARD);content.setBorder(new EmptyBorder(16,16,16,16));
        addLabel(content,"SYNTHETIC SENSOR LAB",21,PilotDisplay.AMBER,true);
        addLabel(content,"GPS / IMU / LiDAR error proxies · no flight authority",13,PilotDisplay.INK,false);
        addLabel(content,"Physical receiver / inertial / LiDAR feeds remain unavailable.",12,PilotDisplay.MUTED,false);
        style(nativeState,12,PilotDisplay.CYAN,false);nativeState.setBorder(new EmptyBorder(10,0,10,0));nativeState.setAlignmentX(LEFT_ALIGNMENT);content.add(nativeState);
        JPanel inputs=new JPanel(new GridLayout(4,3,8,8));inputs.setOpaque(false);inputs.setAlignmentX(LEFT_ALIGNMENT);inputs.setMaximumSize(new Dimension(1800,166));
        for(String h:List.of("Manual input","Error proxy (0–20)","Feed condition")){JLabel l=new JLabel(h);style(l,12,PilotDisplay.MUTED,true);inputs.add(l);}
        for(var s:SensorResearch.Sensor.values()){
            JLabel title=new JLabel(s==SensorResearch.Sensor.LIDAR?"LiDAR proxy":s.name()+" proxy");style(title,16,PilotDisplay.INK,true);inputs.add(title);
            JSpinner value=new JSpinner(new SpinnerNumberModel(s==SensorResearch.Sensor.GPS?8.5:0.0,0.0,20.0,.1));value.setFont(new Font("Segoe UI",Font.BOLD,18));errors.put(s,value);inputs.add(value);
            var feed=new JComboBox<>(SensorResearch.Availability.values());feed.setFont(new Font("Segoe UI",Font.BOLD,12));availability.put(s,feed);inputs.add(feed);
        }
        content.add(inputs);content.add(Box.createVerticalStrut(10));
        JPanel commands=new JPanel(new GridLayout(1,2,8,0));commands.setOpaque(false);commands.setMaximumSize(new Dimension(1800,46));commands.setAlignmentX(LEFT_ALIGNMENT);
        commands.add(button("Evaluate sample",this::submit));
        JCheckBox training=new JCheckBox("Train (explore 20%)",true);training.setOpaque(false);training.setForeground(PilotDisplay.INK);training.setFont(new Font("Segoe UI",Font.BOLD,14));training.addActionListener(e->invoke(()->research.training(training.isSelected())));commands.add(training);content.add(commands);
        addLabel(content,"Unchecked = frozen evaluation. Values stay fixed until Evaluate.",12,PilotDisplay.MUTED,false);
        style(mode,13,PilotDisplay.CYAN,true);mode.setBorder(new EmptyBorder(12,0,6,0));mode.setAlignmentX(LEFT_ALIGNMENT);content.add(mode);
        for(var s:SensorResearch.Sensor.values()){
            JPanel row=new JPanel(new BorderLayout(8,4));row.setOpaque(false);row.setBorder(new EmptyBorder(5,0,5,0));row.setAlignmentX(LEFT_ALIGNMENT);row.setMaximumSize(new Dimension(1800,90));
            JLabel label=new JLabel(s+": waiting");style(label,15,PilotDisplay.INK,true);result.put(s,label);row.add(label,BorderLayout.NORTH);
            JPanel actions=new JPanel(new GridLayout(1,2,8,0));actions.setOpaque(false);
            JButton y=button("Appropriate",()->rate(s,true)),n=button("Should change",()->rate(s,false));yes.put(s,y);no.put(s,n);actions.add(y);actions.add(n);row.add(actions,BorderLayout.CENTER);content.add(row);
        }
        addLabel(content,"Rate the displayed choice within 30 s. Fixed safety states cannot be rated.",12,PilotDisplay.MUTED,false);
        addLabel(content,"LEARNED WARNING PREFERENCES",15,PilotDisplay.CYAN,true);
        scores.setAlignmentX(LEFT_ALIGNMENT);scores.setPreferredSize(new Dimension(500,220));scores.setMinimumSize(new Dimension(200,220));scores.setMaximumSize(new Dimension(1800,220));content.add(scores);
        addLabel(content,"Cyan: warn · gray: quiet. Q values are preferences, not confidence.",12,PilotDisplay.MUTED,false);
        style(status,12,PilotDisplay.AMBER,false);status.setAlignmentX(LEFT_ALIGNMENT);status.setBorder(new EmptyBorder(8,0,8,0));content.add(status);
        JButton reset=button("Reset session learning",()->invoke(research::reset));reset.setAlignmentX(LEFT_ALIGNMENT);reset.setMaximumSize(new Dimension(1800,44));content.add(reset);
        addLabel(content,"Bands 8 / 9 / 10 are legacy research bins, not aircraft safety limits.",12,PilotDisplay.MUTED,false);
        JScrollPane scroll=new JScrollPane(content);scroll.setBorder(null);scroll.getVerticalScrollBar().setUnitIncrement(28);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        liveInstruments.setVerticalAlignment(SwingConstants.TOP);liveInstruments.setFont(new Font("Segoe UI",Font.PLAIN,17));liveInstruments.setForeground(PilotDisplay.INK);liveInstruments.setBorder(new EmptyBorder(18,18,18,18));liveInstruments.setOpaque(true);liveInstruments.setBackground(PilotDisplay.CARD);
        JScrollPane instrumentScroll=new JScrollPane(liveInstruments);instrumentScroll.getVerticalScrollBar().setUnitIncrement(30);instrumentScroll.setBorder(null);
        pages.setFont(new Font("Segoe UI",Font.BOLD,16));pages.addTab("Live instruments",instrumentScroll);pages.addTab("Warning lab",scroll);pages.setSelectedIndex(1);add(pages);
        PilotTheme.apply(this);update();
    }
    private static void style(JLabel l,int size,Color color,boolean bold){l.setFont(new Font("Segoe UI",bold?Font.BOLD:Font.PLAIN,size));l.setForeground(color);}
    private static void addLabel(JPanel p,String text,int size,Color color,boolean bold){JLabel l=new JLabel("<html>"+html(text)+"</html>");style(l,size,color,bold);l.setAlignmentX(LEFT_ALIGNMENT);l.setBorder(new EmptyBorder(3,0,3,0));p.add(l);}
    private static JButton button(String text,Runnable action){JButton b=new JButton(text);b.setFont(new Font("Segoe UI",Font.BOLD,14));b.setBackground(PilotTheme.SELECTED);b.setForeground(PilotDisplay.INK);PilotTheme.button(b);b.setPreferredSize(new Dimension(170,42));b.addActionListener(e->action.run());return b;}
    private void invoke(Runnable action){try{action.run();}catch(RuntimeException e){status.setText(e.getMessage());}}
    private void submit(){List<SensorResearch.Input> values=new ArrayList<>();for(var s:SensorResearch.Sensor.values()){
        try{errors.get(s).commitEdit();}catch(java.text.ParseException e){status.setText("Enter a numeric error proxy from 0 to 20");return;}
        values.add(new SensorResearch.Input(s,((Number)errors.get(s).getValue()).doubleValue(),(SensorResearch.Availability)availability.get(s).getSelectedItem()));
    }invoke(()->research.submit(values));}
    private void rate(SensorResearch.Sensor s,boolean appropriate){if(current!=null)current.decisions().stream().filter(d->d.sensor()==s&&d.rateable()).findFirst().ifPresent(d->invoke(()->research.feedback(d.id(),appropriate)));}
    void nativeState(ExplainabilityModel.Frame frame){
        boolean live=frame!=null&&frame.telemetry().fresh();var s=frame==null?null:frame.telemetry();
        nativeState.setText(live?String.format(Locale.ROOT,"<html>Native X-Plane: %.5f°, %.5f°<br>HDG %.0f° · Pitch %+.1f° · Roll %+.1f° · Battery %s kW</html>",s.pos.lat,s.pos.lon,s.pos.heading,s.pos.pitch,s.pos.roll,Double.isFinite(frame.battery().powerKw())?String.format(Locale.ROOT,"%.1f",frame.battery().powerKw()):"unavailable"):"<html>Native aircraft telemetry: unavailable<br>The synthetic lab works offline.</html>");
        StringBuilder b=new StringBuilder("<html><h3>X-PLANE SIMULATED INSTRUMENTS</h3>Separate instrument channels; shared simulator.<br>Physical independence is not established.<br><br><table cellspacing='0' cellpadding='5'>");
        for(var key:SimulatorSensors.Key.values()){if(key==SimulatorSensors.Key.P)b.append("<tr><td colspan='2'><br><b>Simulator truth — not raw IMU</b></td></tr>");b.append("<tr><td><b>").append(key.label).append("</b></td><td>").append(SimulatorSensors.formatted(s,key)).append("</td></tr>");}
        double residual=SimulatorSensors.attitudeDisagreement(s);b.append("</table><br><b>AHRS pair difference: </b>").append(Double.isFinite(residual)?String.format(Locale.ROOT,"%.3f deg",residual):"UNAVAILABLE");
        b.append("<br><br>GPS values are navigation/status outputs.<br>Independent GPS position: unavailable.<br>Raw inertial sensor: unavailable.<br>LiDAR range: unavailable.<br>Radio altitude is not LiDAR.</html>");liveInstruments.setText(b.toString());
        if(live&&!selectedLive){selectedLive=true;pages.setSelectedIndex(0);}
    }
    void update(){
        current=research.view();mode.setText(current.fresh()?(current.training()?"TRAINING / SYNTHETIC":"FROZEN EVALUATION / SYNTHETIC"):"RESEARCH UNAVAILABLE");
        status.setText("<html>"+html(current.status())+"<br>Ratings: "+current.feedbackCount()+" · logs: research/local/sensor-demo</html>");
        for(var s:SensorResearch.Sensor.values()){
            var d=current.decisions().stream().filter(x->x.sensor()==s).findFirst().orElse(null);
            var r=current.readings().stream().filter(x->x.sensor()==s).findFirst().orElse(null);
            String name=s==SensorResearch.Sensor.LIDAR?"LiDAR":s.name();
            result.get(s).setText(d==null?name+": waiting":String.format(Locale.ROOT,"%s %.2f · %s → %s",name,r.error(),r.availability()==SensorResearch.Availability.AVAILABLE?d.band():r.availability(),d.action().toUpperCase(Locale.ROOT)));
            result.get(s).setForeground(d!=null&&d.band().equals("safety")?PilotDisplay.AMBER:PilotDisplay.INK);
            boolean rateable=current.fresh()&&d!=null&&d.rateable();yes.get(s).setEnabled(rateable);no.get(s).setEnabled(rateable);
        }
        scores.repaint();
    }
    private static String html(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static final class Content extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 28;}
        public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(28,r.height-28);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
    private final class Scores extends JPanel {
        Scores(){setOpaque(false);}
        protected void paintComponent(Graphics original){super.paintComponent(original);Graphics2D g=(Graphics2D)original.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setFont(new Font("Segoe UI",Font.PLAIN,12));
            if(current==null){g.dispose();return;}
            int left=95,right=getWidth()-100,mid=(left+right)/2,half=Math.max(10,(right-left)/2);g.setColor(PilotDisplay.LINE);g.drawLine(mid,2,mid,208);
            int y=20;for(var s:current.scores()){
                g.setColor(PilotDisplay.INK);g.drawString(s.sensor()+" / "+s.band(),0,y+4);
                draw(g,s.warn(),mid,y-5,half,PilotDisplay.CYAN);draw(g,s.quiet(),mid,y+3,half,PilotDisplay.MUTED);
                g.setColor(PilotDisplay.INK);g.drawString(String.format(Locale.ROOT,"%+.2f / %+.2f",s.warn(),s.quiet()),right+8,y+3);
                g.setColor(PilotDisplay.MUTED);g.drawString(s.updates()+" updates",right+8,y+16);y+=34;
            }g.dispose();
        }
        private void draw(Graphics2D g,double value,int mid,int y,int half,Color color){int len=(int)(Math.min(1,Math.abs(value))*half);g.setColor(color);g.fillRect(value<0?mid-len:mid,y,Math.max(1,len),6);}
    }
}
