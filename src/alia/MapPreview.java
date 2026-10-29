package alia;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

/** Visible, explicitly labelled map UI preview. Never creates NativeLink or sends controls. */
public final class MapPreview {
    public static void main(String[] args){SwingUtilities.invokeLater(()->{
        var site=new AirportDatabase.Site("Runway 4/22 midpoint",41.5144,-74.2647,22,1527,30.5,1);var airport=new AirportDatabase.Airport("KMGJ","Orange County",site.latitude(),site.longitude(),112.8,"Airport",List.of(site));var plan=new AirportPlan(airport,site,300,90);
        PilotDisplay pilot=new PilotDisplay(()->{},()->{},()->Path.of("C:/X-Plane 12"));pilot.mapPreview();pilot.showPage("Navigation");
        ExplainabilityDashboard dashboard=new ExplainabilityDashboard(()->{},()->{},()->{},()->{});
        JPanel pilotHost=host(pilot),dashboardHost=host(dashboard);JFrame p=window("CCAT | MAP PREVIEW — no aircraft connection",pilotHost,740,1040);JFrame d=window("CCAT | DASHBOARD MAP PREVIEW — no aircraft connection",dashboardHost,1440,940);
        p.setLocation(12,8);d.setLocation(Math.max(40,Toolkit.getDefaultToolkit().getScreenSize().width-d.getWidth()),30);p.toFront();
        Timer refresh=new Timer(200,e->{var f=PilotPreview.sample(plan,false);pilot.update(f,plan);dashboard.update(f);});refresh.start();
        Timer capture=new Timer(20000,e->{try{Path root=Path.of("build/alia");capture(pilotHost,root.resolve("pilot-map-charcoal-preview.png"));capture(dashboardHost,root.resolve("dashboard-basemap-preview.png"));System.out.println("VISIBLE MAP PREVIEW captured; tiles requested only for the shown viewports.");}catch(Exception ex){ex.printStackTrace();}});capture.setRepeats(false);capture.start();
        Timer close=new Timer(35000,e->{refresh.stop();capture.stop();pilot.close();dashboard.close();p.dispose();d.dispose();});close.setRepeats(false);close.start();
    });}
    private static JPanel host(JComponent content){JPanel host=new JPanel(new BorderLayout());JLabel label=new JLabel("MAP UI PREVIEW · SIMULATED ROUTE · NO AIRCRAFT CONTROL",SwingConstants.CENTER);label.setOpaque(true);label.setBackground(new Color(255,192,73));label.setForeground(new Color(18,18,18));label.setFont(new Font("Segoe UI",Font.BOLD,14));label.setBorder(BorderFactory.createEmptyBorder(8,4,8,4));host.add(label,BorderLayout.NORTH);host.add(content);return host;}
    private static JFrame window(String title,JPanel content,int width,int height){JFrame frame=new JFrame(title);frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);frame.setContentPane(content);Dimension screen=Toolkit.getDefaultToolkit().getScreenSize();frame.setSize(Math.min(width,screen.width-30),Math.min(height,screen.height-50));frame.setVisible(true);return frame;}
    private static void capture(JComponent content,Path path)throws Exception{BufferedImage image=new BufferedImage(content.getWidth(),content.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();content.printAll(g);g.dispose();Files.createDirectories(path.getParent());javax.imageio.ImageIO.write(image,"png",path.toFile());}
}
