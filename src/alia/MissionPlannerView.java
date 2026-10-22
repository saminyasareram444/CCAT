package alia;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.imageio.ImageIO;

/** Airport selection uses local scenery, and planning never engages flight controls. */
final class MissionPlannerView extends JPanel {
    private final Supplier<NativeLink.Snapshot> telemetry;
    private final Consumer<AirportPlan> apply;
    private final JTextField installation=new JTextField("C:\\X-Plane 12",28),search=new JTextField(28);
    private final JLabel status=new JLabel("Load your installed airport database to search by airport code or name.");
    private final JTextArea summary=new JTextArea(12,30);
    private final JComboBox<AirportDatabase.Site> sites=new JComboBox<>();
    private final JSpinner altitude=new JSpinner(new SpinnerNumberModel(300,100,1000,25)),cruise=new JSpinner(new SpinnerNumberModel(90,75,110,5));
    private final DefaultTableModel rows=new DefaultTableModel(new String[]{"Airport","Name","Type","Distance"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(rows);
    private final RoutePreview map=new RoutePreview();
    private AirportDatabase database;
    private List<AirportDatabase.Airport> results=List.of();
    private AirportDatabase.Airport selected;
    private boolean loading;
    private JFrame window;
    private final javax.swing.Timer refresh;

    MissionPlannerView(Supplier<NativeLink.Snapshot> telemetry,Consumer<AirportPlan> apply) {
        this.telemetry=telemetry;this.apply=apply;setLayout(new BorderLayout(12,12));setBackground(ExplainabilityDashboard.BG);setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
        JPanel top=new JPanel(new BorderLayout(0,10));top.setOpaque(false);
        JLabel title=new JLabel("AIRPORT MISSION PLANNER");title.setForeground(ExplainabilityDashboard.INK);title.setFont(new Font("Segoe UI",Font.BOLD,23));top.add(title,BorderLayout.NORTH);
        JPanel path=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));path.setOpaque(false);path.add(new JLabel("X-Plane folder:"));path.add(installation);
        JButton browse=new JButton("Browse");browse.addActionListener(e->{JFileChooser chooser=new JFileChooser(installation.getText());chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)installation.setText(chooser.getSelectedFile().toString());});path.add(browse);
        JButton load=new JButton("Load airports");load.addActionListener(e->load());path.add(load);top.add(path,BorderLayout.CENTER);
        status.setForeground(ExplainabilityDashboard.MUTED);top.add(status,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
        JPanel left=new JPanel(new BorderLayout(0,10));left.setOpaque(false);JPanel find=new JPanel(new BorderLayout(8,0));find.setOpaque(false);find.add(new JLabel("Code / name:"),BorderLayout.WEST);find.add(search,BorderLayout.CENTER);left.add(find,BorderLayout.NORTH);
        table.setRowHeight(27);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getColumnModel().getColumn(0).setPreferredWidth(65);table.getColumnModel().getColumn(1).setPreferredWidth(210);
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())select();});left.add(new JScrollPane(table),BorderLayout.CENTER);
        JPanel right=new JPanel(new BorderLayout(0,10));right.setOpaque(false);
        JPanel options=new JPanel(new GridLayout(3,2,8,8));options.setOpaque(false);options.add(new JLabel("Arrival location:"));options.add(sites);
        options.add(new JLabel("Cruise clearance (m):"));options.add(altitude);options.add(new JLabel("Cruise airspeed (kt):"));options.add(cruise);right.add(options,BorderLayout.NORTH);
        JPanel detail=new JPanel(new BorderLayout(0,8));detail.setOpaque(false);map.setPreferredSize(new Dimension(390,185));detail.add(map,BorderLayout.NORTH);
        summary.setEditable(false);summary.setLineWrap(true);summary.setWrapStyleWord(true);summary.setFont(new Font("Segoe UI",Font.PLAIN,12));summary.setMargin(new Insets(10,10,10,10));detail.add(new JScrollPane(summary),BorderLayout.CENTER);right.add(detail,BorderLayout.CENTER);
        JButton use=new JButton("Use airport mission — start separately");use.setFont(new Font("Segoe UI",Font.BOLD,12));
        use.addActionListener(e->{try {
            AirportPlan plan=selection();NativeLink.Snapshot s=telemetry.get();if(s==null||!s.fresh()||!s.alia())throw new IllegalStateException("Connect CCAT to the loaded ALIA first");
            plan.validateStart(s.pos);apply.accept(plan);
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Mission input",JOptionPane.WARNING_MESSAGE);}});right.add(use,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,right);split.setResizeWeight(.5);split.setBorder(null);add(split,BorderLayout.CENTER);
        JLabel note=new JLabel("Experimental transition controller • Direct route only • Terrain / obstacles / traffic not checked • Start from the flight-controls window");note.setFont(new Font("Segoe UI",Font.PLAIN,11));note.setForeground(ExplainabilityDashboard.MUTED);add(note,BorderLayout.SOUTH);
        javax.swing.Timer debounce=new javax.swing.Timer(250,e->filter());debounce.setRepeats(false);search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){debounce.restart();}public void removeUpdate(DocumentEvent e){debounce.restart();}public void changedUpdate(DocumentEvent e){debounce.restart();}});
        sites.addActionListener(e->details());altitude.addChangeListener(e->details());cruise.addChangeListener(e->details());
        refresh=new javax.swing.Timer(1000,e->details());setPreferredSize(new Dimension(1050,740));details();
    }
    void open() {
        if(window==null){
            window=new JFrame("CCAT | Airport mission planner");window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            JScrollPane viewport=new JScrollPane(this);viewport.setBorder(null);viewport.getVerticalScrollBar().setUnitIncrement(20);window.setContentPane(viewport);
            Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();window.setSize(Math.min(1100,screen.width),Math.min(800,screen.height));window.setLocationRelativeTo(null);
        }
        window.setVisible(true);window.toFront();refresh.start();if(database==null&&!loading)load();
    }
    void close(){refresh.stop();if(window!=null)window.dispose();}
    private void load() {
        if(loading)return;loading=true;Path root=Paths.get(installation.getText());status.setText("Indexing installed scenery airports… first load may take a few seconds.");
        new SwingWorker<AirportDatabase,Void>() {
            protected AirportDatabase doInBackground() throws Exception{return AirportDatabase.load(root);}
            protected void done(){loading=false;try{setDatabase(get());}catch(Exception ex){status.setText("Airport load failed: "+(ex.getCause()==null?ex.getMessage():ex.getCause().getMessage()));}}
        }.execute();
    }
    void setDatabase(AirportDatabase data){database=data;status.setText(data.size()+" installed airports / heliports indexed. Search by ICAO code or name.");filter();}
    private void filter() {
        if(database==null)return;NativeLink.Snapshot s=telemetry.get();NativeLink.Position origin=s==null?null:s.pos;
        results=database.search(search.getText(),origin,150);selected=null;rows.setRowCount(0);
        for(var airport:results)rows.addRow(new Object[]{airport.id(),airport.name(),airport.type(),origin==null?"—":String.format(Locale.ROOT,"%.1f km",AirportDatabase.distance(origin.lat,origin.lon,airport.latitude(),airport.longitude())/1000)});
        if(!results.isEmpty())table.setRowSelectionInterval(0,0);else{sites.removeAllItems();details();}
    }
    private void select() {
        int row=table.getSelectedRow();if(row<0||row>=results.size())return;selected=results.get(row);sites.removeAllItems();for(var site:selected.sites())sites.addItem(site);details();
    }
    AirportPlan selection() {
        if(selected==null||sites.getSelectedItem()==null)throw new IllegalArgumentException("Select an airport and a runway midpoint or helipad");
        return new AirportPlan(selected,(AirportDatabase.Site)sites.getSelectedItem(),((Number)altitude.getValue()).doubleValue(),((Number)cruise.getValue()).doubleValue());
    }
    private void details() {
        if(selected==null){summary.setText("Select an airport. The mission origin is the aircraft's actual position when Start automatic flight is pressed.\n\nThe plan includes vertical takeoff, wing-borne cruise, deceleration, hover capture and vertical landing.");map.repaint();return;}
        NativeLink.Snapshot s=telemetry.get();StringBuilder b=new StringBuilder(selected.toString()).append("\n");
        if(sites.getSelectedItem()==null)b.append("No runway or helipad coordinates available in the airport data.\n");
        else {
            AirportPlan p=selection();b.append(p.site().name()).append(String.format(Locale.ROOT,"\nDestination: %.7f, %.7f\nAirport elevation: %.0f ft MSL\n",p.site().latitude(),p.site().longitude(),p.airport().elevationM()*3.28084));
            if(s!=null&&s.pos!=null) {
                b.append(String.format(Locale.ROOT,"From current position: %.2f km, initial course %.0f°\nCruise target: %.0f ft MSL at %.0f kt\nNominal cruise time: %.1f min (+ takeoff / approach)\n",p.distance(s.pos)/1000,p.course(s.pos),(s.pos.elevation+p.cruiseHeight(s.pos))*3.28084,p.cruiseKnots(),p.distance(s.pos)/(p.cruiseKnots()*.514444)/60));
                try{p.validateStart(s.pos);b.append("Route length is within the experimental 2.5–100 km envelope.\n");}catch(IllegalArgumentException e){b.append(e.getMessage()).append('\n');}
                BatteryStatus battery=BatteryStatus.from(s);b.append(battery.chargeAvailable()?String.format(Locale.ROOT,"Battery charge: %.0f%% average; weakest pack %.0f%%.\n",battery.chargePercent(),battery.minimumPercent()):"Battery charge: waiting for measured energy values.\n");
            }else b.append("Connect ALIA to preview origin, distance and cruise altitude.\n");
        }
        b.append("\nClearance is above the higher endpoint, not a terrain-checked corridor. Battery range and landing-surface clearance are not guaranteed.");summary.setText(b.toString());summary.setCaretPosition(0);map.repaint();
    }
    private final class RoutePreview extends JPanel {
        RoutePreview(){setBackground(Color.WHITE);}
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);Graphics2D x=(Graphics2D)g.create();x.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);x.setFont(new Font("Segoe UI",Font.PLAIN,11));
            int w=getWidth(),h=getHeight();x.setColor(ExplainabilityDashboard.LINE);for(int i=1;i<5;i++){x.drawLine(i*w/5,15,i*w/5,h-15);x.drawLine(15,i*h/5,w-15,i*h/5);}x.setColor(ExplainabilityDashboard.MUTED);x.drawString("N ↑   Direct route preview",12,18);
            NativeLink.Snapshot s=telemetry.get();if(s==null||s.pos==null||sites.getSelectedItem()==null){x.drawString("Waiting for origin and destination",30,h/2);x.dispose();return;}
            AirportPlan p=selection();double d=p.distance(s.pos),heading=Math.toRadians(p.course(s.pos)),dn=Math.cos(heading)*d,de=Math.sin(heading)*d;
            double scale=Math.min(w-100,h-70)/Math.max(1,Math.max(Math.abs(dn),Math.abs(de)));
            int ax=(int)(w/2-de*scale/2),ay=(int)(h/2+dn*scale/2),bx=(int)(w/2+de*scale/2),by=(int)(h/2-dn*scale/2);
            x.setStroke(new BasicStroke(2));x.setColor(ExplainabilityDashboard.BLUE);x.drawLine(ax,ay,bx,by);x.fillOval(ax-5,ay-5,10,10);x.drawString("CURRENT",ax+8,ay+15);
            x.setColor(ExplainabilityDashboard.AMBER);x.fillOval(bx-6,by-6,12,12);x.drawString(selected.id(),bx+9,by-7);x.dispose();
        }
    }
    void render(Path file) throws Exception {
        setSize(1050,740);layout(this);BufferedImage image=new BufferedImage(1050,740,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();printAll(g);
        g.setColor(new Color(255,232,176));g.fillRect(0,714,1050,26);g.setColor(ExplainabilityDashboard.INK);g.drawString("OFFLINE PLANNER PREVIEW — synthetic origin, no aircraft commands",15,732);g.dispose();Files.createDirectories(file.getParent());ImageIO.write(image,"png",file.toFile());
    }
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container cc)layout(cc);}
}
