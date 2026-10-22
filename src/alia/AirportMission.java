package alia;

import java.util.*;
import static alia.HoverMission.*;

/** Experimental ALIA lift+cruise control. Requires live tuning; no terrain/traffic avoidance. */
final class AirportMission implements MissionController {
    private final AirportPlan plan;
    private final List<Point> route;
    private final Point destination;
    private final double cruiseHeight,landingHeight,clearance,baseHover,initialCourse,maxRadius;
    private Phase phase=Phase.IDLE;
    private int waypoint;
    private double lastTime,phaseStart,dwell,lift,pusher,liftIntegral,speedIntegral,approachHeading;
    private double approachStartDistance;
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
        if(!Double.isFinite(s.airspeed)||s.airspeed<0)throw new IllegalStateException("Valid indicated airspeed is required for transition flight");
        lowAirspeedSeconds=phase==Phase.CRUISE&&s.airspeed<55?lowAirspeedSeconds+dt:0;
        if(lowAirspeedSeconds>2)throw new IllegalStateException("Cruise airspeed below 55 kt for 2 seconds; pause and take over");
        Phase computed=phase;int computedWaypoint=waypoint;double age=time-phaseStart;
        double budget=phase==Phase.CRUISE?Math.max(300,maxRadius/(plan.cruiseKnots()*.514444)*2+180):
            phase==Phase.APPROACH?Math.max(240,approachStartDistance/6*2+120):180;
        if(age>budget)throw new IllegalStateException("Airport mission phase timed out: "+phase);
        if(Math.abs(s.pitch)>35||Math.abs(s.roll)>35||s.height>maximumHeight()||s.height<minimumHeight()||Math.hypot(s.north,s.east)>maxRadius)
            throw new IllegalStateException("Aircraft exceeded airport mission limits");
        if((phase==Phase.CRUISE||phase==Phase.TRANSITION)&&s.agl<15&&age>5)
            throw new IllegalStateException("Terrain clearance below 15 m during forward flight");
        double toDestination=Math.hypot(destination.north-s.north,destination.east-s.east),groundSpeed=Math.hypot(s.vNorth,s.vEast);
        double brakingDistance=Math.max(1600,groundSpeed*groundSpeed/(2*.65)+650);
        Point target=phase==Phase.TAKEOFF?new Point(0,0,40):phase==Phase.CRUISE?route.get(waypoint):destination;
        double heightTarget=phase==Phase.TAKEOFF?40:phase==Phase.CRUISE||phase==Phase.TRANSITION?cruiseHeight:
            phase==Phase.APPROACH?landingHeight+40+(cruiseHeight-landingHeight-40)*clamp((toDestination-500)/Math.max(500,brakingDistance-500),0,1):
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
        double wingFraction=rotor?0:smooth((s.airspeed-40)/30);
        double desiredLift=clamp((baseHover+climbError*.1+liftIntegral)*(1-wingFraction),0,.95);
        if(phase==Phase.CRUISE)desiredLift=0;
        if(phase==Phase.TRANSITION&&s.height<20)desiredLift=Math.max(desiredLift,baseHover);
        double vn=clamp(dn*.32,-6,6),ve=clamp(de*.32,-6,6),v=Math.hypot(vn,ve),cap=phase==Phase.TAKEOFF?2:6;
        if(v>cap){vn*=cap/v;ve*=cap/v;}
        double angle=Math.toRadians(s.heading),an=clamp((vn-s.vNorth)*.65,-1.8,1.8),ae=clamp((ve-s.vEast)*.65,-1.8,1.8);
        double rotorPitch=-Math.toDegrees(Math.atan2(an*Math.cos(angle)+ae*Math.sin(angle),9.80665));
        double rotorRoll=Math.toDegrees(Math.atan2(ae*Math.cos(angle)-an*Math.sin(angle),9.80665));
        double wingPitch=clamp(3+Math.toDegrees(Math.atan2(targetClimb,Math.max(15,s.airspeed*.514444))),-8,12);
        double wingRoll=clamp(headingError*.65-s.r*.2,-22,22);
        if(phase==Phase.APPROACH)wingPitch=Math.max(wingPitch,clamp((groundSpeed-Math.sqrt(2*.65*Math.max(0,toDestination-200)))*.6,0,12));
        double blend=rotor?0:phase==Phase.APPROACH?smooth((s.airspeed-25)/25):1;
        double pitchTarget=rotorPitch*(1-blend)+wingPitch*blend,rollTarget=rotorRoll*(1-blend)+wingRoll*blend;
        double speedError=plan.cruiseKnots()-s.airspeed;
        if(phase==Phase.CRUISE)speedIntegral=clamp(speedIntegral+speedError*dt*.001,-.2,.2);
        double desiredPusher=phase==Phase.TRANSITION?.8:phase==Phase.CRUISE?clamp(.55+speedError*.015+speedIntegral,.1,.95):0;
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
                dwell=s.airspeed>=70&&s.height>=35&&s.vUp> -1?dwell+dt:0;
                if(dwell>3)change(Phase.CRUISE,time);
            }
            case CRUISE -> {
                gate=String.format(Locale.ROOT,"Begin approach within %.0f m of the destination; intermediate route legs advance within 300 m.",brakingDistance);
                if(toDestination<brakingDistance){approachHeading=s.heading;approachStartDistance=toDestination;change(Phase.APPROACH,time);}
                else if(distance<300&&waypoint<route.size()-1)waypoint++;
            }
            case APPROACH -> {
                gate="Ground speed < 12 m/s and destination distance < 450 m; restore lift rotors as airspeed falls.";
                if(groundSpeed<12&&toDestination<450)change(Phase.HOVER_CAPTURE,time);
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
        terms.put("targetPitch",pitchTarget);terms.put("targetRoll",rollTarget);terms.put("pitchP",.075*(pitchTarget-s.pitch));terms.put("pitchD",-.025*s.q);
        terms.put("rollP",.075*(rollTarget-s.roll));terms.put("rollD",-.025*s.p);terms.put("headingError",headingError);terms.put("yawP",.025*headingError);terms.put("yawD",-.018*s.r);
        terms.put("requestedPusher",desiredPusher);terms.put("appliedPusher",pusher);terms.put("airspeedKnots",s.airspeed);terms.put("targetAirspeedKnots",plan.cruiseKnots());
        terms.put("destinationDistance",toDestination);terms.put("brakingDistance",brakingDistance);terms.put("phaseSeconds",age);terms.put("settledSeconds",dwell);
        terms.put("underspeedSeconds",lowAirspeedSeconds);
        String transition=phase!=computed?computed+" → "+phase+": "+gate:waypoint!=computedWaypoint?"Advanced to route leg "+(waypoint+1):"";
        return new Output(pitch,roll,yaw,lift,pusher,distance,phase,waypoint,new Trace(computed,computedWaypoint,target,s,terms,gate,transition));
    }
    private static double smooth(double x){x=clamp(x,0,1);return x*x*(3-2*x);}
}
