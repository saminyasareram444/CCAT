package alia;

import java.awt.*;
import java.net.http.HttpHeaders;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Projection/route/caching semantics, including known geographic edge cases. No network fetches. */
final class MapTests {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void near(double a,double b,double tolerance,String why){check(Math.abs(a-b)<tolerance,why);}
    static int run()throws Exception{
        near(MapProjection.x(0),.5,1e-12,"Greenwich world x");near(MapProjection.y(0),.5,1e-12,"equator world y");
        for(double lat:new double[]{-80,-41.5,0,41.50734,80})near(MapProjection.latitude(MapProjection.y(lat)),lat,1e-9,"latitude round trip");
        for(double lon:new double[]{-179.9,-74.0886,0,179.9})near(MapProjection.longitude(MapProjection.x(lon)),lon,1e-9,"longitude round trip");
        var home=new MapProjection.Geo(41.50734,-74.0886);var target=MapProjection.local(home,1000,1500);
        near(Math.toRadians(target.lat()-home.lat())*6371000,1000,1e-5,"local route to latitude is inverse of telemetry projection");
        near(Math.toRadians(target.lon()-home.lon())*6371000*Math.cos(Math.toRadians(home.lat())),1500,1e-5,"local route to longitude is inverse of telemetry projection");
        for(int[] size:new int[][]{{668,330},{210,240},{1000,500},{508,220}}){var c=MapProjection.fit(List.of(home,target),home,size[0],size[1],0,0,0);for(var p:List.of(home,target)){var xy=c.point(p);check(xy.x>15&&xy.x<size[0]-15&&xy.y>15&&xy.y<size[1]-15,"route remains on basemap viewport");}check(c.metresPerPixel()>0,"geographic scale valid");var moved=c.pan(15,-10).point(home);var before=c.point(home);near(moved.x-before.x,15,1e-6,"pan uses tile camera x");near(moved.y-before.y,-10,1e-6,"pan uses tile camera y");}
        var east=new MapProjection.Geo(10,179.99);var west=new MapProjection.Geo(10,-179.99);var dateLine=MapProjection.fit(List.of(east,west),east,600,400,0,0,0);
        check(dateLine.zoom()>10,"dateline route fits short crossing rather than world extent");check(Math.abs(dateLine.point(east).x-dateLine.point(west).x)<550,"dateline overlay and wrapped tiles align");
        check(!new MapProjection.Geo(89,0).valid(),"polar position cannot be silently clamped onto incorrect map");
        var hover=DashboardPreview.sample();var loop=FlightPathMap.planned(hover,null);check(loop.getFirst().equals(loop.getLast()),"hover route includes its planned return");
        var site=new AirportDatabase.Site("Runway 4/22 midpoint",41.5144,-74.2647,22,1527,30.5,1);var airport=new AirportDatabase.Airport("KMGJ","Orange County",site.latitude(),site.longitude(),112.8,"Airport",List.of(site));var plan=new AirportPlan(airport,site,300,90);var flight=PilotPreview.sample(plan,false);var open=FlightPathMap.planned(flight,plan);
        check(!open.getFirst().equals(open.getLast()),"airport destination does not gain a false return leg");near(open.getLast().lat(),site.latitude(),1e-12,"arrival map marker uses exact airport coordinate");
        long now=1000000;
        var headers=HttpHeaders.of(Map.of("Cache-Control",List.of("public, max-age=420"),"Age",List.of("120")),(a,b)->true);
        check(MapTiles.expiry(headers,now)==now+300000,"tile max-age accounts for upstream cache age");
        check(MapTiles.expiry(HttpHeaders.of(Map.of(),(a,b)->true),now)==now+7L*86400000,"missing cache headers retain tiles at least seven days");
        Color global=UIManager.getColor("Panel.background");
        SwingUtilities.invokeAndWait(()->{JPanel panel=new JPanel();JTextField field=new JTextField("text");JComboBox<String> combo=new JComboBox<>(new String[]{"OK"});panel.add(field);panel.add(combo);PilotTheme.apply(panel);check(field.getBackground().equals(new Color(0x18181B))&&field.getForeground().equals(new Color(0xE0E0E0)),"form controls use requested charcoal and off-white");check(combo.getBackground().equals(PilotTheme.SURFACE),"combo popup uses scoped dark surface");combo.setSize(160,36);combo.doLayout();var image=new java.awt.image.BufferedImage(160,36,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();combo.printAll(g);g.dispose();check((image.getRGB(90,18)&0xffffff)==0x18181B,"selected combo value paints dark, not look-and-feel white");});
        check(Objects.equals(global,UIManager.getColor("Panel.background")),"pilot styling does not recolour the dashboard globally");
        check(PilotDisplay.BG.equals(new Color(0x121212))&&PilotDisplay.MUTED.equals(new Color(0xA3A3A3)),"exact requested base and metadata palette");
        check(PilotDisplay.AMBER.equals(new Color(255,192,73))&&PilotDisplay.RED.equals(new Color(255,105,112)),"safety alert palette preserved");
        System.out.println("PASS: "+checks+" map/theme checks (Mercator alignment, dateline, pan, route semantics, tile expiry, scoped palette).");return checks;
    }
}
