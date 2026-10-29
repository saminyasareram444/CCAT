package alia;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Native dashboard sharing the controller connection. All explainability values come from Frame. */
final class ExplainabilityDashboard extends JPanel {
    static final Color NAVY=new Color(17,39,64),INK=new Color(30,48,70),MUTED=new Color(103,122,142),
        BLUE=new Color(40,116,222),TEAL=new Color(17,150,126),AMBER=new Color(216,139,17),
        RED=new Color(209,64,68),LINE=new Color(222,230,239),BG=new Color(240,244,249);
    private JFrame window;
    private ExplainabilityModel.Frame data;
    private final JLabel connection=new JLabel("WAITING"),phase=new JLabel("MONITORING"),
        altitude=new JLabel("—"),speed=new JLabel("—"),target=new JLabel("—"),
        batteryCharge=new JLabel("—"),
        headline=new JLabel("Waiting for aircraft data"),status=new JLabel("WAITING"),
        age=new JLabel("—"),margin=new JLabel("—"),tracking=new JLabel("—"),residual=new JLabel("—"),
        recommendation=new JLabel("Load ALIA in X-Plane to begin monitoring."),
        footer=new JLabel("Read-only explainability • Flight control remains in the CCAT flight window");
    private final JTextArea reason=text(3),gate=text(2),sources=text(4),traceGate=text(3);
    private final JPanel factors=new JPanel();
    private final JComboBox<String> controlChannel=new JComboBox<>(new String[]{"Lift power","Pitch","Roll","Yaw"});
    private final DefaultTableModel eventTable=new DefaultTableModel(new String[]{"Time","Status","Event","Evidence / explanation"},0){
        public boolean isCellEditable(int r,int c){return false;}
    };
    private final DefaultTableModel traceTable=new DefaultTableModel(new String[]{"Controller term","Actual value","Definition / units"},0){
        public boolean isCellEditable(int r,int c){return false;}
    };
    private final FlightPathMap map=new FlightPathMap(false);
    private final TrendView heightChart=new TrendView(0),liftChart=new TrendView(1),attitudeChart=new TrendView(2),batteryPowerChart=new TrendView(3),batteryChargeChart=new TrendView(4);
    private final Meter limitMeter=new Meter();
    private final JLabel destinationCaption=new JLabel("Street map · north up · waiting for position");
    private JTabbedPane responseTabs;

    ExplainabilityDashboard(Runnable controls,Runnable telemetry,Runnable pause,Runnable clear) {
        this(controls,telemetry,pause,clear,()->{});
    }
    ExplainabilityDashboard(Runnable controls,Runnable telemetry,Runnable pause,Runnable clear,Runnable planner) {
        this(controls,telemetry,pause,clear,planner,()->{});
    }
    ExplainabilityDashboard(Runnable controls,Runnable telemetry,Runnable pause,Runnable clear,Runnable planner,Runnable pilot) {
        setLayout(new BorderLayout(0,10));setBackground(BG);setBorder(new EmptyBorder(0,0,8,0));
        JPanel header=new JPanel(new BorderLayout(15,0));header.setBackground(NAVY);header.setBorder(new EmptyBorder(17,20,17,20));
        JLabel brand=new JLabel("CCAT  /  LIVE EXPLAINABILITY");brand.setForeground(Color.WHITE);brand.setFont(font(23,Font.BOLD));
        JLabel subtitle=new JLabel("ALIA-250  ·  Measured state, controller reasons & runtime limits");subtitle.setForeground(new Color(179,201,223));subtitle.setFont(font(12,0));
        JPanel titles=new JPanel(new GridLayout(2,1,0,4));titles.setOpaque(false);titles.add(brand);titles.add(subtitle);header.add(titles,BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));buttons.setOpaque(false);
        connection.setOpaque(true);connection.setBorder(new EmptyBorder(8,12,8,12));connection.setFont(font(12,Font.BOLD));buttons.add(connection);
        buttons.add(button("Flight controls",controls,false));buttons.add(button("Raw telemetry",telemetry,false));
        buttons.add(button("Mission planner",planner,false));
        buttons.add(button("Pilot display",pilot,false));
        buttons.add(button("Pause & take over",pause,true));header.add(buttons,BorderLayout.EAST);add(header,BorderLayout.NORTH);

