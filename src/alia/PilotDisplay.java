package alia;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/** Separate touch-oriented pilot display. Alternate routes are previews and never control the aircraft. */
final class PilotDisplay extends JPanel {
    static final Color BG=PilotTheme.BASE,CARD=PilotTheme.SURFACE,LINE=PilotTheme.LINE,INK=PilotTheme.TEXT,MUTED=PilotTheme.MUTED,CYAN=new Color(79,219,235),GREEN=new Color(91,224,171),AMBER=new Color(255,192,73),RED=new Color(255,105,112);
    private final Runnable pause,controls;
    private final Supplier<Path> sceneryRoot;
    private final JLabel connection=new JLabel("CONNECTING"),stage=new JLabel("PILOT DISPLAY");
    private final Banner banner=new Banner();
    private final ControlAttribution attribution=new ControlAttribution();
    private ControlAttribution.Channel attributionChannel=ControlAttribution.Channel.PITCH;
    private final FlightPathMap navigationMap=new FlightPathMap(true);
    private final Screen screen=new Screen();
    private final List<JButton> tabs=new ArrayList<>();
    private ExplainabilityModel.Frame data;
    private SensorLabPanel sensorLab;
    private boolean offlineResearch;
    private AirportPlan destination,alternate;
    private AirportDatabase database;
    private Path databaseRoot;
    private JFrame window;
    private JDialog alternateDialog;
    private Rectangle normalBounds;
    private boolean fullScreen,preview;
    private String page="Navigation";
    private final javax.swing.Timer freshness=new javax.swing.Timer(250,e->refresh());

