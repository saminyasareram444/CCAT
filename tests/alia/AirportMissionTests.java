package alia;

import java.io.*;
import java.nio.file.*;
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
        check(a.sites().get(0).surface()==1&&a.sites().get(0).widthM()==20&&a.sites().get(0).lengthM()>1700,"runway surface, width and physical length are retained");
        var pad=airports.get("H01").sites().get(0);
        check(pad.surface()==1&&pad.lengthM()==20&&pad.widthM()==20&&!pad.landingIssue().isEmpty(),"small pad geometry remains inspectable but cannot be used for automatic landing");
        landingSites();
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
        var site=new AirportDatabase.Site("Runway 09/27 midpoint",lat,lon,90,1500,30,1);
        var airport=new AirportDatabase.Airport("TEST","Destination",lat,lon,origin.elevation-origin.agl,"Airport",List.of(site));
        AirportPlan plan=new AirportPlan(airport,site,300,90);plan.validateStart(origin);
        signedAirspeed(plan,origin);
        approach(plan,origin);
        boolean nearRejected=false;
        try{var nearby=new AirportDatabase.Site("Nearby",origin.lat,origin.lon+.001,0,30,30,1);var nearbyAirport=new AirportDatabase.Airport("NEAR","Nearby",origin.lat,nearby.longitude(),0,"Airport",List.of(nearby));new AirportPlan(nearbyAirport,nearby,300,90).validateStart(origin);}catch(IllegalArgumentException ex){nearRejected=true;}
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
        for(int i=0;i<45;i++){time+=.05;out=mission.update(state(destination.north,destination.east,arrivalHeight+30,31.5,90,0,0,0,false,-.20687556266784668),time);}
        check(mission.phase()==Phase.DESCEND,"capture gate starts vertical destination landing");
        check(out.trace.target.east>500,"landing target remains at destination, not departure home");
        for(int i=0;i<25;i++){time+=.05;out=mission.update(state(destination.north,destination.east,arrivalHeight,origin.agl,90,0,0,0,true,-.20687556266784668),time);}
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
    private static void landingSites() throws Exception {
        for(int code:new int[]{1,2,20,38,50,57})check(new AirportDatabase.Site("Paved",36,-115,0,40,30,code).preferred(),"XP12 paved surface variant accepted: "+code);
        for(int code:new int[]{0,13,14,15})check(!new AirportDatabase.Site("Unsupported",36,-115,0,40,30,code).landingIssue().isEmpty(),"unusable / invisible landing surface rejected: "+code);
        var grass=new AirportDatabase.Site("Runway 09/27 midpoint",41.5611388,-74.0156021,81,475,16.18,3);
        var small=new AirportDatabase.Site("Helipad H1",41.5337044,-73.8337453,0,22.9,22.9,1);
        for(var site:List.of(grass,small,new AirportDatabase.Site("Unknown geometry",36,-115,0))) {
            var airport=new AirportDatabase.Airport("BLOCK","Fixture",site.latitude(),site.longitude(),100,"Airport",List.of(site));boolean blocked=false;
            try{new AirportPlan(airport,site,300,90);}catch(IllegalArgumentException ex){blocked=true;}
            check(blocked,"narrow grass strip / small pad / unknown geometry cannot silently become an automatic landing target");
        }
        Path root=Files.createTempDirectory(Paths.get("build/alia"),"airport-fixture-");
        Path global=root.resolve("Global Scenery/Global Airports/Earth nav data/apt.dat"),custom=root.resolve("Custom Scenery/Override/Earth nav data/apt.dat"),ini=root.resolve("Custom Scenery/scenery_packs.ini");
        Files.createDirectories(global.getParent());Files.createDirectories(custom.getParent());
        String apt="A\n1200 test\n17 100 0 0 ONLY Visible Heliport\n1302 gui_label 3D\n102 H1 36.1 -115.1 90 40 30 20 0 0 0 0\n99\n";
        Files.writeString(global,apt.replace("ONLY","HIDDEN").replace("3D","2D"));Files.writeString(custom,apt);
        Files.writeString(ini,"SCENERY_PACK Custom Scenery/Override/\nSCENERY_PACK_DISABLED *GLOBAL_AIRPORTS*\n");
        AirportDatabase db=AirportDatabase.load(root);
        check(db.size()==1&&db.search("HIDDEN",null,10).isEmpty(),"disabled global airports are not silently re-added by fallback indexing");
        check(db.search("ONLY",null,10,true,true).size()==1,"default filters accept a modeled paved site with sufficient dimensions");
        var loaded=db.search("ONLY",null,1).get(0);
        check(loaded.scenery().equals("3D")&&loaded.source().endsWith("apt.dat")&&loaded.sites().get(0).surface()==20,"scenery metadata and source provenance are retained");
        var cached=AirportDatabase.load(root).search("ONLY",null,1).get(0);
        check(cached.equals(loaded),"versioned cache roundtrip preserves exact site dimensions, surface, scenery label and source");
        Files.writeString(ini,"SCENERY_PACK Custom Scenery/Override/\nSCENERY_PACK *GLOBAL_AIRPORTS*\n");
        db=AirportDatabase.load(root);
        check(db.size()==2&&db.search("HIDDEN",null,10,true,true).isEmpty()&&db.search("HIDDEN",null,10,false,false).size()==1,"2D sites can be inspected with filters cleared but are hidden by default");
    }
    private static void approach(AirportPlan plan,NativeLink.Position origin) {
        AirportMission mission=new AirportMission(plan,origin,.6);mission.start(0);double time=0,heading=plan.course(origin);
        for(int i=0;i<45;i++){time+=.05;mission.update(state(0,0,40,41.5,heading,0,0,0,false,0),time);}
        for(int i=0;i<65;i++){time+=.05;mission.update(state(0,100,50,51.5,heading,0,38,0,false,75),time);}
        Point dest=mission.route().get(mission.route().size()-1);time+=.05;
        Output out=mission.update(state(dest.north,dest.east-3500,300,301.5,90,0,47.6,0,false,90),time);
        check(out.phase==Phase.APPROACH,"recorded cruise ground speed initiates approach earlier than the old 2.4 km gate");
        for(int i=0;i<200;i++){time+=.05;out=mission.update(state(dest.north,dest.east-3000,300,301.5,90,0,47.6,0,false,90),time);}
        check(out.trace.terms.get("targetPitch")>10&&out.trace.target.height==300,"weak braking increases nose-up demand while holding height instead of descending in a fast glide");
        time+=.05;out=mission.update(state(dest.north,dest.east-1000,300,301.5,90,0,36,0,false,70),time);
        check(out.trace.terms.get("requestedLift")>0,"approach restores some rotor support already at 70 kt");
        time+=.05;out=mission.update(state(dest.north,dest.east+100,100,101.5,90,0,20,0,false,40),time);
        double reducedHeight=out.trace.target.height;
        check(out.trace.terms.get("approachAlongRemaining")<0&&out.trace.terms.get("approachSpeedTarget")==0,"passing the destination cannot reverse the stopping-distance schedule and demand more forward speed");
        time+=.05;out=mission.update(state(dest.north,dest.east+1200,100,101.5,90,0,20,0,false,40),time);
        check(out.trace.target.height<=reducedHeight,"overshoot does not command a climb back to cruise altitude");
        time+=.05;out=mission.update(state(dest.north,dest.east+1200,70,71.5,90,0,5,0,false,10),time);
        check(out.phase==Phase.HOVER_CAPTURE,"slow flight enters rotor capture even outside the old 450 m gate");
        time+=.05;out=mission.update(state(dest.north,dest.east+1200,60,61.5,90,0,0,0,false,0),time);
        check(out.trace.terms.get("targetEastSpeed")==-12,"rotor recovery uses a 12 m/s bounded target instead of the old slow distant return");
    }
    private static void signedAirspeed(AirportPlan plan,NativeLink.Position origin) {
        // Actual paused-flight IAS, AGL, attitude and velocity from 20261022-172427.
        double measuredIas=-.20687556266784668,heading=plan.course(origin),time=.05;
        AirportMission signed=new AirportMission(plan,origin,.6),zero=new AirportMission(plan,origin,.6);
        signed.start(0);zero.start(0);
        State recorded=new State(.675,.999,1.779,1.791,2.456,-.118,heading,.142,.246,1.291,0,0,0,false,measuredIas);
        Output negative=signed.update(recorded,time);
        Output baseline=zero.update(new State(recorded.north,recorded.east,recorded.height,recorded.agl,
            recorded.pitch,recorded.roll,recorded.heading,recorded.vNorth,recorded.vEast,recorded.vUp,0,0,0,false,0),time);
        check(negative.phase==Phase.TAKEOFF&&negative.lift>0,"recorded negative-IAS liftoff sample continues vertical takeoff");
        check(negative.lift==baseline.lift&&negative.pitch==baseline.pitch&&negative.roll==baseline.roll&&negative.yaw==baseline.yaw&&negative.pusher==0,
            "negative near-hover IAS leaves rotor control identical to zero IAS");
        check(negative.trace.terms.get("airspeedKnots")==measuredIas&&negative.trace.terms.get("forwardAirspeedKnots")==0,
            "trace preserves signed measured IAS separately from nonnegative forward control speed");
        for(int i=0;i<45;i++){time+=.05;signed.update(state(0,0,40,41.5,heading,0,0,0,false,measuredIas),time);}
        check(signed.phase()==Phase.TRANSITION,"negative hover IAS does not block the stabilized takeoff gate");
        for(int i=0;i<65;i++){time+=.05;negative=signed.update(state(0,100,50,51.5,heading,0,.3,0,false,measuredIas),time);}
        check(signed.phase()==Phase.TRANSITION&&negative.trace.terms.get("wingSupportFraction")==0,
            "negative IAS cannot pass the 70 kt transition gate or remove rotor support");
        for(int i=0;i<65;i++){time+=.05;signed.update(state(0,100,50,51.5,heading,0,38,0,false,75),time);}
        check(signed.phase()==Phase.CRUISE,"valid forward IAS is still required to enter cruise");
        Point destination=signed.route().get(signed.route().size()-1);
        boolean underspeed=false;
        try {for(int i=0;i<45;i++){time+=.05;signed.update(state(destination.north/2,destination.east/2,300,301.5,heading,0,20,0,false,measuredIas),time);}}
        catch(IllegalStateException ex){underspeed=ex.getMessage().contains("55 kt");}
        check(underspeed,"negative cruise IAS still trips the persistent 55 kt underspeed guard");
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            AirportMission missing=new AirportMission(plan,origin,.6);missing.start(0);boolean rejected=false;
            try{missing.update(state(0,0,1,2.5,heading,0,0,1,false,invalid),.05);}
            catch(IllegalStateException ex){rejected=ex.getMessage().contains("indicated airspeed");}
            check(rejected,"non-finite IAS remains invalid telemetry: "+invalid);
        }
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
