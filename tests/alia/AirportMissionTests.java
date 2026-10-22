package alia;

import java.io.*;
import java.util.*;
import static alia.HoverMission.*;

/** Offline gate/geometry tests; these are not a validation of X-Plane aerodynamics. */
final class AirportMissionTests {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static int run(NativeLink.Snapshot source) throws Exception {
        Map<String,AirportDatabase.Airport> airports=new LinkedHashMap<>();
        String apt="A\n1200 test\n1 100 0 0 TEST Test Field\n1302 datum_lat 36.0\n1302 datum_lon -115.0\n1302 icao_code KTST\n100 20 1 0 0.25 1 2 0 09 36.0000 -115.0100 0 0 1 0 0 0 27 36.0000 -114.9900 0 0 1 0 0 0\n17 200 0 0 H01 Test Helipad\n102 H1 36.1000 -115.1000 90 20 20 1 0 0 0 0\n99\n";
        AirportDatabase.parse(new BufferedReader(new StringReader(apt)),airports);
        check(airports.size()==2,"airport and heliport imported");var a=airports.get("KTST");
        check(a!=null&&a.sites().size()==1,"ICAO metadata and runway arrival point parsed");
        check(Math.abs(a.elevationM()-30.48)<1e-6,"airport elevation feet converted to metres");
        check(Math.abs(a.sites().get(0).longitude()+115)<1e-6,"runway midpoint used instead of guessed airport position");
        check(airports.get("H01").sites().get(0).heading()==90,"helipad heading and coordinates imported");
        AirportDatabase.parse(new BufferedReader(new StringReader(apt.replace("Test Field","Lower Priority"))),airports);
        check(airports.get("KTST").name().equals("Test Field"),"higher-priority scenery wins duplicates");
        AirportDatabase db=new AirportDatabase(new ArrayList<>(airports.values()));
        check(db.search("ktst",null,10).get(0).id().equals("KTST"),"case-insensitive ICAO search");
        check(db.search("helipad",null,10).size()==1,"airport-name search");
        Map<String,AirportDatabase.Airport> tabbed=new LinkedHashMap<>();
        AirportDatabase.parse(new BufferedReader(new StringReader(apt.replace(" ","\t"))),tabbed);
        check(tabbed.size()==2&&tabbed.get("KTST").sites().size()==1,"apt.dat records accept tab-separated whitespace");
        check(Math.abs(AirportDatabase.distance(0,0,0,1)-111194.93)<2,"geographic distance in metres");
        check(Math.abs(AirportDatabase.bearing(0,0,0,1)-90)<1e-6,"initial course in degrees true");

