package alia;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import javax.swing.*;

/** Read-only tablet screenshot from actual simulator instrument subscriptions. */
public final class LiveSensorPreview {
    public static void main(String[] args)throws Exception{
        try(NativeLink link=new NativeLink("127.0.0.1",49000);SensorResearch research=new SoarSensorResearch();ExplainabilityModel model=new ExplainabilityModel()){
            long end=System.nanoTime()+12_000_000_000L;while((!link.snapshot().fresh()||!research.view().fresh())&&System.nanoTime()<end)Thread.sleep(25);
            if(!link.snapshot().fresh())throw new IllegalStateException("Live simulator connection required for this read-only preview");
            SwingUtilities.invokeAndWait(()->{
                PilotDisplay p=new PilotDisplay(()->{},()->{},()->Path.of("C:/X-Plane 12"));p.sensorResearch(research);p.showPage("Sensors");
                try{
                    var first=link.snapshot();p.update(model.update(first,"MONITORING",false,0,first.pos,List.of(),null,"Read-only instrument preview",null),null);
                    Thread.sleep(1700); // Allow the existing master-caution recovery latch to settle.
                    for(int[] size:new int[][]{{700,1000},{540,680},{1000,700}}){
                        var s=link.snapshot();p.update(model.update(s,"MONITORING",false,0,s.pos,List.of(),null,"Read-only instrument preview",null),null);
                        p.setSize(size[0],size[1]);layout(p);BufferedImage image=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();p.printAll(g);g.dispose();Path out=Path.of("research/local/sensor-demo/live-pilot-"+size[0]+"x"+size[1]+".png");javax.imageio.ImageIO.write(image,"png",out.toFile());System.out.println(out);
                    }
                }catch(Exception e){throw new RuntimeException(e);}finally{p.close();}
            });
        }
    }
    private static void layout(Container c){c.doLayout();for(var child:c.getComponents())if(child instanceof Container cc)layout(cc);}
}
