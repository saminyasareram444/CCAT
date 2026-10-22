package alia;

import javax.swing.*;
import java.nio.file.*;
import java.util.*;

/** Offline visual/index QA; no UDP connection is opened. */
public final class PlannerPreview {
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("--index")) {
            long start=System.nanoTime();AirportDatabase db=AirportDatabase.load(Paths.get("C:/X-Plane 12"));
            System.out.printf(Locale.ROOT,"Indexed %d installed airports in %.2f s%n",db.size(),(System.nanoTime()-start)/1e9);
            for(String code:new String[]{"KLAS","KLSV","KBTV","6N1","3NK3","KMGJ"})for(var a:db.search(code,null,1)) {
                System.out.printf(Locale.ROOT,"%s | %s | %.6f %.6f | %d runway/helipad locations%n",a,a.scenery(),a.latitude(),a.longitude(),a.sites().size());
                for(var site:a.sites())System.out.println("  "+site.description()+" | "+(site.landingIssue().isEmpty()?"geometry eligible":site.landingIssue()));
            }
            long preferred=db.search("",null,100000,true,true).size();System.out.println("Default paved / 25 m / 3D filter: "+preferred+" airports");
            return;
        }
        SwingUtilities.invokeAndWait(()->{try {
            var dashboard=DashboardPreview.sample();var snapshot=dashboard.telemetry();
            var planner=new MissionPlannerView(()->snapshot,p->{});
            planner.setDatabase(new AirportDatabase(List.of(
                new AirportDatabase.Airport("KLAS","Harry Reid International",36.08,-115.15,665,"Airport",List.of(new AirportDatabase.Site("Runway 01L/19R midpoint",36.08,-115.15,10,2700,45,1)),"3D","Synthetic preview"),
                new AirportDatabase.Airport("KVGT","North Las Vegas",36.21,-115.19,671,"Airport",List.of(new AirportDatabase.Site("Runway 12R/30L midpoint",36.21,-115.19,120,1500,30,1)),"3D","Synthetic preview"),
                new AirportDatabase.Airport("KLSV","Nellis AFB",36.23,-115.03,570,"Airport",List.of(new AirportDatabase.Site("Runway 03L/21R midpoint",36.23,-115.03,30,3000,45,2)),"3D","Synthetic preview")
            )));
            planner.render(Paths.get("build/alia/mission-planner-preview.png"));System.out.println("Offline planner preview: build/alia/mission-planner-preview.png");
        }catch(Exception e){throw new RuntimeException(e);}});
    }
}
