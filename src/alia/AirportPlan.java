package alia;

import java.util.*;

/** Geographic destination is stored; a new local route is calculated from the actual position at Start. */
record AirportPlan(AirportDatabase.Airport airport,AirportDatabase.Site site,double cruiseClearanceM,double cruiseKnots) {
    AirportPlan {
        Objects.requireNonNull(airport);Objects.requireNonNull(site);
        if(!AirportDatabase.valid(site.latitude(),site.longitude())||!Double.isFinite(airport.elevationM()))throw new IllegalArgumentException("Airport coordinates or elevation unavailable");
        if(!Double.isFinite(cruiseClearanceM)||cruiseClearanceM<100||cruiseClearanceM>1000)throw new IllegalArgumentException("Cruise clearance must be 100–1000 m above the higher endpoint");
        if(!Double.isFinite(cruiseKnots)||cruiseKnots<75||cruiseKnots>110)throw new IllegalArgumentException("Cruise airspeed must be 75–110 kt");
    }
    double distance(NativeLink.Position start){return AirportDatabase.distance(start.lat,start.lon,site.latitude(),site.longitude());}
    double course(NativeLink.Position start){return AirportDatabase.bearing(start.lat,start.lon,site.latitude(),site.longitude());}
    double cruiseHeight(NativeLink.Position start){return Math.max(start.elevation-start.agl,airport.elevationM())+cruiseClearanceM-start.elevation;}
    double destinationHeight(NativeLink.Position start){return airport.elevationM()+start.agl-start.elevation;}
    void validateStart(NativeLink.Position start) {
        double d=distance(start);
        if(d<2500)throw new IllegalArgumentException("Full-transition missions need at least 2.5 km for acceleration and approach. Use the hover-test mode for nearby points.");
        if(d>100000)throw new IllegalArgumentException("Destination is beyond this experimental controller's 100 km limit");
        if(Math.abs(site.latitude())>80||Math.abs(start.lat)>80)throw new IllegalArgumentException("Polar routes are not supported");
    }
    List<HoverMission.Point> points(NativeLink.Position start) {
        List<HoverMission.Point> points=new ArrayList<>();int count=Math.max(1,(int)Math.ceil(distance(start)/10000));
        for(int i=1;i<=count;i++) {
            double[] p=AirportDatabase.interpolate(start.lat,start.lon,site.latitude(),site.longitude(),i/(double)count);
            double north=Math.toRadians(p[0]-start.lat)*6371000;
            double east=Math.toRadians(HoverMission.wrap(p[1]-start.lon))*6371000*Math.cos(Math.toRadians(start.lat));
            points.add(new HoverMission.Point(north,east,cruiseHeight(start)));
        }
        return List.copyOf(points);
    }
    String label(){return airport.id()+" — "+airport.name()+" / "+site.name();}
}