        NativeLink.Position origin=source.pos;
        double lat=origin.lat,lon=origin.lon+.1;
        var site=new AirportDatabase.Site("Runway 09/27 midpoint",lat,lon,90);
        var airport=new AirportDatabase.Airport("TEST","Destination",lat,lon,origin.elevation-origin.agl,"Airport",List.of(site));
        AirportPlan plan=new AirportPlan(airport,site,300,90);plan.validateStart(origin);
        boolean nearRejected=false;
        try{var nearby=new AirportDatabase.Site("Nearby",origin.lat,origin.lon+.001,0);new AirportPlan(airport,nearby,300,90).validateStart(origin);}catch(IllegalArgumentException ex){nearRejected=true;}
        check(nearRejected,"insufficient transition distance is rejected, not silently shortened");
        check(plan.distance(origin)>2500&&plan.distance(origin)<100000,"selected destination inside transition envelope");
        check(plan.points(origin).get(plan.points(origin).size()-1).east>500,"airport mode is not restricted to the legacy 500 m route");
        AirportMission mission=new AirportMission(plan,origin,.6);mission.start(0);double time=0,heading=plan.course(origin);
        Output out=null;for(int i=0;i<45;i++){time+=.05;out=mission.update(state(0,0,40,41.5,heading,0,0,0,false,0),time);}
        check(mission.phase()==Phase.TRANSITION,"takeoff gate advances to transition");
        for(int i=0;i<65;i++){time+=.05;out=mission.update(state(0,100,50,51.5,heading,0,38,0,false,75),time);}
        check(mission.phase()==Phase.CRUISE&&out.pusher>0,"airspeed gate enables cruise with separate pusher command");
        Point destination=mission.route().get(mission.route().size()-1);
        time+=.05;out=mission.update(state(destination.north/2,destination.east/2,plan.cruiseHeight(origin),301.5,heading,0,46,0,false,90),time);
        check(out.trace.computedPhase==Phase.CRUISE&&out.trace.terms.get("requestedLift")==0,"wing-borne cruise requests idle lift rotors");
        check(out.pusher>=0&&out.pusher<=.95,"pusher output is bounded");
        time+=.05;out=mission.update(state(destination.north,destination.east-1000,150,151.5,heading,0,40,0,false,80),time);
        check(mission.phase()==Phase.APPROACH,"destination distance initiates braking approach");
        time+=.05;out=mission.update(state(destination.north,destination.east-200,50,51.5,heading,0,5,0,false,10),time);
        check(mission.phase()==Phase.HOVER_CAPTURE,"slow approach switches to destination hover capture");
        double arrivalHeight=plan.destinationHeight(origin);
        for(int i=0;i<45;i++){time+=.05;out=mission.update(state(destination.north,destination.east,arrivalHeight+30,31.5,90,0,0,0,false,0),time);}
        check(mission.phase()==Phase.DESCEND,"capture gate starts vertical destination landing");
        check(out.trace.target.east>500,"landing target remains at destination, not departure home");
        for(int i=0;i<25;i++){time+=.05;out=mission.update(state(destination.north,destination.east,arrivalHeight,origin.agl,90,0,0,0,true,0),time);}
        check(mission.phase()==Phase.LANDED&&out.lift==0&&out.pusher==0,"ground contact idles both propulsion groups");
        check(mission.radiusLimit()>plan.distance(origin),"airport-specific bounds replace hover-test radius");
        AirportMission timed=new AirportMission(plan,origin,.6);timed.start(0);double clock=0;
        for(int i=0;i<45;i++){clock+=.05;timed.update(state(0,0,40,41.5,heading,0,0,0,false,0),clock);}
        for(int i=0;i<65;i++){clock+=.05;timed.update(state(0,100,50,51.5,heading,0,38,0,false,75),clock);}
        clock+=200;
        check(timed.update(state(destination.north/2,destination.east/2,300,301.5,heading,0,46,0,false,90),clock).phase==Phase.CRUISE,"airport cruise can exceed the old 180-second hover phase timeout");
        boolean underspeed=false;
        try{for(int i=0;i<45;i++){clock+=.05;timed.update(state(destination.north/2,destination.east/2,300,301.5,heading,0,20,0,false,40),clock);}}catch(IllegalStateException ex){underspeed=ex.getMessage().contains("55 kt");}
        check(underspeed,"persistent wing-cruise underspeed requests takeover instead of continuing with lift rotors idle");
        try(var model=new ExplainabilityModel(false)) {
            var frame=model.update(source,"CRUISE",false,0,origin,mission.route(),out,"Airport fixture",mission);
            check(frame.limits().destination().contains("TEST")&&frame.limits().radius()>650,"dashboard receives selected-airport mission limits");
        }
        battery(source);
        System.out.println("PASS: "+checks+" airport / battery checks (scenery parsing, routing, transition gates, destination landing, measured power).");return checks;
    }
    private static State state(double n,double e,double h,double agl,double heading,double vn,double ve,double vu,boolean ground,double ias) {
        return new State(n,e,h,agl,0,0,heading,vn,ve,vu,0,0,0,ground,ias);
    }
    private static void battery(NativeLink.Snapshot source) {
        double[] refs=source.refs.clone();long[] times=new long[refs.length];Arrays.fill(times,System.nanoTime());
        refs[22]=65000;for(int i=0;i<5;i++){refs[23+i]=52000;refs[28+i]=800;refs[33+i]=100;}
        var s=new NativeLink.Snapshot(source.pos,refs,times);BatteryStatus battery=BatteryStatus.from(s);
        check(battery.chargeAvailable()&&Math.abs(battery.chargePercent()-80)<1e-9,"charge comes from measured Wh divided by capacity");
        check(battery.remainingKwh()==260&&battery.minimumPercent()==80,"five-pack energy and minimum charge");
        check(battery.powerAvailable()&&battery.powerKw()==400,"electrical power sums all five V × I measurements");
        refs[33]=-100;refs[34]=-100;refs[35]=-100;refs[36]=-100;refs[37]=-100;
        check(BatteryStatus.from(new NativeLink.Snapshot(source.pos,refs,times)).powerKw()==-400,"signed charging power is retained");
        times[23]=System.nanoTime()-2_000_000_000L;var missing=new NativeLink.Snapshot(source.pos,refs,times);
        check(missing.fresh(),"missing optional battery instrument does not break core connection");
        BatteryStatus stale=BatteryStatus.from(missing);
        check(!stale.chargeAvailable()&&Double.isNaN(stale.chargePercent()),"missing pack measurement never becomes fabricated zero charge");
        check(Double.isFinite(stale.packPercent()[1]),"other measured packs remain visible if one pack is stale");
    }
}