        JPanel content=new JPanel(new BorderLayout(0,10));content.setOpaque(false);content.setBorder(new EmptyBorder(0,14,0,14));
        JPanel metrics=new JPanel(new GridLayout(1,5,10,0));metrics.setOpaque(false);
        metrics.add(stat("FLIGHT STAGE",phase,"Actual application stage"));metrics.add(stat("HEIGHT ABOVE TERRAIN",altitude,"Live X-Plane measurement"));
        metrics.add(stat("GROUND SPEED",speed,"Horizontal velocity magnitude"));metrics.add(stat("TARGET DISTANCE",target,"Current control-step target"));
        metrics.add(stat("BATTERY CHARGE",batteryCharge,"Average measured charge · five packs"));
        content.add(metrics,BorderLayout.NORTH);

        JTabbedPane tabs=new JTabbedPane();tabs.setFont(font(12,Font.BOLD));tabs.setBackground(BG);
        JPanel overview=new JPanel(new GridBagLayout());overview.setOpaque(false);
        JPanel left=new JPanel(new BorderLayout(0,10));left.setOpaque(false);
        JPanel mapCard=card("FLIGHT PATH",map);mapCard.add(destinationCaption,BorderLayout.SOUTH);destinationCaption.setFont(font(10,0));
        left.add(mapCard,BorderLayout.CENTER);
        JTabbedPane charts=new JTabbedPane();charts.setFont(font(11,0));charts.addTab("Height",heightChart);charts.addTab("Lift",liftChart);charts.addTab("Attitude",attitudeChart);charts.addTab("Battery kW",batteryPowerChart);charts.addTab("Charge %",batteryChargeChart);
        responseTabs=charts;
        JPanel chartCard=card("LIVE RESPONSE  /  LAST 120 SECONDS",charts);chartCard.setPreferredSize(new Dimension(400,190));left.add(chartCard,BorderLayout.SOUTH);

