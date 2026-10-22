package alia;

import java.util.*;

/** Rotor-borne demonstration mission. Does not implement wing-borne transition or obstacle avoidance. */
public final class HoverMission implements MissionController {
    public enum Phase { IDLE, TAKEOFF, TRANSITION, CRUISE, APPROACH, HOVER_CAPTURE, WAYPOINTS, RETURN, DESCEND, LANDED, STOPPED }
    public static final class Point {
        public final double north,east,height;
        public Point(double n,double e,double h) {north=n;east=e;height=h;}
    }
    public static final class State {
        public final double north,east,height,agl,pitch,roll,heading,vNorth,vEast,vUp,p,q,r;
        public final boolean ground;
        public final double airspeed;
        public State(double n,double e,double h,double a,double pitch,double roll,double heading,
                     double vn,double ve,double vu,double p,double q,double r,boolean ground) {
            this(n,e,h,a,pitch,roll,heading,vn,ve,vu,p,q,r,ground,Double.NaN);
        }
        public State(double n,double e,double h,double a,double pitch,double roll,double heading,
                     double vn,double ve,double vu,double p,double q,double r,boolean ground,double airspeed) {
            north=n;east=e;height=h;agl=a;this.pitch=pitch;this.roll=roll;this.heading=heading;this.airspeed=airspeed;
            vNorth=vn;vEast=ve;vUp=vu;this.p=p;this.q=q;this.r=r;this.ground=ground;
        }
    }
    public static final class Output {
        public final double pitch,roll,yaw,lift,distance,pusher;
        public final Phase phase;
        public final int waypoint;
        public final Trace trace;
        Output(double p,double r,double y,double l,double d,Phase f,int w) {
            this(p,r,y,l,d,f,w,null);
        }
        Output(double p,double r,double y,double l,double d,Phase f,int w,Trace trace) {
            this(p,r,y,l,0,d,f,w,trace);
        }
        Output(double p,double r,double y,double l,double push,double d,Phase f,int w,Trace trace) {
            pitch=p;roll=r;yaw=y;lift=l;distance=d;phase=f;waypoint=w;
            this.trace=trace;pusher=push;
        }
    }
    /** Exact intermediate values from this control step, not a post-hoc model guess. */
    public static final class Trace {
        public final Phase computedPhase;
        public final int computedWaypoint;
        public final Point target;
        public final State state;
        public final Map<String,Double> terms;
        public final String gate,transition;
        Trace(Phase phase,int waypoint,Point target,State state,Map<String,Double> terms,String gate,String transition) {
            computedPhase=phase;computedWaypoint=waypoint;this.target=target;this.state=state;
            this.terms=Collections.unmodifiableMap(new LinkedHashMap<>(terms));
            this.gate=gate;this.transition=transition;
        }
    }
    private final List<Point> route;
    private final double heading,hoverThrottle,maxSpeed;
    private Phase phase=Phase.IDLE;
    private int index;
    private double integrator,lift,lastTime,phaseTime,settledTime;
    private double targetHeight=15;
    public HoverMission(List<Point> points,double heading,double hoverThrottle,double speed) {
        if(points==null || points.isEmpty()) throw new IllegalArgumentException("Enter at least one waypoint");
        if(!Double.isFinite(hoverThrottle)||hoverThrottle<0.25||hoverThrottle>0.85)
            throw new IllegalArgumentException("Hover throttle must be 0.25 to 0.85");
        if(!Double.isFinite(speed)||speed<1||speed>8) throw new IllegalArgumentException("Speed must be 1 to 8 m/s");
        for(Point p:points) if(!Double.isFinite(p.north)||!Double.isFinite(p.east)||!Double.isFinite(p.height)
            || Math.hypot(p.north,p.east)>500 || p.height<10 || p.height>60)
            throw new IllegalArgumentException("Waypoints: within 500 m of start, height 10 to 60 m");
        route=new ArrayList<>(points);this.heading=heading;this.hoverThrottle=hoverThrottle;maxSpeed=speed;
    }
    public Phase phase() {return phase;}
    public List<Point> route() {return Collections.unmodifiableList(route);}
    public void stop() {phase=Phase.STOPPED;}
    public void start(double time) {phase=Phase.TAKEOFF;phaseTime=lastTime=time;}
    private void transition(Phase next,double time) {phase=next;phaseTime=time;settledTime=0;}
    public Output update(State s,double time) {
        double dt=clamp(time-lastTime,0.01,0.15);lastTime=time;
        if(phase==Phase.IDLE||phase==Phase.STOPPED||phase==Phase.LANDED)
            return new Output(0,0,0,0,0,phase,index);
        Phase computedPhase=phase;int computedIndex=index;
        double phaseSeconds=time-phaseTime;
        if(time-phaseTime>180) throw new IllegalStateException("Flight phase timed out: "+phase);
        if(Math.abs(s.roll)>35||Math.abs(s.pitch)>35||s.height>85||s.height< -5||Math.hypot(s.north,s.east)>650)
            throw new IllegalStateException("Aircraft exceeded test flight limits");
        Point target=new Point(0,0,targetHeight);
        if(phase==Phase.WAYPOINTS) {target=route.get(index);targetHeight=target.height;}
        else if(phase==Phase.RETURN) target=new Point(0,0,targetHeight);
        else if(phase==Phase.DESCEND) target=new Point(0,0,0);
        double dn=target.north-s.north,de=target.east-s.east,distance=Math.hypot(dn,de);
        double limit=(phase==Phase.TAKEOFF||phase==Phase.DESCEND)?2:maxSpeed;
        double vn=dn*0.32,ve=de*0.32,velocity=Math.hypot(vn,ve);
        if(velocity>limit) {vn*=limit/velocity;ve*=limit/velocity;}
        double an=clamp((vn-s.vNorth)*0.65,-1.5,1.5),ae=clamp((ve-s.vEast)*0.65,-1.5,1.5);
        double angle=Math.toRadians(s.heading);
        double forward=an*Math.cos(angle)+ae*Math.sin(angle),right=ae*Math.cos(angle)-an*Math.sin(angle);
        double pitchTarget=-Math.toDegrees(Math.atan2(forward,9.80665));
        double rollTarget=Math.toDegrees(Math.atan2(right,9.80665));
        double pitch=clamp(0.075*(pitchTarget-s.pitch)-0.025*s.q,-0.5,0.5);
        double roll=clamp(0.075*(rollTarget-s.roll)-0.025*s.p,-0.5,0.5);
        double yaw=clamp(0.025*wrap(heading-s.heading)-0.018*s.r,-0.4,0.4);
        double climb=clamp((target.height-s.height)*0.6,-1.0,1.5);
        if(phase==Phase.DESCEND) {
            // Stop descent until aligned; use height above the initial aircraft position for touchdown.
            climb=distance>2.5||Math.hypot(s.vNorth,s.vEast)>0.9?0:-clamp(s.height*0.25,0.2,0.75);
        }
        double error=climb-s.vUp;
        if(!s.ground && lift>0.2) integrator=clamp(integrator+error*dt*0.045,-0.3,0.3);
        double desired=clamp(hoverThrottle+error*0.10+integrator,0.05,0.95);
        // Gradually spin up instead of applying hover power instantly.
        lift+=clamp(desired-lift,-0.35*dt,0.20*dt);
        if(phase==Phase.TAKEOFF) {
            if(time-phaseTime>30 && s.height<2) throw new IllegalStateException("No liftoff; check power and hover throttle");
            if(s.height>13 && Math.abs(s.vUp)<0.7 && distance<3) settledTime+=dt;else settledTime=0;
            if(settledTime>2) transition(Phase.WAYPOINTS,time);
        } else if(phase==Phase.WAYPOINTS) {
            if(distance<3 && Math.abs(s.height-target.height)<2 && Math.hypot(s.vNorth,s.vEast)<1.2)
                settledTime+=dt;else settledTime=0;
            if(settledTime>1.5) {
                if(++index>=route.size()) transition(Phase.RETURN,time);else settledTime=0;
            }
        } else if(phase==Phase.RETURN) {
            if(distance<2 && Math.hypot(s.vNorth,s.vEast)<0.7 && Math.abs(s.height-targetHeight)<2)
                settledTime+=dt;else settledTime=0;
            if(settledTime>2) transition(Phase.DESCEND,time);
        } else if(phase==Phase.DESCEND) {
            if(s.ground && s.height<2 && Math.abs(s.vUp)<0.5) settledTime+=dt;else settledTime=0;
            if(settledTime>1) {transition(Phase.LANDED,time);lift=0;}
        }
        Map<String,Double> terms=new LinkedHashMap<>();
        terms.put("northError",dn);terms.put("eastError",de);terms.put("heightError",target.height-s.height);
        terms.put("targetNorthSpeed",vn);terms.put("targetEastSpeed",ve);terms.put("targetClimb",climb);
        terms.put("climbError",error);terms.put("baseLift",hoverThrottle);terms.put("verticalP",error*0.10);
        terms.put("verticalI",integrator);terms.put("requestedLift",desired);terms.put("appliedLift",lift);
        terms.put("targetPitch",pitchTarget);terms.put("targetRoll",rollTarget);
        terms.put("pitchP",0.075*(pitchTarget-s.pitch));terms.put("pitchD",-0.025*s.q);
        terms.put("rollP",0.075*(rollTarget-s.roll));terms.put("rollD",-0.025*s.p);
        terms.put("headingError",wrap(heading-s.heading));
        terms.put("yawP",0.025*wrap(heading-s.heading));terms.put("yawD",-0.018*s.r);
        terms.put("settledSeconds",settledTime);terms.put("phaseSeconds",phaseSeconds);
        String gate=switch(computedPhase) {
            case TAKEOFF -> "Height > 13 m; |vertical speed| < 0.7 m/s; home error < 3 m, sustained for 2 s.";
            case WAYPOINTS -> "Distance < 3 m; height error < 2 m; ground speed < 1.2 m/s, sustained for 1.5 s.";
            case RETURN -> "Home error < 2 m; ground speed < 0.7 m/s; height error < 2 m, sustained for 2 s.";
            case DESCEND -> "Ground contact; height above start < 2 m; |vertical speed| < 0.5 m/s, sustained for 1 s.";
            default -> "No automatic control step.";
        };
        String transition=phase!=computedPhase?computedPhase+" → "+phase+": stage gate satisfied":
            index!=computedIndex?"Waypoint "+(computedIndex+1)+" reached; advancing to waypoint "+(index+1):"";
        return new Output(pitch,roll,yaw,lift,distance,phase,index,
            new Trace(computedPhase,computedIndex,target,s,terms,gate,transition));
    }
    public static double clamp(double x,double lo,double hi) {return Math.max(lo,Math.min(hi,x));}
    public static double wrap(double degrees) {return ((degrees+180)%360+360)%360-180;}
}
