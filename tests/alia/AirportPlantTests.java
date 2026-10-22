package alia;

import java.util.*;
import static alia.HoverMission.*;

/** A deliberately coarse independent lift/drag plant, not an ALIA/X-Plane aircraft model. */
final class AirportPlantTests {
    static int run(NativeLink.Position origin) {
        return scenario(origin,"nominal",.05,150,90,12,.003)+
            scenario(origin,"weak glide drag",.15,300,90,2,.0005)+
            scenario(origin,"fast cruise / weak glide drag",.2,300,110,2,.0005);
    }
    private static int scenario(NativeLink.Position origin,String label,double longitudeOffset,double clearance,double cruise,double thrust,double drag) {
        var site=new AirportDatabase.Site("Synthetic landing point",origin.lat,origin.lon+longitudeOffset,90,35,35,1);
        var airport=new AirportDatabase.Airport("PLANT","Offline flat-field fixture",site.latitude(),site.longitude(),origin.elevation-origin.agl,"Airport",List.of(site));
        AirportPlan plan=new AirportPlan(airport,site,clearance,cruise);AirportMission controller=new AirportMission(plan,origin,.6);controller.start(0);
        double n=0,e=0,h=0,vn=0,ve=0,vu=0,pitch=0,roll=0,heading=plan.course(origin),p=0,q=0,r=0,lift=0,pusher=0;
        double dt=.05,maxOvershoot=0,approachN=0,approachE=0,captureDistance=0;Set<Phase> phases=EnumSet.noneOf(Phase.class);
        for(int i=1;i<=40000;i++) {
            double groundSpeed=Math.hypot(vn,ve),ias=groundSpeed*1.943844;
            // Exercise signed near-hover airspeed throughout liftoff and touchdown.
            // Keep the forward-flight measurement and plant dynamics unchanged.
            if(groundSpeed<.5)ias-=.20687556266784668;
            var state=new State(n,e,h,h+origin.agl,pitch,roll,heading,vn,ve,vu,p,q,r,h<=.001,ias);
            Output out;
            try{out=controller.update(state,i*dt);}catch(IllegalStateException ex){throw new AssertionError(String.format(Locale.ROOT,
                "Coarse airport plant failed at %.1fs: %s, h=%.1f, n/e=%.1f/%.1f, speed=%.1fkt, attitude=%.1f/%.1f",i*dt,ex.getMessage(),h,n,e,ias,pitch,roll),ex);}
            Point target=controller.route().get(controller.route().size()-1);
            double remaining=Math.hypot(target.north-n,target.east-e);
            if(out.phase==Phase.APPROACH&&!phases.contains(Phase.APPROACH)){approachN=(target.north-n)/remaining;approachE=(target.east-e)/remaining;}
            if(out.phase==Phase.HOVER_CAPTURE&&!phases.contains(Phase.HOVER_CAPTURE))captureDistance=remaining;
            if(out.trace!=null&&out.trace.computedPhase==Phase.APPROACH)maxOvershoot=Math.max(maxOvershoot,-((target.north-n)*approachN+(target.east-e)*approachE));
            if(phases.contains(Phase.APPROACH)&&remaining<50&&groundSpeed>10)throw new AssertionError(label+": entered the landing zone too fast: "+groundSpeed);
            phases.add(out.phase);
            if(out.phase==Phase.LANDED) {
                double error=Math.hypot(n-target.north,e-target.east);
                if(error>3)throw new AssertionError("Coarse plant touchdown missed destination");
                if(!phases.containsAll(EnumSet.of(Phase.TAKEOFF,Phase.TRANSITION,Phase.CRUISE,Phase.APPROACH,Phase.HOVER_CAPTURE,Phase.DESCEND,Phase.LANDED)))throw new AssertionError("A transition phase was skipped");
                if(maxOvershoot>25)throw new AssertionError(label+": forward-flight landing overshoot "+maxOvershoot+" m");
                System.out.printf(Locale.ROOT,"Coarse plant [%s]: landed %.1fs, error %.2fm, braking overshoot %.1fm, rotor capture %.0fm from target. Not X-Plane validation.%n",label,i*dt,error,maxOvershoot,captureDistance);return 4;
            }
            lift+=(out.lift-lift)*dt/.25;pusher+=(out.pusher-pusher)*dt/.5;
            p+=(65*out.roll-2.5*p)*dt;q+=(65*out.pitch-2.5*q)*dt;r+=(50*out.yaw-2*r)*dt;
            pitch+=q*dt;roll+=p*dt;
            double bankTurn=groundSpeed>15?Math.toDegrees(9.80665*Math.tan(Math.toRadians(roll))/groundSpeed):0;
            heading+=(r+bankTurn)*dt;
            double theta=Math.toRadians(pitch),phi=Math.toRadians(roll),psi=Math.toRadians(heading),gamma=Math.atan2(vu,Math.max(2,groundSpeed));
            double alpha=pitch-Math.toDegrees(gamma),cl=clamp(.25+.12*alpha,-.5,1.8);
            double wingLift=9.80665*Math.pow(groundSpeed/35,2)*cl;
            double rotorThrust=9.80665*Math.pow(lift/.6,2),forward=thrust*pusher-drag*groundSpeed*groundSpeed-Math.sin(theta)*rotorThrust-wingLift*Math.sin(gamma);
            double side=Math.sin(phi)*rotorThrust,bodySide=ve*Math.cos(psi)-vn*Math.sin(psi);
            side-=.25*bodySide;
            vn+=(forward*Math.cos(psi)-side*Math.sin(psi))*dt;
            ve+=(forward*Math.sin(psi)+side*Math.cos(psi))*dt;
            vu+=(rotorThrust*Math.cos(theta)*Math.cos(phi)+wingLift*Math.cos(gamma)*Math.cos(phi)-9.80665-.2*vu)*dt;
            n+=vn*dt;e+=ve*dt;h+=vu*dt;
            if(h<0){h=0;vu=Math.max(0,vu);vn*=.9;ve*=.9;}
        }
        throw new AssertionError("Coarse airport plant did not finish");
    }
}