        JPanel middle=new JPanel(new BorderLayout(0,10));middle.setOpaque(false);
        JPanel why=new JPanel(new BorderLayout(0,12));why.setOpaque(false);
        JPanel words=new JPanel(new BorderLayout(0,10));words.setOpaque(false);
        headline.setFont(font(17,Font.BOLD));headline.setForeground(INK);words.add(headline,BorderLayout.NORTH);words.add(reason,BorderLayout.CENTER);
        JPanel selection=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));selection.setOpaque(false);
        JLabel explainLabel=new JLabel("Explain control:  ");explainLabel.setFont(font(11,Font.BOLD));selection.add(explainLabel);
        controlChannel.setFont(font(11,0));selection.add(controlChannel);words.add(selection,BorderLayout.SOUTH);
        controlChannel.addActionListener(e->{if(data!=null)renderReasons(data);});why.add(words,BorderLayout.NORTH);
        factors.setOpaque(false);factors.setLayout(new BoxLayout(factors,BoxLayout.Y_AXIS));
        JScrollPane factorScroll=new JScrollPane(factors);factorScroll.setBorder(null);factorScroll.setOpaque(false);
        factorScroll.getViewport().setOpaque(false);factorScroll.getVerticalScrollBar().setUnitIncrement(12);
        factorScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);why.add(factorScroll,BorderLayout.CENTER);
        JPanel gateBox=new JPanel(new BorderLayout(0,6));gateBox.setBackground(new Color(237,244,254));gateBox.setBorder(new EmptyBorder(10,10,10,10));
        JLabel gateTitle=new JLabel("WHAT MUST HAPPEN NEXT");gateTitle.setFont(font(11,Font.BOLD));gateTitle.setForeground(BLUE);
        gateBox.add(gateTitle,BorderLayout.NORTH);gateBox.add(gate,BorderLayout.CENTER);gate.setBackground(gateBox.getBackground());why.add(gateBox,BorderLayout.SOUTH);
        middle.add(card("WHY THIS ACTION?",why),BorderLayout.CENTER);
        JPanel sourceCard=card("SIGNAL PROVENANCE",sources);sourceCard.setPreferredSize(new Dimension(370,120));middle.add(sourceCard,BorderLayout.SOUTH);

        JPanel right=new JPanel(new BorderLayout(0,10));right.setOpaque(false);
        JPanel monitoring=new JPanel(new BorderLayout(0,10));monitoring.setOpaque(false);
        JPanel stateRow=new JPanel(new BorderLayout());stateRow.setOpaque(false);stateRow.add(new JLabel("Runtime status"),BorderLayout.WEST);
        status.setFont(font(13,Font.BOLD));stateRow.add(status,BorderLayout.EAST);monitoring.add(stateRow,BorderLayout.NORTH);
        JPanel small=new JPanel(new GridLayout(2,2,10,10));small.setOpaque(false);
        small.add(smallStat("Sample age",age,"Not network round-trip time"));small.add(smallStat("Limit margin",margin,"To configured controller limits"));
        small.add(smallStat("Command mismatch",tracking,"Largest motor-handle difference"));small.add(smallStat("Motion residual",residual,"Position vs velocity prediction"));monitoring.add(small,BorderLayout.CENTER);
        limitMeter.setPreferredSize(new Dimension(250,64));monitoring.add(limitMeter,BorderLayout.SOUTH);
        JPanel runtime=card("ROBUSTNESS & RUNTIME",monitoring);runtime.setPreferredSize(new Dimension(300,310));right.add(runtime,BorderLayout.NORTH);
        JPanel guidance=new JPanel(new BorderLayout(0,12));guidance.setOpaque(false);
        recommendation.setVerticalAlignment(SwingConstants.TOP);recommendation.setForeground(INK);recommendation.setFont(font(13,0));guidance.add(recommendation,BorderLayout.CENTER);
        JTextArea note=text(3);note.setText("Configured-limit margins are engineering indicators. Model confidence and independent GPS / IMU / LiDAR agreement are unavailable.");note.setForeground(MUTED);guidance.add(note,BorderLayout.SOUTH);
        right.add(card("OPERATOR GUIDANCE",guidance),BorderLayout.CENTER);
        GridBagConstraints c=new GridBagConstraints();c.gridy=0;c.weighty=1;c.fill=GridBagConstraints.BOTH;
        c.gridx=0;c.weightx=.36;c.insets=new Insets(7,0,0,8);overview.add(left,c);
        c.gridx=1;c.weightx=.35;c.insets=new Insets(7,0,0,8);overview.add(middle,c);
        c.gridx=2;c.weightx=.29;c.insets=new Insets(7,0,0,0);overview.add(right,c);
        tabs.addTab("Live overview",overview);
        JPanel tracePanel=new JPanel(new BorderLayout(0,10));tracePanel.setBorder(new EmptyBorder(12,8,8,8));tracePanel.setBackground(BG);
        tracePanel.add(traceGate,BorderLayout.NORTH);tracePanel.add(new JScrollPane(table(traceTable)),BorderLayout.CENTER);tabs.addTab("Exact controller trace",tracePanel);
        content.add(tabs,BorderLayout.CENTER);

        JPanel bottom=new JPanel(new BorderLayout(0,6));bottom.setOpaque(false);
        JPanel timelineHeader=new JPanel(new BorderLayout());timelineHeader.setOpaque(false);
        JLabel timelineTitle=new JLabel("EVENT TIMELINE");timelineTitle.setForeground(INK);timelineTitle.setFont(font(11,Font.BOLD));timelineHeader.add(timelineTitle,BorderLayout.WEST);
        timelineHeader.add(button("Clear history",clear,false),BorderLayout.EAST);bottom.add(timelineHeader,BorderLayout.NORTH);
        JTable history=table(eventTable);history.getColumnModel().getColumn(0).setPreferredWidth(80);history.getColumnModel().getColumn(1).setPreferredWidth(80);
        history.getColumnModel().getColumn(2).setPreferredWidth(230);history.getColumnModel().getColumn(3).setPreferredWidth(750);
        JScrollPane scroll=new JScrollPane(history);scroll.setPreferredSize(new Dimension(1100,110));bottom.add(scroll,BorderLayout.CENTER);
        footer.setFont(font(11,0));footer.setForeground(MUTED);bottom.add(footer,BorderLayout.SOUTH);content.add(bottom,BorderLayout.SOUTH);add(content,BorderLayout.CENTER);
        setPreferredSize(new Dimension(1440,920));
    }
    void open() {
        if(window==null) {
            window=new JFrame("CCAT | Live explainability dashboard");window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            setPreferredSize(new Dimension(Math.max(1100,Math.min(1440,screen.width-24)),920));
            JScrollPane viewport=new JScrollPane(this);viewport.setBorder(null);viewport.getVerticalScrollBar().setUnitIncrement(20);window.setContentPane(viewport);
            window.setSize(Math.min(1480,screen.width),Math.min(960,screen.height));window.setLocationRelativeTo(null);
        }
        window.setVisible(true);window.toFront();
    }
    void close(){if(window!=null)window.dispose();}
    void showBatteryPower(){responseTabs.setSelectedIndex(3);}
    void update(ExplainabilityModel.Frame f) {
        data=f;Color color=color(f.level());NativeLink.Position p=f.telemetry().pos;
        connection.setText(f.telemetry().fresh()?"● LIVE":"○ WAITING / STALE");connection.setBackground(f.telemetry().fresh()?TEAL:AMBER);connection.setForeground(Color.WHITE);
        phase.setText(f.phase());phase.setForeground(color);status.setText(f.level().name());status.setForeground(color);
        altitude.setText(p==null?"—":fmt(p.agl,"%.1f m"));speed.setText(p==null?"—":fmt(Math.hypot(p.east,p.south),"%.1f m/s"));
        batteryCharge.setText(fmt(f.battery().chargePercent(),"%.0f%%"));batteryCharge.setForeground(f.battery().minimumPercent()<20?AMBER:TEAL);
        batteryCharge.setToolTipText("Remaining energy "+fmt(f.battery().remainingKwh(),"%.1f kWh")+"; signed electrical power "+fmt(f.battery().powerKw(),"%.1f kW")+"; weakest pack "+fmt(f.battery().minimumPercent(),"%.0f%%"));
        target.setText(f.output()==null?"—":fmt(f.output().distance,"%.1f m"));
        String code=f.limits().destination().split(" — ",2)[0];
        destinationCaption.setText(f.limits().destination().startsWith("Hover test")?"Street map · north up · home = takeoff point":"Destination: "+code+" · north up · street map");
        destinationCaption.setToolTipText(f.limits().destination());
        renderReasons(f);
        gate.setText(f.output()!=null&&f.output().trace!=null?f.output().trace.gate:
            "No automatic mission is active. Prepare the aircraft and route in Flight controls.");
        age.setText(fmt(f.ageMs(),"%.0f ms"));age.setForeground(f.ageMs()>250?AMBER:TEAL);
        margin.setText(fmt(f.limitMargin()*100,"%.0f%%"));margin.setForeground(f.limitUse()>=.75?AMBER:TEAL);
        margin.setToolTipText(String.format(Locale.ROOT,"1 − largest configured-limit use. Height limits %.0f to %.0f m; radius %.0f m. Not a safety probability.",f.limits().minimumHeight(),f.limits().maximumHeight(),f.limits().radius()));
        tracking.setText(fmt(f.trackingError()*100,"%.1f pp"));tracking.setForeground(f.trackingError()>.20?AMBER:TEAL);
        residual.setText(fmt(f.residual(),"%.2f m"));
        sources.setText("POSITION & MOTION  ·  X-Plane native telemetry\nBATTERY  ·  Measured charge, volts and amps\nCONTROL REASONS  ·  Exact rule/controller trace\nINDEPENDENT GPS / IMU / LIDAR  ·  Unavailable");
        recommendation.setText(html(f.recommendation(),270));
        eventTable.setRowCount(0);for(var event:f.events())eventTable.addRow(new Object[]{event.time(),event.level(),event.title(),event.detail()});
        traceTable.setRowCount(0);
        if(f.output()!=null&&f.output().trace!=null) {
            HoverMission.Trace trace=f.output().trace;
            traceGate.setText("Computed step: "+trace.computedPhase+" | Reported next stage: "+f.phase()+"\n"+trace.gate+
                (trace.transition.isEmpty()?"":"\n"+trace.transition));
            for(var term:trace.terms.entrySet())traceTable.addRow(new Object[]{term.getKey(),String.format(Locale.ROOT,"%+.5f",term.getValue()),definition(term.getKey())});
        }else traceGate.setText("No current controller step. Monitoring does not fabricate decision terms.");
        footer.setText(f.systemEvent().contains("log unavailable")?f.systemEvent():
            "Measured telemetry + deterministic controller trace  ·  IMU/LiDAR comparison and learned confidence unavailable  ·  Logs: logs/alia/xai-*.jsonl");
        factors.revalidate();map.update(f,null,null);heightChart.repaint();liftChart.repaint();attitudeChart.repaint();batteryPowerChart.repaint();batteryChargeChart.repaint();limitMeter.repaint();
    }
    private void renderReasons(ExplainabilityModel.Frame f) {
        headline.setText(html(f.headline(),300));reason.setText(f.rationale());factors.removeAll();
        if(f.factors().isEmpty()) {
            JTextArea empty=text(4);empty.setForeground(MUTED);empty.setText("Exact command contributions appear during an automatic mission. No AI confidence is inferred.");factors.add(empty);
        } else {
            int channel=controlChannel.getSelectedIndex(),start=channel==0?0:channel==1?3:channel==2?5:7,end=channel==0?3:start+2;
            var t=f.output().trace;var v=t.terms;
            if(channel==1)reason.setText(String.format(Locale.ROOT,
                "Pitch target %+.1f° versus measured %+.1f°. Proportional correction and measured pitch-rate damping produce %+.3f stick input after the ±0.50 limit.",v.get("targetPitch"),t.state.pitch,f.output().pitch));
            else if(channel==2)reason.setText(String.format(Locale.ROOT,
                "Roll target %+.1f° versus measured %+.1f°. Guidance uses heading in wing flight and sideways position/velocity in rotor capture. Final stick input %+.3f, limited to ±0.50.",v.get("targetRoll"),t.state.roll,f.output().roll));
            else if(channel==3)reason.setText(String.format(Locale.ROOT,
                "Track the current stage's heading. Wrapped heading error %+.1f° combines with yaw-rate damping to produce %+.3f rudder input, limited to ±0.40.",v.get("headingError"),f.output().yaw));
            for(var factor:f.factors().subList(start,end)){factors.add(new FactorBar(factor));factors.add(Box.createVerticalStrut(3));}
        }
        factors.revalidate();factors.repaint();
    }
    private static JPanel card(String title,JComponent body) {
        JPanel panel=new JPanel(new BorderLayout(0,12));panel.setBackground(Color.WHITE);
        panel.setBorder(new CompoundBorder(new LineBorder(LINE,1,true),new EmptyBorder(13,14,13,14)));
        JLabel label=new JLabel(title);label.setForeground(MUTED);label.setFont(font(11,Font.BOLD));panel.add(label,BorderLayout.NORTH);panel.add(body,BorderLayout.CENTER);return panel;
    }
    private static JPanel stat(String title,JLabel value,String sub) {
        value.setFont(font(23,Font.BOLD));value.setForeground(INK);
        JPanel body=new JPanel(new BorderLayout(0,3));body.setOpaque(false);body.add(value,BorderLayout.NORTH);
        JLabel detail=new JLabel(sub);detail.setFont(font(10,0));detail.setForeground(MUTED);body.add(detail,BorderLayout.SOUTH);return card(title,body);
    }
    private static JPanel smallStat(String title,JLabel value,String sub) {
        JPanel p=new JPanel(new BorderLayout(0,5));p.setBackground(BG);p.setBorder(new EmptyBorder(10,10,10,10));
        JLabel label=new JLabel(title);label.setFont(font(11,Font.BOLD));label.setForeground(INK);p.add(label,BorderLayout.NORTH);
        value.setFont(font(24,Font.BOLD));p.add(value,BorderLayout.CENTER);
        p.setToolTipText(sub);value.setToolTipText(sub);return p;
    }
    private static JButton button(String title,Runnable action,boolean danger) {
        JButton b=new JButton(title);b.setFont(font(11,Font.BOLD));b.setFocusPainted(false);b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBackground(danger?RED:Color.WHITE);b.setForeground(danger?Color.WHITE:INK);b.addActionListener(e->action.run());return b;
    }
    private static JTextArea text(int rows) {
        JTextArea t=new JTextArea(rows,15);t.setEditable(false);t.setLineWrap(true);t.setWrapStyleWord(true);t.setFont(font(12,0));t.setForeground(INK);t.setOpaque(false);return t;
    }
    private static JTable table(DefaultTableModel model) {
        JTable t=new JTable(model);t.setFont(font(11,0));t.setRowHeight(25);t.setForeground(INK);t.setGridColor(LINE);
        t.getTableHeader().setFont(font(11,Font.BOLD));t.getTableHeader().setBackground(BG);t.setFillsViewportHeight(true);return t;
    }
    private static Font font(int size,int style){return new Font("Segoe UI",style,size);}
    static Color color(ExplainabilityModel.Level level){return switch(level){case NOMINAL->TEAL;case CAUTION->AMBER;case CRITICAL->RED;case PAUSED->BLUE;default->MUTED;};}
    private static String fmt(double value,String format){return Double.isFinite(value)?String.format(Locale.ROOT,format,value):"N/A";}
    private static String html(String s,int width){return "<html><div style='width:"+width+"px'>"+s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"</div></html>";}
    static String definition(String key){return switch(key){
        case "northError","eastError","heightError" -> "Target minus measured position (m)";
        case "targetNorthSpeed","targetEastSpeed","targetClimb","climbError" -> "Velocity / velocity error (m/s)";
        case "baseLift" -> "Operator hover setting (0–1)";
        case "verticalP" -> "0.10 × climbError (throttle ratio)";
        case "verticalI" -> "Accumulated climb error, clamped ±0.30";
        case "requestedLift" -> "Hover sum after any wing-support blend, altitude floor and throttle limits";
        case "appliedLift" -> "After ramp limit: +0.20/s, −0.35/s; idle after landing";
        case "targetPitch","targetRoll","headingError" -> "Angle / angle error (degrees)";
        case "pitchP","rollP" -> "0.075 × attitude error, before control clamp";
        case "pitchD","rollD" -> "−0.025 × measured angular rate";
        case "yawP" -> "0.025 × wrapped heading error";
        case "yawD" -> "−0.018 × measured yaw rate";
        case "settledSeconds" -> "Current gate dwell timer (s); resets on a transition";
        case "phaseSeconds" -> "Elapsed time in the computed stage (s)";
        case "wingSupportFraction" -> "Smooth IAS support schedule: transition 40–70 kt; braking approach 25–90 kt; not measured aerodynamic wing lift";
        case "requestedPusher","appliedPusher" -> "Pusher motor command before / after ramp limit (ratio)";
        case "airspeedKnots","targetAirspeedKnots" -> "Measured / configured indicated airspeed (kt)";
        case "destinationDistance","brakingDistance" -> "Destination distance / approach initiation distance (m)";
        case "approachAlongRemaining" -> "Signed distance along approach to landing point (m); negative after passing it";
        case "approachSpeedTarget" -> "Braking speed target (m/s), limited by time and remaining stopping distance";
        case "approachHeightHold" -> "Approach height target (m above origin); held until slow, then reduced";
        case "forwardAirspeedKnots" -> "Nonnegative forward IAS for control scheduling (kt); signed measurement is retained";
        default -> "Exact recorded controller intermediate value";
    };}
    private static Graphics2D graphics(Graphics g){Graphics2D x=(Graphics2D)g.create();x.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);x.setFont(font(10,0));return x;}
    private static final class FactorBar extends JPanel {
        private final ExplainabilityModel.Factor factor;
        FactorBar(ExplainabilityModel.Factor factor){this.factor=factor;setOpaque(false);setPreferredSize(new Dimension(310,23));setMaximumSize(new Dimension(Integer.MAX_VALUE,23));setToolTipText(factor.explanation());}
        protected void paintComponent(Graphics g){super.paintComponent(g);Graphics2D x=graphics(g);int w=getWidth();
            x.setColor(INK);x.setFont(font(11,Font.BOLD));x.drawString(factor.name(),0,12);x.setFont(font(11,0));
            x.drawString(String.format(Locale.ROOT,"%+.1f pp",factor.value()*100),Math.max(150,w-66),12);
            x.setColor(BG);x.fillRoundRect(0,18,w,4,4,4);int mid=w/2;x.setColor(LINE);x.drawLine(mid,16,mid,23);
            int size=(int)(Math.min(Math.abs(factor.value()),1)*(w/2-2));x.setColor(factor.value()<0?AMBER:BLUE);
            x.fillRoundRect(factor.value()<0?mid-size:mid,18,Math.max(1,size),4,4,4);x.dispose();}
    }
    private final class Meter extends JPanel {
        Meter(){setOpaque(false);}
        protected void paintComponent(Graphics g){super.paintComponent(g);Graphics2D x=graphics(g);double use=data==null?Double.NaN:data.limitUse();int w=getWidth();
            x.setColor(INK);x.drawString("Configured-limit use "+fmt(use*100,"%.0f%%"),0,13);
            x.setColor(BG);x.fillRoundRect(0,24,w,10,8,8);x.setColor(Double.isFinite(use)&&use>=.75?AMBER:TEAL);
            if(Double.isFinite(use))x.fillRoundRect(0,24,(int)(Math.min(1,use)*w),10,8,8);
            x.setColor(MUTED);x.drawString("0",0,49);x.drawString("caution 75%",w/2,49);x.drawString("limit",w-24,49);x.dispose();}
    }
    private final class TrendView extends JPanel {
        private final int kind;
        TrendView(int kind){this.kind=kind;setBackground(Color.WHITE);setPreferredSize(new Dimension(380,130));}
        protected void paintComponent(Graphics g){super.paintComponent(g);Graphics2D x=graphics(g);int w=getWidth(),h=getHeight(),left=35,top=25,bottom=h-23;
            String first=kind==0?"Relative height (m)":kind==1?"Command (%)":kind==2?"Pitch (deg)":kind==3?"Battery power (kW, V × I)":"Charge remaining (%)",second=kind==0?"Target":kind==1?"Actual lift 1":kind==2?"Roll":"";
            x.setColor(BLUE);x.drawLine(left,10,left+10,10);x.drawString(first,left+15,13);
            if(kind<3){x.setColor(AMBER);x.drawLine(w/2+10,10,w/2+20,10);x.drawString(second,w/2+25,13);}
            if(data==null||data.samples().size()<2){x.setColor(MUTED);x.drawString("Collecting live history…",left,60);x.dispose();return;}
            List<ExplainabilityModel.Sample> list=data.samples();double min=0,max=kind==1||kind==4?100:kind==0?20:kind==3?10:10;
            if(kind==2)min=-10;
            for(var s:list)for(double v:new double[]{value(s,false),value(s,true)})if(Double.isFinite(v)){min=Math.min(min,v);max=Math.max(max,v);}
            double pad=(max-min)*.1;max+=pad;min-=pad;double start=list.get(0).seconds(),end=list.get(list.size()-1).seconds();
            for(int i=0;i<4;i++){int yy=top+(bottom-top)*i/3;x.setColor(LINE);x.drawLine(left,yy,w-10,yy);x.setColor(MUTED);x.drawString(String.format(Locale.ROOT,"%.0f",max-(max-min)*i/3),0,yy+4);}
            for(boolean other:kind>=3?new boolean[]{false}:new boolean[]{false,true}){Path2D path=new Path2D.Double();boolean fresh=true;double previous=Double.NaN;
                for(var s:list){double v=value(s,other);if(!Double.isFinite(v)){fresh=true;continue;}
                    double xx=left+(s.seconds()-start)/Math.max(1,end-start)*(w-left-10),yy=bottom-(v-min)/(max-min)*(bottom-top);
                    if(fresh||s.seconds()-previous>1){path.moveTo(xx,yy);fresh=false;}else path.lineTo(xx,yy);previous=s.seconds();}
                x.setColor(other?AMBER:BLUE);x.setStroke(new BasicStroke(1.8f));x.draw(path);}
            x.setColor(MUTED);x.drawString(String.format(Locale.ROOT,"−%.0f s",end-start),left,h-5);x.drawString("now",w-28,h-5);x.dispose();}
        private double value(ExplainabilityModel.Sample s,boolean other){return kind==0?(other?s.targetHeight():s.height()):kind==1?(other?s.actualLift():s.lift())*100:kind==2?(other?s.roll():s.pitch()):kind==3?s.batteryPowerKw():s.batteryPercent();}
    }
    void render(Path path) throws IOException {
        setSize(1440,920);layoutTree(this);BufferedImage image=new BufferedImage(1440,920,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();printAll(g);
        if(data!=null&&data.systemEvent().startsWith("OFFLINE PREVIEW")) {
            g.setColor(new Color(255,232,176));g.fillRect(0,894,1440,26);
            g.setColor(INK);g.setFont(font(12,Font.BOLD));g.drawString("OFFLINE UI PREVIEW  •  SYNTHETIC TELEMETRY  •  NO AIRCRAFT COMMANDS",20,912);
        }
        g.dispose();Files.createDirectories(path.toAbsolutePath().getParent());ImageIO.write(image,"png",path.toFile());
    }
    private static void layoutTree(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container cc)layoutTree(cc);}
}