    PilotDisplay(Runnable pause,Runnable controls,Supplier<Path> sceneryRoot) {
        this.pause=pause;this.controls=controls;this.sceneryRoot=sceneryRoot;
        setLayout(new BorderLayout(0,0));setBackground(BG);setBorder(new EmptyBorder(12,16,12,16));
        JPanel top=panel(new BorderLayout(8,0));top.setBorder(new EmptyBorder(3,4,12,4));
        JPanel brand=panel(new GridLayout(2,1));JLabel name=label("CCAT  /  PILOT",20,Font.BOLD,INK);brand.add(name);stage.setFont(font(12,Font.BOLD));stage.setForeground(MUTED);brand.add(stage);top.add(brand,BorderLayout.WEST);
        JPanel tools=panel(new FlowLayout(FlowLayout.RIGHT,8,0));connection.setFont(font(12,Font.BOLD));connection.setForeground(GREEN);tools.add(connection);
        JButton flightControls=button("Controls",controls,CARD,INK);flightControls.setFont(font(13,Font.BOLD));flightControls.setPreferredSize(new Dimension(88,44));tools.add(flightControls);
        JButton full=button("Full screen",this::toggleFullScreen,CARD,INK);full.setFont(font(13,Font.BOLD));full.setPreferredSize(new Dimension(112,44));tools.add(full);top.add(tools,BorderLayout.EAST);
        JPanel north=panel(new BorderLayout());north.add(top,BorderLayout.NORTH);north.add(banner,BorderLayout.CENTER);add(north,BorderLayout.NORTH);
        add(screen,BorderLayout.CENTER);
        JPanel bottom=panel(new BorderLayout(0,10));bottom.setBorder(new EmptyBorder(10,0,0,0));
        JPanel navigation=panel(new GridLayout(1,4,8,0));
        for(String title:new String[]{"Navigation","Sensors","Weather","Explain"}){JButton b=button(title,()->showPage(title),CARD,MUTED);b.setPreferredSize(new Dimension(120,54));navigation.add(b);tabs.add(b);}
        bottom.add(navigation,BorderLayout.NORTH);
        JPanel actions=panel(new GridLayout(1,2,10,0));JButton reroute=button("Reroute preview",this::chooseAlternate,PilotTheme.SELECTED,INK);reroute.setPreferredSize(new Dimension(240,58));
        actions.add(reroute);actions.add(button("Pause & take over",pause,new Color(106,35,47),INK));bottom.add(actions,BorderLayout.CENTER);add(bottom,BorderLayout.SOUTH);
        setPreferredSize(new Dimension(700,1000));showPage("Navigation");PilotTheme.apply(this);
    }
    void open(){
        if(window==null){
            window=new JFrame("CCAT | Pilot tablet");window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);window.setContentPane(this);
            Rectangle bounds=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();window.setSize(Math.min(740,bounds.width),Math.min(1060,bounds.height));window.setMinimumSize(new Dimension(540,680));window.setLocationRelativeTo(null);
            window.addComponentListener(new ComponentAdapter(){public void componentHidden(ComponentEvent e){freshness.stop();}});
            getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F11"),"full");getActionMap().put("full",new AbstractAction(){public void actionPerformed(ActionEvent e){toggleFullScreen();}});
            getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"),"exitFull");getActionMap().put("exitFull",new AbstractAction(){public void actionPerformed(ActionEvent e){if(fullScreen)toggleFullScreen();}});
        }
        window.setVisible(true);window.toFront();freshness.start();refresh();
    }
    void close(){freshness.stop();if(window!=null)window.dispose();}
    void update(ExplainabilityModel.Frame data,AirportPlan destination){this.data=data;this.destination=destination;attribution.update(data);refresh();}
    void sensorResearch(SensorResearch research){sensorLab=new SensorLabPanel(research);screen.add(sensorLab);screen.revalidate();refresh();}
    void offlineResearch(){offlineResearch=true;refresh();}
    void mapPreview(){preview=true;refresh();}
    void showPage(String name){page=name;for(JButton b:tabs){boolean selected=b.getText().equals(name);b.setBackground(selected?PilotTheme.SELECTED:CARD);b.setForeground(selected?CYAN:MUTED);}screen.revalidate();screen.repaint();}
    void showAttributionChannel(ControlAttribution.Channel channel){attributionChannel=channel;screen.styleChannels();screen.repaint();}
    private void refresh(){
        var alert=offlineResearch?new PilotDisplayModel.Alert(PilotDisplayModel.Tone.INFO,"OFFLINE SENSOR LAB","Synthetic warning learning demonstration.","No aircraft connection. No flight controls are sent."):PilotDisplayModel.alert(data);banner.update(alert,System.nanoTime());
        boolean live=data!=null&&data.telemetry().fresh();connection.setText(preview?"DEMO / OFFLINE":live?"● LIVE":"● NO DATA");connection.setForeground(preview?AMBER:live?GREEN:AMBER);
        stage.setText(data==null?"WAITING FOR AIRCRAFT":data.phase().replace('_',' '));screen.repaint();
        if(offlineResearch){connection.setText("SENSOR DEMO");stage.setText("OFFLINE RESEARCH");}
        if(sensorLab!=null){sensorLab.nativeState(data);sensorLab.update();}
        navigationMap.update(data,destination,alternate);
    }
    private void toggleFullScreen(){
        if(window==null)return;
        window.dispose();
        if(!fullScreen){normalBounds=window.getBounds();window.setUndecorated(true);window.setBounds(window.getGraphicsConfiguration().getBounds());}
        else{window.setUndecorated(false);window.setBounds(normalBounds);}
        fullScreen=!fullScreen;window.setVisible(true);freshness.start();
    }
    private static JPanel panel(LayoutManager layout){JPanel p=new JPanel(layout);p.setOpaque(false);return p;}
    private static Font font(int size,int weight){return new Font("Segoe UI",weight,size);}
    private static JLabel label(String s,int size,int weight,Color color){JLabel l=new JLabel(s);l.setFont(font(size,weight));l.setForeground(color);return l;}
    private static JButton button(String text,Runnable action,Color bg,Color fg){
        JButton b=new JButton(text);b.setBackground(bg);b.setForeground(fg);b.setFont(font(17,Font.BOLD));PilotTheme.button(b);b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),new EmptyBorder(9,10,9,10)));b.addActionListener(e->action.run());return b;
    }
    private static void text(Graphics2D g,String text,int x,int y,int size,Color color,boolean bold){g.setFont(font(size,bold?Font.BOLD:Font.PLAIN));g.setColor(color);g.drawString(text,x,y);}
    private static int wrap(Graphics2D g,String value,int x,int y,int width,int size,Color color,int maxLines){
        g.setFont(font(size,Font.PLAIN));g.setColor(color);FontMetrics fm=g.getFontMetrics();String line="";int lines=0;
        for(String word:value.split("\\s+")) {
            String next=line.isEmpty()?word:line+" "+word;
            if(fm.stringWidth(next)>width&&!line.isEmpty()){
                if(++lines==maxLines){g.drawString(ellipsize(g,line+" "+word,width),x,y);return y+fm.getHeight();}
                g.drawString(line,x,y);y+=fm.getHeight();line=word;
            }else line=next;
        }
        if(!line.isEmpty())g.drawString(ellipsize(g,line,width),x,y);return y+fm.getHeight();
    }
    private static String ellipsize(Graphics2D g,String s,int width){if(g.getFontMetrics().stringWidth(s)<=width)return s;while(s.length()>1&&g.getFontMetrics().stringWidth(s+"…")>width)s=s.substring(0,s.length()-1);return s+"…";}
    private static void card(Graphics2D g,int x,int y,int w,int h){g.setColor(CARD);g.fillRoundRect(x,y,w,h,18,18);g.setColor(LINE);g.drawRoundRect(x,y,w-1,h-1,18,18);}
    private static String number(double n,String format){return Double.isFinite(n)?String.format(Locale.ROOT,format,n):"—";}

    record ExplainLayout(Rectangle intent,Rectangle controls,Rectangle contributions,Rectangle trend,Rectangle metric) {}
    static ExplainLayout explainLayout(int width,int height){
        int w=Math.max(0,width),h=Math.max(160,height);
        if(w>850){
            int left=(int)(w*.30),x=left+12,right=w-x,chartW=(right-10)/2;
            return new ExplainLayout(new Rectangle(0,0,left,h),new Rectangle(x,0,right,44),
                new Rectangle(x,54,chartW,h-112),new Rectangle(x+chartW+10,54,right-chartW-10,h-112),new Rectangle(x,h-48,right,48));
        }
        if(h>=520)return new ExplainLayout(new Rectangle(0,0,w,110),new Rectangle(0,120,w,44),
            new Rectangle(0,174,w,146),new Rectangle(0,330,w,h-388),new Rectangle(0,h-48,w,48));
        int chartW=(w-10)/2;
        if(h<300)return new ExplainLayout(new Rectangle(0,0,w,0),new Rectangle(0,0,w,44),
            new Rectangle(0,54,chartW,h-112),new Rectangle(chartW+10,54,w-chartW-10,h-112),new Rectangle(0,h-48,w,48));
        return new ExplainLayout(new Rectangle(0,0,w,68),new Rectangle(0,78,w,44),
            new Rectangle(0,132,chartW,h-190),new Rectangle(chartW+10,132,w-chartW-10,h-190),new Rectangle(0,h-48,w,48));
    }

    static final class Banner extends JPanel {
        static final int HEIGHT=134;
        private final PilotAlertState presentation=new PilotAlertState();
        private PilotDisplayModel.Alert alert=PilotDisplayModel.alert(null);
        Banner(){setName("pilot-alert");setOpaque(true);setBackground(BG);setPreferredSize(new Dimension(500,HEIGHT));}
        void update(PilotDisplayModel.Alert value,long now){var next=presentation.update(value,now);if(!next.equals(alert)){alert=next;repaint();}}
        protected void paintComponent(Graphics original){
            // Always erase the old text, triangle and rounded edges on a partial repaint.
            super.paintComponent(original);
            Graphics2D g=(Graphics2D)original.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            boolean normal=alert.tone()==PilotDisplayModel.Tone.NORMAL,warning=alert.tone()==PilotDisplayModel.Tone.WARNING,caution=alert.tone()==PilotDisplayModel.Tone.CAUTION;
            Color accent=warning?RED:caution?AMBER:CYAN;
            if(normal){
                card(g,0,0,getWidth(),getHeight()-4);g.setColor(GREEN);g.fillOval(19,24,8,8);
                text(g,"CCAT STATUS",37,33,12,GREEN,true);text(g,alert.detail(),19,67,22,INK,true);
                text(g,"No current CCAT caution.",19,97,14,MUTED,false);g.dispose();return;
            }
            g.setColor(warning?new Color(65,26,38):caution?new Color(63,46,21):CARD);g.fillRoundRect(0,0,getWidth(),getHeight()-4,16,16);
            g.setColor(accent);g.setStroke(new BasicStroke(2));g.drawRoundRect(1,1,getWidth()-3,getHeight()-6,16,16);
            Polygon triangle=new Polygon(new int[]{34,9,59},new int[]{23,71,71},3);g.fillPolygon(triangle);text(g,"!",28,63,32,BG,true);
            text(g,alert.title(),78,32,24,accent,true);
            int y=wrap(g,alert.detail(),78,57,getWidth()-96,17,INK,2);
            wrap(g,alert.action(),78,y+3,getWidth()-96,14,MUTED,2);g.dispose();
        }
    }
    private final class Screen extends JPanel {
        private final JPanel channels=panel(new GridLayout(1,4,6,0));
        Screen(){
            setLayout(null);setOpaque(true);setBackground(BG);
            add(navigationMap);
            for(var channel:ControlAttribution.Channel.values()){
                JButton b=button(channel.label,()->showAttributionChannel(channel),CARD,MUTED);b.setName("attribution-"+channel.name());b.setFont(font(15,Font.BOLD));channels.add(b);
            }
            channels.setName("attribution-channels");add(channels);styleChannels();
        }
        private void styleChannels(){for(Component c:channels.getComponents()){JButton b=(JButton)c;boolean selected=b.getText().equals(attributionChannel.label);b.setBackground(selected?PilotTheme.SELECTED:CARD);b.setForeground(selected?CYAN:MUTED);}}
        public void doLayout(){
            navigationMap.setVisible(page.equals("Navigation"));
            if(navigationMap.isVisible()){int w=getWidth(),h=getHeight()-100,mapW=w>850?(int)(w*.62):w,mapH=mapHeight(w,h);navigationMap.setBounds(1,168,mapW-2,Math.max(50,mapH-92));}
            if(sensorLab!=null){sensorLab.setVisible(page.equals("Sensors"));sensorLab.setBounds(0,100,getWidth(),Math.max(0,getHeight()-100));}
            channels.setVisible(page.equals("Explain"));
            if(channels.isVisible()){Rectangle r=explainLayout(getWidth(),getHeight()-100).controls();channels.setBounds(r.x,r.y+100,r.width,r.height);}
        }
        protected void paintComponent(Graphics original){
            super.paintComponent(original);Graphics2D g=(Graphics2D)original.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            boolean live=data!=null&&data.telemetry().fresh();int w=getWidth(),h=getHeight(),gap=10,tw=(w-2*gap)/3;
            double agl=live?data.telemetry().pos.agl*3.28084:Double.NaN,ias=live?data.telemetry().refs[2]:Double.NaN,battery=live?data.battery().chargePercent():Double.NaN;
            metric(g,0,8,tw,80,"HEIGHT AGL",number(agl,"%.0f")+" ft",INK);
            metric(g,tw+gap,8,tw,80,"AIRSPEED",number(ias,"%.0f")+" kt",INK);
            metric(g,2*(tw+gap),8,w-2*(tw+gap),80,"BATTERY",number(battery,"%.0f")+"%",Double.isFinite(battery)&&data.battery().minimumPercent()<20?AMBER:GREEN);
            Graphics2D body=(Graphics2D)g.create();body.translate(0,100);int bh=h-100;
            switch(page){case "Sensors"->{if(sensorLab==null)sensors(body,w,bh);}case "Weather"->weather(body,w,bh);case "Explain"->liveExplanation(body,w,bh);default->navigation(body,w,bh);}
            body.dispose();g.dispose();
        }
        private void metric(Graphics2D g,int x,int y,int w,int h,String title,String value,Color color){card(g,x,y,w,h);text(g,title,x+14,y+23,12,MUTED,true);text(g,value,x+14,y+61,w<180?27:32,color,true);}
        private void navigation(Graphics2D g,int w,int h){
            boolean landscape=w>850;
            int mapW=landscape?(int)(w*.62):w,mapH=mapHeight(w,h);
            navigationMap(g,0,0,mapW,mapH);
            if(landscape)explanation(g,mapW+12,0,w-mapW-12,h,false);else if(h>=380)explanation(g,0,mapH+10,w,h-mapH-10,false);
        }
        private void navigationMap(Graphics2D original,int x,int y,int w,int h){
            card(original,x,y,w,h);Graphics2D g=(Graphics2D)original.create();g.translate(x,y);g.clip(new RoundRectangle2D.Double(1,1,w-2,h-2,18,18));
            String title=destination==null?"LOCAL MISSION":destination.airport().id()+"  /  "+destination.airport().name();
            g.setFont(font(21,Font.BOLD));text(g,ellipsize(g,title,w-34),16,30,21,INK,true);
            double distance=PilotDisplayModel.distance(data,destination);
            String subtitle=destination!=null?number(distance/1000,"%.1f km")+"  ·  ETA "+PilotDisplayModel.eta(data,destination)+" at current GS":data!=null&&data.telemetry().pos!=null?String.format(Locale.ROOT,"%.5f°, %.5f°",data.telemetry().pos.lat,data.telemetry().pos.lon):"Waiting for live position";
            text(g,subtitle,16,54,13,MUTED,false);
            text(g,alternate==null?"DASHED PLAN · SOLID RECENT TRACK · NORTH UP":"AMBER = ALTERNATE PREVIEW ONLY",16,h-9,11,alternate==null?MUTED:AMBER,true);g.dispose();
        }
        private int mapHeight(int w,int h){return w>850||h<380?h:Math.max(220,(int)(h*.65));}
        private void explanation(Graphics2D g,int x,int y,int w,int h,boolean expanded){
            if(h<80)return;card(g,x,y,w,h);var d=PilotDisplayModel.decision(data);int inset=16,yy=y+25;
            text(g,"WHY THIS ACTION",x+inset,yy,12,CYAN,true);yy+=28;
            yy=wrap(g,d.action(),x+inset,yy,w-32,expanded?28:21,INK,2)+8;
            yy=wrap(g,d.reason(),x+inset,yy,w-32,expanded?22:16,MUTED,expanded?4:2)+6;
            if(yy+38<y+h){text(g,"NEXT",x+inset,yy,12,CYAN,true);wrap(g,d.next(),x+inset,yy+24,w-32,expanded?21:16,INK,expanded?4:2);}
            if(expanded&&h>340){text(g,"EXPLANATION SOURCE",x+inset,y+h-88,12,MUTED,true);wrap(g,"Current controller stage and measured aircraft response. Independent sensor confidence is unavailable.",x+inset,y+h-62,w-32,16,MUTED,3);}
        }
        private void liveExplanation(Graphics2D g,int w,int h){
            ExplainLayout l=explainLayout(w,h);Rectangle intent=l.intent();
            if(intent.height>0){card(g,intent.x,intent.y,intent.width,intent.height);
            var decision=PilotDisplayModel.decision(data);text(g,"WHY THIS ACTION",intent.x+14,intent.y+22,11,CYAN,true);
            int baseline=intent.y+48;
            int next=wrap(g,decision.action(),intent.x+14,baseline,intent.width-28,intent.height<90?19:22,INK,2);
            if(next+22<intent.y+intent.height)wrap(g,decision.reason(),intent.x+14,next+5,intent.width-28,15,MUTED,2);
            if(intent.height>200)wrap(g,"Next: "+decision.next(),intent.x+14,next+75,intent.width-28,17,INK,4);
            }
            Optional<ControlAttribution.Result> current=ControlAttribution.live(data)?ControlAttribution.evaluate(data.output(),attributionChannel):Optional.empty();
            drawContributions(g,l.contributions(),current.orElse(null));drawAttributionTrend(g,l.trend());
            Rectangle metric=l.metric();card(g,metric.x,metric.y,metric.width,metric.height);
            String error=current.isPresent()?number(current.get().errorPp(),"%.4f pp"):"—";
            text(g,"TRACE CLOSURE ERROR",metric.x+12,metric.y+18,11,MUTED,true);
            g.setFont(font(18,Font.BOLD));int valueWidth=g.getFontMetrics().stringWidth(error);text(g,error,metric.x+metric.width-valueWidth-12,metric.y+22,18,current.isPresent()?CYAN:MUTED,true);
            text(g,"Rebuilt sum vs command · not flight accuracy / confidence",metric.x+12,metric.y+40,metric.width<600?10:12,MUTED,false);
        }
        private void drawContributions(Graphics2D g,Rectangle r,ControlAttribution.Result result){
            card(g,r.x,r.y,r.width,r.height);text(g,"CONTROL ATTRIBUTION",r.x+12,r.y+20,11,CYAN,true);
            text(g,attributionChannel.label+" · signed percentage points",r.x+12,r.y+37,r.width<330?10:12,MUTED,false);
            if(result==null){wrap(g,"No current control trace. History is retained below.",r.x+12,r.y+63,r.width-24,14,MUTED,3);return;}
            List<ControlAttribution.Contribution> parts=result.contributions();
            if(parts.size()==5&&r.height<140){parts=List.of(parts.get(0),new ControlAttribution.Contribution("Vertical P + I",parts.get(1).value()+parts.get(2).value()),new ControlAttribution.Contribution("Limits + ramp",parts.get(3).value()+parts.get(4).value()));}
            double max=.01;for(var part:parts)max=Math.max(max,Math.abs(part.value()));
            int row=Math.max(16,(r.height-48)/parts.size()),barWidth=r.width-28,zero=r.x+r.width/2,yy=r.y+48;
            for(var part:parts){
                text(g,part.name(),r.x+12,yy+11,r.width<330?10:12,INK,false);
                double pp=part.value()*100;String value=number(Math.abs(pp)<.05?0:pp,"%+.1f pp");g.setFont(font(r.width<330?10:12,Font.BOLD));text(g,value,r.x+r.width-12-g.getFontMetrics().stringWidth(value),yy+11,r.width<330?10:12,INK,true);
                int barY=yy+row-5;g.setColor(LINE);g.fillRoundRect(r.x+14,barY,barWidth,4,4,4);
                int length=(int)(Math.abs(part.value())/max*(barWidth/2.0));g.setColor(part.value()<0?AMBER:CYAN);g.fillRect(part.value()<0?zero-length:zero,barY,length,4);
                g.setColor(MUTED);g.drawLine(zero,barY-1,zero,barY+5);yy+=row;
            }
        }
        private void drawAttributionTrend(Graphics2D original,Rectangle r){
            card(original,r.x,r.y,r.width,r.height);Graphics2D g=(Graphics2D)original.create();g.clipRect(r.x+1,r.y+1,r.width-2,r.height-2);
            text(g,"COMMAND / RECONSTRUCTION",r.x+12,r.y+20,r.width<330?10:11,CYAN,true);
            boolean live=ControlAttribution.live(data);String status=ControlAttribution.status(data);
            if(live&&ControlAttribution.evaluate(data.output(),attributionChannel).isEmpty())status="INCOMPLETE TRACE · HISTORY FROZEN";
            text(g,status,r.x+12,r.y+37,r.width<330?9:11,MUTED,false);
            int left=r.x+35,right=r.x+r.width-12,top=r.y+53,bottom=r.y+r.height-35;
            if(bottom<=top){g.dispose();return;}
            var channel=attributionChannel;double range=channel.maximum-channel.minimum;
            g.setColor(LINE);for(int i=0;i<3;i++){int y=top+i*(bottom-top)/2;g.drawLine(left,y,right,y);text(g,number((channel.maximum-i*range/2)*100,"%.0f"),r.x+5,y+4,10,MUTED,false);}
            text(g,"−60s",left,bottom+16,10,MUTED,false);text(g,"now",right-23,bottom+16,10,MUTED,false);
            List<ControlAttribution.Sample> samples=attribution.samples();
            if(samples.isEmpty()){wrap(g,"Live trace appears during automatic flight.",left,top+24,Math.max(50,right-left),13,MUTED,3);g.dispose();return;}
            double end=samples.stream().filter(s->s.channels().containsKey(channel)).mapToDouble(ControlAttribution.Sample::seconds).max().orElse(samples.getLast().seconds());
            Graphics2D plot=(Graphics2D)g.create();plot.clipRect(left,top,right-left+1,bottom-top+1);
            for(int series=0;series<2;series++){
                Path2D path=new Path2D.Double();boolean first=true;
                for(var sample:samples){var value=sample.channels().get(channel);if(value==null){first=true;continue;}
                    double v=series==0?value.command():value.reconstructed();double x=left+(sample.seconds()-(end-60))/60*(right-left),y=bottom-(v-channel.minimum)/range*(bottom-top);
                    if(first){path.moveTo(x,y);first=false;}else path.lineTo(x,y);
                }
                plot.setColor(series==0?CYAN:AMBER);plot.setStroke(series==0?new BasicStroke(3):new BasicStroke(1.5f,0,0,1,new float[]{5,5},0));plot.draw(path);
            }
            plot.dispose();text(g,"Command",left,r.y+r.height-7,10,CYAN,false);text(g,"Rebuilt",left+65,r.y+r.height-7,10,AMBER,false);g.dispose();
        }
        private void sensors(Graphics2D g,int w,int h){
            card(g,0,0,w,h);text(g,"AIRCRAFT & SENSORS",18,32,22,INK,true);text(g,"X-Plane measurements · no inferred confidence",18,56,14,MUTED,false);
            boolean live=data!=null&&data.telemetry().fresh();double weakest=live?data.battery().minimumPercent():Double.NaN;
            boolean compact=h<430;int row=Math.min(75,Math.max(42,(h-100)/(compact?4:6))),yy=77;
            sensorRow(g,w,yy,row,"Position / motion",live?"LIVE":"NO DATA",live?String.format(Locale.ROOT,"%.5f°, %.5f°",data.telemetry().pos.lat,data.telemetry().pos.lon):"Awaiting a fresh aircraft sample",live?GREEN:AMBER);yy+=row;
            sensorRow(g,w,yy,row,"Attitude / airspeed",live?"LIVE":"NO DATA",live?String.format(Locale.ROOT,"HDG %.0f°T  ·  Pitch %+.1f°  ·  Roll %+.1f°",data.telemetry().pos.heading,data.telemetry().pos.pitch,data.telemetry().pos.roll):"—",live?GREEN:AMBER);yy+=row;
            int on=0;if(live)for(int i=13;i<=17;i++)if(data.telemetry().refs[i]==1)on++;
            sensorRow(g,w,yy,row,"Battery power",live?on+" / 5 ON":"NO DATA","Lowest pack "+number(weakest,"%.0f")+"%",live&&on==5?GREEN:AMBER);yy+=row;
            var sensors=data==null?null:data.telemetry();double delta=SimulatorSensors.attitudeDisagreement(sensors);
            String radio=SimulatorSensors.formatted(sensors,SimulatorSensors.Key.RADIO_ALT_1);
            if(compact){sensorRow(g,w,yy,row,"Simulated instruments",Double.isFinite(delta)?"LIVE":"UNAVAILABLE","AHRS Δ "+number(delta,"%.2f")+"° · Radio "+radio+" · No LiDAR / GPS position",Double.isFinite(delta)?CYAN:MUTED);return;}
            sensorRow(g,w,yy,row,"Simulated AHRS",Double.isFinite(delta)?"LIVE":"UNAVAILABLE","Pair pitch/roll difference "+number(delta,"%.3f")+"°; shared simulator source",CYAN);yy+=row;
            sensorRow(g,w,yy,row,"Radio altimeter","SIMULATED",radio+" · radio altitude is not LiDAR",CYAN);yy+=row;
            sensorRow(g,w,yy,row,"GPS navigation","STATUS ONLY","Fault codes "+number(SimulatorSensors.value(sensors,SimulatorSensors.Key.GPS_FAILURE_1),"%.0f")+" / "+number(SimulatorSensors.value(sensors,SimulatorSensors.Key.GPS_FAILURE_2),"%.0f")+" · Independent GPS position unavailable",MUTED);
        }
        private void sensorRow(Graphics2D g,int w,int y,int h,String title,String status,String detail,Color color){
            int titleY=h<48?20:23,detailY=h<48?36:45;
            g.setColor(LINE);g.drawLine(18,y+h-2,w-18,y+h-2);text(g,title,18,y+titleY,w<600?18:21,INK,true);
            g.setFont(font(w<600?12:14,Font.BOLD));int sw=g.getFontMetrics().stringWidth(status);text(g,status,w-18-sw,y+titleY,w<600?12:14,color,true);
            text(g,detail,18,y+detailY,h<48?12:14,MUTED,false);
        }
        private void weather(Graphics2D g,int w,int h){
            card(g,0,0,w,h);text(g,"WEATHER AT AIRCRAFT",18,33,22,INK,true);text(g,"Simulator conditions · not destination weather",18,58,14,MUTED,false);
            PilotWeather weather=PilotWeather.from(data==null?null:data.telemetry());int row=Math.min(92,Math.max(53,(h-125)/4)),yy=82;
            String wind=weather.windAvailable()?(weather.windKnots()<.5?"Calm":String.format(Locale.ROOT,"%03.0f°T  /  %.0f kt",weather.windFromTrue(),weather.windKnots())):"UNAVAILABLE";
            if(h<400){
                int tileW=(w-48)/2,tileH=(h-100)/2;
                weatherTile(g,18,78,tileW,tileH,"WIND FROM",wind,weather.windAvailable());
                weatherTile(g,30+tileW,78,tileW,tileH,"VISIBILITY",number(weather.visibilitySm(),"%.1f sm"),Double.isFinite(weather.visibilitySm()));
                weatherTile(g,18,90+tileH,tileW,tileH,"OUTSIDE TEMPERATURE",number(weather.temperatureC(),"%.0f °C"),Double.isFinite(weather.temperatureC()));
                weatherTile(g,30+tileW,90+tileH,tileW,tileH,"WINDSHIELD RAIN",Double.isFinite(weather.rainRatio())?(weather.rainRatio()<.01?"None":number(weather.rainRatio()*100,"%.0f%% wetness")):"UNAVAILABLE",Double.isFinite(weather.rainRatio()));
                return;
            }
            weatherRow(g,w,yy,row,"WIND FROM",wind,weather.windAvailable());yy+=row;
            weatherRow(g,w,yy,row,"VISIBILITY",number(weather.visibilitySm(),"%.1f sm"),Double.isFinite(weather.visibilitySm()));yy+=row;
            weatherRow(g,w,yy,row,"OUTSIDE TEMPERATURE",number(weather.temperatureC(),"%.0f °C"),Double.isFinite(weather.temperatureC()));yy+=row;
            weatherRow(g,w,yy,row,"WINDSHIELD RAIN",Double.isFinite(weather.rainRatio())?(weather.rainRatio()<.01?"None":number(weather.rainRatio()*100,"%.0f%% wetness")):"UNAVAILABLE",Double.isFinite(weather.rainRatio()));
            if(yy+row+45<h)wrap(g,"Unavailable weather stays blank. This display does not assess weather suitability or forecast the route.",18,yy+row+25,w-36,14,MUTED,2);
        }
        private void weatherTile(Graphics2D g,int x,int y,int w,int h,String title,String value,boolean available){g.setColor(PilotTheme.SELECTED);g.fillRoundRect(x,y,w,h,12,12);text(g,title,x+12,y+23,11,MUTED,true);g.setFont(font(w<240?22:26,Font.BOLD));text(g,ellipsize(g,value,w-24),x+12,y+Math.min(62,h-12),w<240?22:26,available?INK:MUTED,true);}
        private void weatherRow(Graphics2D g,int w,int y,int h,String title,String value,boolean available){g.setColor(LINE);g.drawLine(18,y+h-4,w-18,y+h-4);text(g,title,18,y+17,12,MUTED,true);text(g,value,18,y+50,w<600?27:31,available?INK:MUTED,true);}
    }
    private static double[] local(AirportPlan plan,NativeLink.Position origin){return new double[]{Math.toRadians(plan.site().latitude()-origin.lat)*6371000,Math.toRadians(HoverMission.wrap(plan.site().longitude()-origin.lon))*6371000*Math.cos(Math.toRadians(origin.lat))};}

    private void chooseAlternate(){
        if(alternateDialog!=null&&alternateDialog.isDisplayable()){alternateDialog.toFront();return;}
        JDialog dialog=new JDialog(window,"Alternate route — preview only",Dialog.ModalityType.MODELESS);JPanel content=new JPanel(new BorderLayout(10,12));content.setBackground(BG);content.setBorder(new EmptyBorder(18,18,18,18));
        alternateDialog=dialog;dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel heading=panel(new BorderLayout(0,10));heading.add(label("ALTERNATE ROUTE",23,Font.BOLD,INK),BorderLayout.NORTH);
        JTextArea note=new JTextArea("Preview only — the active mission stays unchanged.\nSearch airports with paved landing sites at least 25 m across.");note.setEditable(false);note.setLineWrap(true);note.setWrapStyleWord(true);note.setFont(font(16,Font.PLAIN));note.setForeground(MUTED);note.setBackground(BG);heading.add(note,BorderLayout.CENTER);
        JTextField query=new JTextField();query.setFont(font(22,Font.PLAIN));query.setPreferredSize(new Dimension(400,52));heading.add(query,BorderLayout.SOUTH);content.add(heading,BorderLayout.NORTH);
        DefaultListModel<AirportDatabase.Airport> items=new DefaultListModel<>();JList<AirportDatabase.Airport> list=new JList<>(items);list.setBackground(CARD);list.setForeground(INK);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setFixedCellHeight(78);
        list.setCellRenderer((view,a,index,selected,focus)->{JPanel p=new JPanel(new GridLayout(2,1));p.setBorder(new EmptyBorder(8,12,8,12));p.setBackground(selected?PilotTheme.SELECTED:CARD);p.add(label(a.id()+"  /  "+a.name(),19,Font.BOLD,INK));var site=a.sites().stream().filter(AirportDatabase.Site::preferred).findFirst().orElse(null);NativeLink.Position pos=data==null?null:data.telemetry().pos;String distance=pos==null?"":String.format(Locale.ROOT,"%.1f km · ",AirportDatabase.distance(pos.lat,pos.lon,a.latitude(),a.longitude())/1000);p.add(label(distance+(site==null?"No eligible site":site.description()),13,Font.PLAIN,MUTED));return p;});
        JScrollPane scroll=new JScrollPane(list);scroll.setBorder(BorderFactory.createLineBorder(LINE));content.add(scroll,BorderLayout.CENTER);
        JLabel status=label("Loading installed airports…",14,Font.PLAIN,MUTED);
        JButton choose=button("Preview route",()->{var a=list.getSelectedValue();if(a==null)return;var site=a.sites().stream().filter(AirportDatabase.Site::preferred).findFirst().orElseThrow();alternate=new AirportPlan(a,site,destination==null?300:destination.cruiseClearanceM(),destination==null?90:destination.cruiseKnots());refresh();showPage("Navigation");dialog.dispose();},PilotTheme.SELECTED,INK);choose.setEnabled(false);
        JPanel buttons=panel(new GridLayout(1,3,8,0));buttons.add(button("Cancel",dialog::dispose,CARD,INK));buttons.add(button("Clear preview",()->{alternate=null;screen.repaint();dialog.dispose();},CARD,INK));buttons.add(choose);buttons.setPreferredSize(new Dimension(560,58));
        JPanel south=panel(new BorderLayout(0,12));south.add(status,BorderLayout.NORTH);south.add(buttons,BorderLayout.SOUTH);content.add(south,BorderLayout.SOUTH);
        Runnable filter=()->{items.clear();if(database==null)return;var pos=data==null?null:data.telemetry().pos;for(var a:database.search(query.getText(),pos,80,true,true))items.addElement(a);if(!items.isEmpty())list.setSelectedIndex(0);choose.setEnabled(!items.isEmpty());status.setText(items.size()+" alternatives · 3D scenery · preview does not command a diversion");};
        list.addListSelectionListener(e->choose.setEnabled(list.getSelectedValue()!=null));javax.swing.Timer debounce=new javax.swing.Timer(200,e->filter.run());debounce.setRepeats(false);
        query.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){debounce.restart();}public void removeUpdate(javax.swing.event.DocumentEvent e){debounce.restart();}public void changedUpdate(javax.swing.event.DocumentEvent e){debounce.restart();}});
        PilotTheme.apply(content);dialog.setContentPane(content);dialog.setSize(Math.min(680,getWidth()+40),680);dialog.setLocationRelativeTo(window);dialog.setVisible(true);
        Path root;try{root=sceneryRoot.get().toAbsolutePath().normalize();}catch(Exception ex){status.setText("Check the X-Plane folder in Mission planner.");return;}
        if(database!=null&&root.equals(databaseRoot)){filter.run();return;}
        new SwingWorker<AirportDatabase,Void>(){protected AirportDatabase doInBackground() throws Exception{return AirportDatabase.load(root);}protected void done(){try{database=get();databaseRoot=root;filter.run();}catch(Exception ex){status.setText("Airports unavailable. Check the X-Plane folder in Mission planner.");}}}.execute();
    }
    void render(Path file,int width,int height) throws Exception {
        preview=true;refresh();setSize(width,height);layout(this);BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();printAll(g);g.dispose();Files.createDirectories(file.getParent());ImageIO.write(image,"png",file.toFile());
    }
    private static void layout(Container p){p.doLayout();for(Component c:p.getComponents())if(c instanceof Container child)layout(child);}
    void previewAlternate(AirportPlan plan){alternate=plan;}
}
