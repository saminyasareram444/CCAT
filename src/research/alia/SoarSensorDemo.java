package alia;

import javax.swing.*;
import java.awt.event.*;
import java.nio.file.*;

/** Offline first: no NativeLink is created in this entry point. */
public final class SoarSensorDemo {
    public static void main(String[] args)throws Exception{
        SensorResearch research=new SoarSensorResearch();
        if(java.util.Arrays.asList(args).contains("--render")){
            long end=System.nanoTime()+12_000_000_000L;while(!research.view().fresh()&&System.nanoTime()<end)Thread.sleep(20);
            SwingUtilities.invokeAndWait(()->{
                try{
                    PilotDisplay p=new PilotDisplay(()->{},()->{},()->Path.of("C:/X-Plane 12"));p.offlineResearch();p.sensorResearch(research);p.showPage("Sensors");
                    for(int[] size:new int[][]{{700,1000},{540,680},{1000,700}}){
                        p.setSize(size[0],size[1]);layout(p);scroll(p,false);layout(p);capture(p,size,"");scroll(p,true);layout(p);capture(p,size,"-scores");
                    }p.close();
                }catch(Exception e){throw new RuntimeException(e);}
            });research.close();return;
        }
        SwingUtilities.invokeLater(()->{
            PilotDisplay p=new PilotDisplay(()->JOptionPane.showMessageDialog(null,"Offline sensor demo: no aircraft is connected."),()->{},()->Path.of("C:/X-Plane 12"));
            p.offlineResearch();p.sensorResearch(research);p.showPage("Sensors");p.open();
            var window=(java.awt.Window)SwingUtilities.getWindowAncestor(p);window.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){p.close();research.close();}});
        });
    }
    private static void layout(java.awt.Container p){p.doLayout();for(var c:p.getComponents())if(c instanceof java.awt.Container child)layout(child);}
    private static void scroll(java.awt.Container p,boolean bottom){for(var c:p.getComponents()){if(c instanceof JScrollPane s)s.getVerticalScrollBar().setValue(bottom?s.getVerticalScrollBar().getMaximum():0);else if(c instanceof java.awt.Container child)scroll(child,bottom);}}
    private static void capture(PilotDisplay p,int[] size,String suffix)throws Exception {var image=new java.awt.image.BufferedImage(size[0],size[1],java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();p.printAll(g);g.dispose();Path out=Path.of("research/local/sensor-demo/pilot-"+size[0]+"x"+size[1]+suffix+".png");Files.createDirectories(out.getParent());javax.imageio.ImageIO.write(image,"png",out.toFile());}
}
