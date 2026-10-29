package alia;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/** The same geographic map, route, history and aircraft projection in both displays. */
final class FlightPathMap extends JComponent {
    private final boolean dark;
    private final JButton plus,minus,fit;
    private ExplainabilityModel.Frame data;
    private AirportPlan destination,alternate;
    private int zoomOffset;
    private double panX,panY;
    private Point drag;
    private MapProjection.Camera camera;
    private Rectangle attribution=new Rectangle();
    FlightPathMap(boolean dark){
        this.dark=dark;setName("geographic-flight-map");setLayout(null);setOpaque(true);setBackground(dark?PilotDisplay.BG:new Color(236,239,233));setPreferredSize(new Dimension(430,260));MapTiles.INSTANCE.watch(this);
        plus=control("+",()->zoom(1),"Zoom in");minus=control("−",()->zoom(-1),"Zoom out");fit=control("Fit",()->{zoomOffset=0;panX=panY=0;repaint();},"Fit route and aircraft");
        addMouseWheelListener(e->zoom(-e.getWheelRotation()));
        var mouse=new MouseAdapter(){
            public void mousePressed(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e))drag=e.getPoint();}
            public void mouseReleased(MouseEvent e){drag=null;}
            public void mouseDragged(MouseEvent e){if(drag!=null&&camera!=null){panX-=(e.getX()-drag.x)/camera.world();panY-=(e.getY()-drag.y)/camera.world();drag=e.getPoint();repaint();}}
            public void mouseClicked(MouseEvent e){if(attribution.contains(e.getPoint()))try{if(Desktop.isDesktopSupported())Desktop.getDesktop().browse(java.net.URI.create(MapTiles.ATTRIBUTION_URL));}catch(Exception ignored){}}
        };addMouseListener(mouse);addMouseMotionListener(mouse);
        setToolTipText("Drag to pan · + / − to zoom · Fit to restore route view. Street map is not an aviation chart.");
    }
    private JButton control(String name,Runnable action,String description){JButton b=new JButton(name);b.setFocusable(false);b.setFont(new Font("Segoe UI",Font.BOLD,dark?16:12));b.setMargin(new Insets(0,0,0,0));b.setBackground(dark?PilotDisplay.CARD:Color.WHITE);b.setForeground(dark?PilotDisplay.INK:ExplainabilityDashboard.INK);b.setBorder(BorderFactory.createLineBorder(dark?PilotDisplay.LINE:new Color(160,170,176)));b.setOpaque(true);b.setContentAreaFilled(true);b.setToolTipText(description);b.getAccessibleContext().setAccessibleName(description);b.addActionListener(e->action.run());add(b);return b;}
    public void doLayout(){int size=dark?(getHeight()>220?38:getHeight()>170?29:22):27;plus.setBounds(10,10,size,size);minus.setBounds(10,12+size,size,size);fit.setBounds(10,14+2*size,size,size);}
    private void zoom(int delta){if(camera!=null)delta=Math.max(2,Math.min(18,camera.zoom()+delta))-camera.zoom();zoomOffset=Math.max(-16,Math.min(16,zoomOffset+delta));repaint();}
    void update(ExplainabilityModel.Frame frame,AirportPlan destination,AirportPlan alternate){this.data=frame;this.destination=destination;this.alternate=alternate;repaint();}
    static MapProjection.Geo geo(NativeLink.Position p){return new MapProjection.Geo(p.lat,p.lon);}
    static List<MapProjection.Geo> planned(ExplainabilityModel.Frame f,AirportPlan destination){
        if(f==null||f.telemetry().pos==null)return List.of();var origin=geo(f.origin()!=null?f.origin():f.telemetry().pos);List<MapProjection.Geo> points=new ArrayList<>();points.add(origin);
        for(var p:f.route())points.add(MapProjection.local(origin,p.north,p.east));
        if(destination!=null){var end=new MapProjection.Geo(destination.site().latitude(),destination.site().longitude());if(points.size()==1)points.add(end);else points.set(points.size()-1,end);}
        else if(points.size()>1&&f.limits().destination().startsWith("Hover test"))points.add(origin);
        return List.copyOf(points);
    }
    protected void paintComponent(Graphics original){
        super.paintComponent(original);Graphics2D g=(Graphics2D)original.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setFont(new Font("Segoe UI",Font.PLAIN,dark?12:10));int w=getWidth(),h=getHeight();g.setColor(getBackground());g.fillRect(0,0,w,h);if(w<50||h<50){g.dispose();return;}
        Color ink=dark?PilotDisplay.INK:ExplainabilityDashboard.INK,muted=dark?PilotDisplay.MUTED:ExplainabilityDashboard.MUTED;
        if(data==null||data.telemetry().pos==null){empty(g,w,h,"Waiting for aircraft position",muted);g.dispose();return;}
        var position=geo(data.telemetry().pos);if(!position.valid()){empty(g,w,h,"Basemap unavailable at this latitude",muted);g.dispose();return;}
        var origin=geo(data.origin()!=null?data.origin():data.telemetry().pos);var route=planned(data,destination);List<MapProjection.Geo> bounds=new ArrayList<>(route);bounds.add(position);
        for(var p:data.samples())if(Double.isFinite(p.north())&&Double.isFinite(p.east()))bounds.add(MapProjection.local(origin,p.north(),p.east()));
        MapProjection.Geo alt=alternate==null?null:new MapProjection.Geo(alternate.site().latitude(),alternate.site().longitude());if(alt!=null)bounds.add(alt);
        camera=MapProjection.fit(bounds,position,w,h,zoomOffset,panX,panY);double world=camera.world(),left=camera.x()*world-w/2.0,top=camera.y()*world-h/2.0;int count=1<<camera.zoom(),drawn=0,requested=0;boolean loading=false,expired=false;
        for(int ty=(int)Math.floor(top/256);ty<=(int)Math.floor((top+h)/256);ty++)for(int tx=(int)Math.floor(left/256);tx<=(int)Math.floor((left+w)/256);tx++){
            if(ty<0||ty>=count)continue;int px=(int)Math.round(tx*256.0-left),py=(int)Math.round(ty*256.0-top);
            var tile=MapTiles.INSTANCE.get(new MapTiles.Tile(camera.zoom(),Math.floorMod(tx,count),ty),isShowing());requested++;var image=dark?tile.dark:tile.image;
            if(image!=null){g.drawImage(image,px,py,256,256,null);drawn++;expired|=System.currentTimeMillis()>tile.expiry;}else{g.setColor(dark?new Color(29,29,31):new Color(232,235,229));g.fillRect(px,py,256,256);g.setColor(dark?PilotDisplay.LINE:new Color(214,220,210));g.drawRect(px,py,256,256);}loading|=tile.loading;
        }
        // Every overlay uses this camera, rather than a separate local-grid transform.
        drawRoute(g,route,dark?PilotDisplay.CYAN:ExplainabilityDashboard.BLUE,true);
        Path2D track=new Path2D.Double();boolean first=true;double previous=Double.NaN;
        for(var sample:data.samples()){
            if(!Double.isFinite(sample.north())||!Double.isFinite(sample.east())){first=true;continue;}
            var p=camera.point(MapProjection.local(origin,sample.north(),sample.east()));if(first||sample.seconds()-previous>1){track.moveTo(p.x,p.y);first=false;}else track.lineTo(p.x,p.y);previous=sample.seconds();
        }
        stroke(g,track,dark?new Color(245,232,216):new Color(18,98,78),dark?3.2f:2.5f,false);
        var home=camera.point(origin);marker(g,home,new Color(67,195,150),"HOME",ink);
        for(int i=1;i<route.size();i++){var p=camera.point(route.get(i));boolean last=i==route.size()-1&&!route.get(i).equals(origin);if(last)marker(g,p,dark?PilotDisplay.CYAN:ExplainabilityDashboard.BLUE,destination!=null?destination.airport().id():data.limits().destination().startsWith("Hover test")?"TARGET":"DEST",ink);else if(!route.get(i).equals(origin)){g.setColor(dark?PilotDisplay.CYAN:ExplainabilityDashboard.BLUE);g.fill(new Ellipse2D.Double(p.x-3,p.y-3,6,6));}}
        if(data.output()!=null&&data.output().trace!=null){var t=data.output().trace.target;var p=camera.point(MapProjection.local(origin,t.north,t.east));g.setColor(PilotDisplay.AMBER);g.setStroke(new BasicStroke(2));g.draw(new Ellipse2D.Double(p.x-10,p.y-10,20,20));}
        if(alt!=null){var a=camera.point(position);var b=camera.point(alt);stroke(g,new Line2D.Double(a,b),PilotDisplay.AMBER,2,true);marker(g,b,PilotDisplay.AMBER,alternate.airport().id()+" ALT",ink);}
        Point2D p=camera.point(position);Graphics2D plane=(Graphics2D)g.create();plane.translate(p.getX(),p.getY());plane.rotate(Math.toRadians(data.telemetry().pos.heading));double size=dark?1.15:.8;plane.scale(size,size);Polygon shape=new Polygon(new int[]{0,-10,0,10},new int[]{-16,12,7,12},4);plane.setColor(dark?new Color(14,14,14):Color.WHITE);plane.setStroke(new BasicStroke(5));plane.draw(shape);plane.setColor(data.telemetry().fresh()?(dark?PilotDisplay.INK:ExplainabilityDashboard.BLUE):muted);plane.fill(shape);plane.dispose();
        badge(g,"N ↑",w-42,10,ink);if(!data.telemetry().fresh())badge(g,"LAST KNOWN POSITION",54,10,PilotDisplay.AMBER);
        String status=drawn==0?(loading?"Loading map":"Map unavailable"):drawn<requested?(loading?"Map loading":"Map partly unavailable"):expired?"Cached map":"Street map";
        // Solid surface keeps attribution legible at every tile colour and viewport size.
        g.setColor(dark?new Color(18,18,18,235):new Color(255,255,255,235));g.fillRect(0,h-44,w,44);g.setColor(muted);g.setFont(new Font("Segoe UI",Font.PLAIN,dark?11:9));g.drawString(status+" · z"+camera.zoom(),8,h-28);
        double metres=camera.metresPerPixel();double length=nice(70*metres);int pixels=(int)(length/metres);int sx=Math.max(85,w-pixels-14);g.setColor(ink);g.drawLine(sx,h-28,sx+pixels,h-28);g.drawLine(sx,h-31,sx,h-25);g.drawLine(sx+pixels,h-31,sx+pixels,h-25);g.drawString(length>=1000?String.format(Locale.ROOT,"%.1f km",length/1000):String.format(Locale.ROOT,"%.0f m",length),sx,h-33);
        g.setFont(new Font("Segoe UI",Font.PLAIN,dark?11:9));g.setColor(ink);g.drawString(MapTiles.ATTRIBUTION,8,h-9);attribution=new Rectangle(5,h-23,Math.min(w-10,g.getFontMetrics().stringWidth(MapTiles.ATTRIBUTION)+6),21);g.dispose();
    }
    private void empty(Graphics2D g,int w,int h,String message,Color muted){g.setColor(muted);g.drawString(message,Math.max(10,(w-g.getFontMetrics().stringWidth(message))/2),h/2);g.drawString("Geographic map appears with live telemetry",10,h-15);}
    private void drawRoute(Graphics2D g,List<MapProjection.Geo> points,Color color,boolean dashed){if(points.size()<2)return;Path2D path=new Path2D.Double();boolean first=true;for(var point:points){var p=camera.point(point);if(first){path.moveTo(p.x,p.y);first=false;}else path.lineTo(p.x,p.y);}stroke(g,path,color,dark?2.8f:2,dashed);}
    private void stroke(Graphics2D g,Shape shape,Color color,float width,boolean dashed){g.setStroke(new BasicStroke(width+2,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(dark?new Color(14,14,14,200):new Color(255,255,255,220));g.draw(shape);g.setColor(color);g.setStroke(dashed?new BasicStroke(width,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{7,6},0):new BasicStroke(width,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(shape);}
    private void marker(Graphics2D g,Point2D p,Color color,String name,Color ink){g.setColor(dark?PilotDisplay.BG:Color.WHITE);g.fill(new Ellipse2D.Double(p.getX()-6,p.getY()-6,12,12));g.setColor(color);g.fill(new Ellipse2D.Double(p.getX()-4,p.getY()-4,8,8));int x=(int)Math.max(8,Math.min(getWidth()-60,p.getX()+10)),y=(int)Math.max(25,Math.min(getHeight()-55,p.getY()-10));g.setFont(new Font("Segoe UI",Font.BOLD,dark?12:10));badge(g,name,x,y-12,ink);}
    private void badge(Graphics2D g,String text,int x,int y,Color color){int width=g.getFontMetrics().stringWidth(text)+10;g.setColor(dark?new Color(18,18,18,225):new Color(255,255,255,225));g.fillRoundRect(x,y,width,21,5,5);g.setColor(color);g.drawString(text,x+5,y+15);}
    private static double nice(double value){double base=Math.pow(10,Math.floor(Math.log10(value))),n=value/base;return (n>=5?5:n>=2?2:1)*base;}
}
