package alia;

import java.util.*;
import static alia.HoverMission.*;

/** Experimental ALIA lift+cruise control. Requires live tuning; no terrain/traffic avoidance. */
final class AirportMission implements MissionController {
    // Version this identifier whenever the approach procedure changes; shadow models are conditional on it.
    static final String BRAKING_POLICY_ID="alia-airport-brake-hold-v1";
    private final AirportPlan plan;
    private final List<Point> route;
    private final Point destination;
    private final double cruiseHeight,landingHeight,clearance,baseHover,initialCourse,maxRadius;
    private Phase phase=Phase.IDLE;
    private int waypoint;
    private double lastTime,phaseStart,dwell,lift,pusher,liftIntegral,speedIntegral,approachHeading;
    private double approachStartDistance;
    private double approachNorth,approachEast,approachEntrySpeed,approachHeight,captureBudget=180;
    private double lowAirspeedSeconds;
    AirportMission(AirportPlan plan,NativeLink.Position start,double hover) {
        plan.validateStart(start);
        if(!Double.isFinite(hover)||hover<.25||hover>.85)throw new IllegalArgumentException("Hover throttle must be 0.25–0.85");
        this.plan=plan;route=plan.points(start);destination=route.get(route.size()-1);
        cruiseHeight=plan.cruiseHeight(start);landingHeight=plan.destinationHeight(start);clearance=Math.max(.5,start.agl);
        baseHover=hover;initialCourse=plan.course(start);maxRadius=plan.distance(start)+3000;
    }
    public Phase phase(){return phase;}
    public List<Point> route(){return route;}
    public double radiusLimit(){return maxRadius;}
    public double maximumHeight(){return cruiseHeight+200;}
    public double minimumHeight(){return Math.min(-20,landingHeight-100);}
    public String destination(){return plan.label();}
    public void start(double time){phase=Phase.TAKEOFF;lastTime=phaseStart=time;}
    public void stop(){phase=Phase.STOPPED;}
    private void change(Phase next,double time){phase=next;phaseStart=time;dwell=0;}
    public Output update(State s,double time) {
        double dt=clamp(time-lastTime,.01,.15);lastTime=time;
        if(phase==Phase.IDLE||phase==Phase.STOPPED||phase==Phase.LANDED)return new Output(0,0,0,0,0,0,phase,waypoint,null);
        if(!Double.isFinite(s.airspeed))throw new IllegalStateException("Missing or non-finite indicated airspeed during "+phase);
        // X-Plane reports signed IAS: slight reverse airflow during rotor flight can
        // produce negative readings. Preserve the measurement in the trace, but use
        // zero forward speed for scheduling; never interpret its magnitude as wing lift.
        double forwardAirspeed=Math.max(0,s.airspeed);
        lowAirspeedSeconds=phase==Phase.CRUISE&&forwardAirspeed<55?lowAirspeedSeconds+dt:0;
        if(lowAirspeedSeconds>2)throw new IllegalStateException("Cruise airspeed below 55 kt for 2 seconds; pause and take over");
        Phase computed=phase;int computedWaypoint=waypoint;double age=time-phaseStart;
        double budget=phase==Phase.CRUISE?Math.max(300,maxRadius/(plan.cruiseKnots()*.514444)*2+180):
            phase==Phase.APPROACH?Math.max(240,approachStartDistance/8*2+120):phase==Phase.HOVER_CAPTURE?captureBudget:180;
        if(age>budget)throw new IllegalStateException("Airport mission phase timed out: "+phase);
        if(Math.abs(s.pitch)>35||Math.abs(s.roll)>35||s.height>maximumHeight()||s.height<minimumHeight()||Math.hypot(s.north,s.east)>maxRadius)
            throw new IllegalStateException("Aircraft exceeded airport mission limits");
        if((phase==Phase.CRUISE||phase==Phase.TRANSITION)&&s.agl<15&&age>5)
            throw new IllegalStateException("Terrain clearance below 15 m during forward flight");
        double toDestination=Math.hypot(destination.north-s.north,destination.east-s.east),groundSpeed=Math.hypot(s.vNorth,s.vEast);
        // Reserve a braking segment and a 250 m rotor-only final segment. The old
        // glide assumed 0.65 m/s² while descending and substantially under-braked.
        double brakingDistance=Math.max(2200,groundSpeed*groundSpeed/(2*.35)+groundSpeed*8+500);
        double alongRemaining=(destination.north-s.north)*approachNorth+(destination.east-s.east)*approachEast;
        double approachSpeedTarget=Math.min(Math.max(0,approachEntrySpeed-.55*age),Math.sqrt(2*.35*Math.max(0,alongRemaining-250)));
        if(phase==Phase.APPROACH&&forwardAirspeed<45&&groundSpeed<22)approachHeight=Math.min(approachHeight,landingHeight+40);
        Point target=phase==Phase.TAKEOFF?new Point(0,0,40):phase==Phase.CRUISE?route.get(waypoint):destination;
        double heightTarget=phase==Phase.TAKEOFF?40:phase==Phase.CRUISE||phase==Phase.TRANSITION?cruiseHeight:
            phase==Phase.APPROACH?approachHeight:
            phase==Phase.HOVER_CAPTURE?landingHeight+30:landingHeight;
        target=new Point(target.north,target.east,heightTarget);
        double dn=target.north-s.north,de=target.east-s.east,distance=Math.hypot(dn,de);
        double targetHeading=phase==Phase.TAKEOFF||phase==Phase.TRANSITION?initialCourse:
            phase==Phase.APPROACH?approachHeading:phase==Phase.HOVER_CAPTURE||phase==Phase.DESCEND?plan.site().heading():Math.toDegrees(Math.atan2(de,dn));
        double headingError=wrap(targetHeading-s.heading);
        double targetClimb=clamp((heightTarget-s.height)*.18,-2,3);
        boolean rotor=phase==Phase.TAKEOFF||phase==Phase.HOVER_CAPTURE||phase==Phase.DESCEND;
        if(rotor)targetClimb=clamp((heightTarget-s.height)*.6,-1,1.5);
        if(phase==Phase.DESCEND)targetClimb=distance>2.5||groundSpeed>.9?0:-clamp((s.agl-clearance)*.25,.2,.75);
        double climbError=targetClimb-s.vUp;
        if(!s.ground&&lift>.2)liftIntegral=clamp(liftIntegral+climbError*dt*.045,-.3,.3);
        double wingFraction=rotor?0:phase==Phase.APPROACH?smooth((forwardAirspeed-25)/65):smooth((forwardAirspeed-40)/30);
        double desiredLift=clamp((baseHover+climbError*.1+liftIntegral)*(1-wingFraction),0,.95);
        if(phase==Phase.CRUISE)desiredLift=0;
        if(phase==Phase.TRANSITION&&s.height<20)desiredLift=Math.max(desiredLift,baseHover);
        double cap=phase==Phase.TAKEOFF||phase==Phase.DESCEND?2:12;
        double targetSpeed=Math.min(cap,Math.min(distance*.32,Math.sqrt(2*.6*distance)));
        double vn=distance>.001?dn/distance*targetSpeed:0,ve=distance>.001?de/distance*targetSpeed:0;
        if(phase==Phase.APPROACH){vn=approachNorth*approachSpeedTarget;ve=approachEast*approachSpeedTarget;}
        double angle=Math.toRadians(s.heading),an=clamp((vn-s.vNorth)*.65,-2.2,2.2),ae=clamp((ve-s.vEast)*.65,-2.2,2.2);
        double rotorPitch=-Math.toDegrees(Math.atan2(an*Math.cos(angle)+ae*Math.sin(angle),9.80665));
        double rotorRoll=Math.toDegrees(Math.atan2(ae*Math.cos(angle)-an*Math.sin(angle),9.80665));
        double wingPitch=clamp(3+Math.toDegrees(Math.atan2(targetClimb,Math.max(15,forwardAirspeed*.514444))),-8,12);
        double wingRoll=clamp(headingError*.65-s.r*.2,-22,22);
        if(phase==Phase.APPROACH)wingPitch=Math.max(wingPitch,clamp(6+(groundSpeed-approachSpeedTarget)*1.25,6,22));
        double blend=rotor?0:phase==Phase.APPROACH?smooth((forwardAirspeed-25)/25):1;
        double pitchTarget=rotorPitch*(1-blend)+wingPitch*blend,rollTarget=rotorRoll*(1-blend)+wingRoll*blend;
        double speedError=plan.cruiseKnots()-forwardAirspeed;
        if(phase==Phase.CRUISE)speedIntegral=clamp(speedIntegral+speedError*dt*.001,-.2,.2);
        double desiredPusher=phase==Phase.TRANSITION?.8:phase==Phase.CRUISE?clamp(.55+speedError*.015+speedIntegral,.1,.95):0;
        double previousLift=lift;
        lift+=clamp(desiredLift-lift,-.25*dt,.25*dt);pusher+=clamp(desiredPusher-pusher,-.6*dt,.15*dt);
        double pitch=clamp(.075*(pitchTarget-s.pitch)-.025*s.q,-.5,.5),roll=clamp(.075*(rollTarget-s.roll)-.025*s.p,-.5,.5);
        double yaw=clamp(.025*headingError-.018*s.r,-.4,.4);
        String gate;
        switch(phase) {
            case TAKEOFF -> {
                gate="Within 2 m of 40 m takeoff height; ground speed < 1 m/s; vertical speed < 0.7 m/s; heading error < 10°, for 2 s.";
                dwell=Math.abs(s.height-40)<2&&groundSpeed<1&&Math.abs(s.vUp)<.7&&Math.abs(headingError)<10?dwell+dt:0;
                if(age>45&&s.height<2)throw new IllegalStateException("No liftoff in airport mission");
                if(dwell>2)change(Phase.TRANSITION,time);
            }
            case TRANSITION -> {
                gate="Indicated airspeed ≥ 70 kt, height ≥ 35 m, vertical speed > −1 m/s, sustained for 3 s.";
                dwell=forwardAirspeed>=70&&s.height>=35&&s.vUp> -1?dwell+dt:0;
                if(dwell>3)change(Phase.CRUISE,time);
            }
            case CRUISE -> {
                gate=String.format(Locale.ROOT,"Begin braking within %.0f m; hold height until slow, then capture the destination on rotors.",brakingDistance);
                if(toDestination<brakingDistance){
                    approachHeading=s.heading;approachStartDistance=toDestination;approachEntrySpeed=groundSpeed;
                    approachNorth=(destination.north-s.north)/Math.max(1,toDestination);approachEast=(destination.east-s.east)/Math.max(1,toDestination);
                    approachHeight=Math.max(s.height,landingHeight+40);change(Phase.APPROACH,time);
                }
                else if(distance<300&&waypoint<route.size()-1)waypoint++;
            }
            case APPROACH -> {
                gate=String.format(Locale.ROOT,"Brake toward %.1f m/s before the 250 m final segment. Ground speed < 10 m/s and IAS < 30 kt enables rotor capture.",approachSpeedTarget);
                if(groundSpeed<10&&forwardAirspeed<30){
                    captureBudget=Math.max(180,toDestination/8*2+Math.abs(s.height-landingHeight-30)/1.0+120);
                    change(Phase.HOVER_CAPTURE,time);
                }
            }
            case HOVER_CAPTURE -> {
                gate="Destination error < 2.5 m; ground speed < 0.7 m/s; hover height error < 2 m; |vertical speed| < 0.5 m/s, for 2 s.";
                dwell=distance<2.5&&groundSpeed<.7&&Math.abs(s.height-heightTarget)<2&&Math.abs(s.vUp)<.5?dwell+dt:0;
                if(dwell>2)change(Phase.DESCEND,time);
            }
            case DESCEND -> {
                gate="Ground contact and |vertical speed| < 0.5 m/s sustained for 1 s. Descent pauses if horizontal capture is lost.";
                dwell=s.ground&&Math.abs(s.vUp)<.5?dwell+dt:0;if(dwell>1){change(Phase.LANDED,time);lift=0;pusher=0;}
            }
            default -> gate="No automatic control step.";
        }
        Map<String,Double> terms=new LinkedHashMap<>();
        terms.put("northError",dn);terms.put("eastError",de);terms.put("heightError",heightTarget-s.height);
        terms.put("targetNorthSpeed",vn);terms.put("targetEastSpeed",ve);terms.put("targetClimb",targetClimb);terms.put("climbError",climbError);
        terms.put("baseLift",baseHover);terms.put("verticalP",climbError*.1);terms.put("verticalI",liftIntegral);
        terms.put("wingSupportFraction",wingFraction);terms.put("requestedLift",desiredLift);terms.put("appliedLift",lift);
        terms.put("previousLift",previousLift);terms.put("stepSeconds",dt);
        terms.put("liftMinimum",0.0);terms.put("liftMaximum",.95);terms.put("liftFloor",computed==Phase.TRANSITION&&s.height<20?baseHover:0.0);
        terms.put("liftRiseRate",.25);terms.put("liftFallRate",.25);
        terms.put("targetPitch",pitchTarget);terms.put("targetRoll",rollTarget);terms.put("pitchP",.075*(pitchTarget-s.pitch));terms.put("pitchD",-.025*s.q);
        terms.put("rollP",.075*(rollTarget-s.roll));terms.put("rollD",-.025*s.p);terms.put("headingError",headingError);terms.put("yawP",.025*headingError);terms.put("yawD",-.018*s.r);
        terms.put("requestedPusher",desiredPusher);terms.put("appliedPusher",pusher);terms.put("airspeedKnots",s.airspeed);terms.put("targetAirspeedKnots",plan.cruiseKnots());
        terms.put("forwardAirspeedKnots",forwardAirspeed);
        terms.put("destinationDistance",toDestination);terms.put("brakingDistance",brakingDistance);terms.put("phaseSeconds",age);terms.put("settledSeconds",dwell);
        terms.put("underspeedSeconds",lowAirspeedSeconds);
        if(computed==Phase.APPROACH){terms.put("approachAlongRemaining",alongRemaining);terms.put("approachSpeedTarget",approachSpeedTarget);terms.put("approachHeightHold",approachHeight);}
        String transition=phase!=computed?computed+" → "+phase+": "+gate:waypoint!=computedWaypoint?"Advanced to route leg "+(waypoint+1):"";
        return new Output(pitch,roll,yaw,lift,pusher,distance,phase,waypoint,new Trace(computed,computedWaypoint,target,s,terms,gate,transition));
    }
    private static double smooth(double x){x=clamp(x,0,1);return x*x*(3-2*x);}
}
