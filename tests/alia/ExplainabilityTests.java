package alia;

import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Checks that explanations reproduce actual control terms and never invent unavailable sensor evidence. */
final class ExplainabilityTests {
    private static int count;
    private static void check(boolean condition,String message){count++;if(!condition)throw new AssertionError(message);}
    static int run(NativeLink.Snapshot source) throws Exception {
        var route=List.of(new HoverMission.Point(0,20,15));
        HoverMission controller=new HoverMission(route,0,.6,3);controller.start(0);
        var observed=new HoverMission.State(0,0,10,11.5,2,0,0,0,0,.2,0,0,0,false);
        HoverMission.Output out=controller.update(observed,.05);
        check(out.trace!=null,"active control step has structured evidence");
        check(Math.abs(out.trace.terms.get("targetClimb")-1.5)<1e-9,"trace records climb saturation at actual 1.5 m/s");
        check(Math.abs(out.trace.terms.get("verticalP")-.13)<1e-9,"trace records actual 0.10 × climb error");
        check(Math.abs(out.trace.terms.get("requestedLift")-.73)<1e-9,"trace reproduces requested power");
        check(Math.abs(out.lift-.01)<1e-9&&out.trace.terms.get("appliedLift")==out.lift,"trace distinguishes requested power from slew-limited output");
        check(Math.abs(out.trace.terms.get("pitchP")+.15)<1e-9,"trace records attitude correction sign");
        check(out.trace.gate.contains("13 m")&&out.trace.gate.contains("0.7"),"takeoff explanation uses actual gate thresholds");
        boolean immutable=false;try{out.trace.terms.put("baseLift",1.0);}catch(UnsupportedOperationException e){immutable=true;}
        check(immutable,"published control terms cannot be mutated by the UI");
        double[] refs=source.refs.clone();refs[0]=0;refs[1]=0;
        for(int i=5;i<=8;i++)refs[i]=out.lift;
        refs[9]=0;for(int i=10;i<=12;i++)refs[i]=1;
        NativeLink.Position home=position(0,0,0,0),pos=position(0,10,2,0);
        try(ExplainabilityModel model=new ExplainabilityModel(false)) {
            var idle=model.update(snapshot(pos,refs),"MONITORING",false,0,null,List.of(),null,"Monitoring");
            check(idle.factors().isEmpty()&&Double.isNaN(idle.trackingError()),"monitoring invents no controller contributions or tracking score");
            check(idle.headline().contains("inactive"),"monitoring is clearly separated from active control");
            var active=model.update(snapshot(pos,refs),"TAKEOFF",true,.05,home,route,out,"Automatic flight: TAKEOFF");
            check(active.factors().size()==9,"exact lift, pitch, roll and yaw terms available");
            check(Math.abs(active.factors().get(1).value()-.13)<1e-9,"displayed vertical contribution equals executed term");
            check(active.trackingError()==0,"matching actual motor handles produce zero mismatch");
            check(active.level()==ExplainabilityModel.Level.NOMINAL,"fresh in-limit measurements are nominal");
            pos=position(0,10,2,28);
            var caution=model.update(snapshot(pos,refs),"TAKEOFF",true,.1,home,route,out,"Automatic flight: TAKEOFF");
            check(Math.abs(caution.limitUse()-.8)<1e-6&&Math.abs(caution.limitMargin()-.2)<1e-6,"limit margin is based on 28/35-degree use, not confidence");
            check(caution.level()==ExplainabilityModel.Level.CAUTION,"75-percent configured-limit use threshold");
            int events=caution.events().size();
            var same=model.update(snapshot(pos,refs),"TAKEOFF",true,.15,home,route,out,"Automatic flight: TAKEOFF");
            check(same.events().size()==events,"unchanged conditions do not flood the timeline");
            long[] old=new long[refs.length];Arrays.fill(old,System.nanoTime()-2_000_000_000L);
            var stale=model.update(new NativeLink.Snapshot(pos,refs,old),"TAKEOFF",true,.2,home,route,out,"Automatic flight: TAKEOFF");
            check(stale.level()==ExplainabilityModel.Level.WAITING&&stale.factors().isEmpty(),"stale telemetry suspends live explanations");
            refs[0]=1;
            var paused=model.update(snapshot(pos,refs),"TAKEOFF",true,.2,home,route,out,"X-Plane paused");
            check(paused.level()==ExplainabilityModel.Level.PAUSED&&paused.headline().contains("paused"),"paused state does not claim the controller is advancing");
            refs[0]=0;refs[13]=0;
            var power=model.update(snapshot(pos,refs),"TAKEOFF",true,.3,home,route,out,"Automatic flight: TAKEOFF");
            check(power.level()==ExplainabilityModel.Level.CRITICAL,"battery-off during active flight is critical");
            model.clearEvents();
            var cleared=model.update(snapshot(pos,refs),"TAKEOFF",true,.3,home,route,out,"Automatic flight: TAKEOFF");
            check(cleared.events().size()==1&&cleared.events().get(0).title().equals("History cleared"),"clearing dashboard history has no controller side effect");
        }
        try(ExplainabilityModel model=new ExplainabilityModel(false)) {
            refs[13]=1;pos=position(0,10,0,0);
            model.update(snapshot(pos,refs),"MONITORING",false,0,null,List.of(),null,"Monitoring");
            Thread.sleep(20);
            var moved=model.update(snapshot(position(1,10,0,0),refs),"MONITORING",false,0,null,List.of(),null,"Monitoring");
            check(Math.abs(moved.residual()-1)<.001,"position/velocity residual uses metres, not a sensor confidence score");
        }
        var none=new NativeLink.Snapshot(null,new double[refs.length],new long[refs.length]);
        try(ExplainabilityModel model=new ExplainabilityModel(false)) {
            var missing=model.update(none,"MONITORING",false,0,null,List.of(),null,"Waiting");
            check(Double.isNaN(missing.limitMargin())&&missing.samples().isEmpty(),"missing input has no fabricated margin or chart data");
        }
        check(ExplainabilityModel.number(Double.NaN).equals("null"),"unavailable JSON values use null");
        check(ExplainabilityModel.quote("a\"b\n").equals("\"a\\\"b\\n\""),"journal escapes quotes and newlines");
        check(ExplainabilityModel.quote("a\u0001").contains("\\u0001"),"journal escapes all JSON control characters");
        Path journalFolder=Files.createTempDirectory(Paths.get("build/alia"),"xai-test-");
        try(ExplainabilityModel model=new ExplainabilityModel(true,journalFolder)) {
            model.update(snapshot(position(0,10,0,0),refs),"TAKEOFF",true,.05,home,route,out,"Test fixture");
            model.clearEvents();
        }
        try(var files=Files.list(journalFolder)) {
            String journal=Files.readString(files.findFirst().orElseThrow());
            check(journal.contains("\"type\":\"session\"")&&journal.contains("\"type\":\"event\"")&&journal.contains("\"type\":\"sample\""),"journal saves provenance, history and samples");
            check(journal.contains("\"terms\"")&&journal.contains("\"aircraft_values\"")&&!journal.contains(":NaN"),"journal stores actual control terms and measured values with valid unavailable encoding");
        }
        System.out.println("PASS: "+count+" explainability checks (executed terms, stale data, margins, history, missing sensors).");
        return count;
    }
    private static NativeLink.Snapshot snapshot(NativeLink.Position p,double[] values) {
        long[] times=new long[values.length];Arrays.fill(times,System.nanoTime());return new NativeLink.Snapshot(p,values.clone(),times);
    }
    private static NativeLink.Position position(double east,double height,double pitch,double roll) {
        ByteBuffer b=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.putDouble(-115.2+Math.toDegrees(east/(6371000*Math.cos(Math.toRadians(36.1))))).putDouble(36.1).putDouble(600+height);
        for(double value:new double[]{height+1.5,pitch,0,roll,0,0,0,0,0,0})b.putFloat((float)value);
        b.flip();return new NativeLink.Position(b);
    }
}
