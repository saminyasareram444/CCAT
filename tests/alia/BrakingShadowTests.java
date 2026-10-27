package alia;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Numerical parity, invalid-model rejection and observer isolation; no actual simulator is used. */
final class BrakingShadowTests {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static Properties fixture() {
        Properties p=new Properties();p.setProperty("schema_version","1");p.setProperty("policy",BrakingModel.POLICY);p.setProperty("features",BrakingModel.FEATURES);
        p.setProperty("qualification","PILOT_ONLY");p.setProperty("flight_control_authorized","false");p.setProperty("uncertainty_qualified","false");p.setProperty("model_id","0123456789abcdef");
        p.setProperty("means","0,0,0,0,0,0,0,0");p.setProperty("scales","1,1,1,1,1,1,1,1");p.setProperty("minimum","0,0,0,-10,-10,0,0,0");p.setProperty("maximum","100,10000,200,10,20,1,1,200");p.setProperty("padding","0,0,0,0,0,0,0,0");
        p.setProperty("distance_m_coefficients","100,20,0,0,0,0,0,0,0");p.setProperty("time_s_coefficients","10,2,0,0,0,0,0,0,0");return p;
    }
    private static void write(Path path,Properties p) throws IOException{try(Writer writer=Files.newBufferedWriter(path)){p.store(writer,"test fixture");}}
    static int run() throws Exception {
        checks=0;Path folder=Files.createTempDirectory(Paths.get("build/alia"),"braking-shadow-tests-");Path file=folder.resolve("model.properties");write(file,fixture());BrakingModel model=BrakingModel.load(file);
        BrakingModel.Observation observation=new BrakingModel.Observation(20,30,-1,2,.5,0,10);var prediction=model.predict(observation);
        check(prediction.distanceM()==500&&prediction.timeS()==50,"known regression output matches explicit arithmetic");check(prediction.supported(),"supported feature envelope");
        check(!model.predict(new BrakingModel.Observation(101,30,-1,2,.5,0,10)).supported(),"unsupported ground speed is flagged");
        boolean rejected=false;try{model.predict(new BrakingModel.Observation(Double.NaN,30,-1,2,.5,0,10));}catch(IllegalArgumentException ex){rejected=true;}check(rejected,"missing/nonfinite values rejected");
        for(String key:new String[]{"flight_control_authorized","uncertainty_qualified","scales","features","policy"}){
            Properties changed=fixture();changed.setProperty(key,switch(key){case "scales"->"0,1,1,1,1,1,1,1";case "features","policy"->"wrong";default->"true";});write(file,changed);
            rejected=false;try{BrakingModel.load(file);}catch(IOException ex){rejected=true;}check(rejected,"unsafe/incompatible model contract rejected: "+key);
        }
        write(file,fixture());
        Path runLog=folder.resolve("metadata-fixture.csv");ResearchRunMetadata.write(runLog,null);
        String metadata=Files.readString(folder.resolve("metadata-fixture.metadata.json"));
        check(metadata.contains("\"braking_policy\":\"hover-test\"")&&metadata.contains("UNCONFIRMED"),"new run metadata preserves unknown scenario settings explicitly");
        var good=new BrakingShadow.Sample(10,true,true,false,true,"APPROACH",BrakingModel.POLICY,observation);
        check(BrakingShadow.evaluate(model,good,"").status().equals("PILOT_SHADOW"),"supported active approach produces experimental observation");
        check(BrakingShadow.evaluate(model,new BrakingShadow.Sample(10,true,false,false,true,"APPROACH",BrakingModel.POLICY,observation),"").prediction()==null,"stale input has no current prediction");
        check(BrakingShadow.evaluate(model,new BrakingShadow.Sample(10,true,true,true,true,"APPROACH",BrakingModel.POLICY,observation),"").status().equals("PAUSED"),"paused data does not advance prediction");
        check(BrakingShadow.evaluate(model,new BrakingShadow.Sample(10,false,true,false,true,"APPROACH",BrakingModel.POLICY,observation),"").prediction()==null,"retained inactive trace is not interpreted as current flight");
        check(BrakingShadow.evaluate(model,new BrakingShadow.Sample(10,true,true,false,true,"CRUISE",BrakingModel.POLICY,observation),"").prediction()==null,"approach model is not applied to arbitrary cruise braking");
        check(BrakingShadow.evaluate(model,new BrakingShadow.Sample(10,true,true,false,true,"APPROACH","other-policy",observation),"").status().equals("POLICY_MISMATCH"),"changed braking policy is rejected");
        check(BrakingShadow.evaluate(model,new BrakingShadow.Sample(10,true,true,false,true,"APPROACH",BrakingModel.POLICY,null),"").status().equals("INVALID_INPUT"),"missing observation is isolated");
        check(BrakingShadow.evaluate(null,good,"missing model").status().equals("MODEL_UNAVAILABLE"),"missing model stays explicit");
        try(BrakingShadow monitor=new BrakingShadow(file,folder.resolve("journal"))){
            monitor.observe(good);monitor.observe(new BrakingShadow.Sample(11,true,true,false,true,"APPROACH",BrakingModel.POLICY,observation));
            check(monitor.history().size()==2,"current approach history recorded");
            monitor.observe(new BrakingShadow.Sample(0,false,true,false,true,"",BrakingModel.POLICY,null));check(monitor.history().isEmpty(),"new flight clock clears previous history");
            for(int i=0;i<=60;i++)monitor.observe(new BrakingShadow.Sample(i,true,true,false,true,"APPROACH",BrakingModel.POLICY,new BrakingModel.Observation(35-.4*i,40,-1,2,.5,0,i)));
            monitor.render(Paths.get("build/alia/braking-shadow-preview.png"),720,650);
        }
        Path blocked=folder.resolve("not-a-directory");Files.writeString(blocked,"fixture");
        try(BrakingShadow monitor=new BrakingShadow(file,blocked)){
            monitor.observe(good);long deadline=System.nanoTime()+1_000_000_000L;
            while(monitor.recordingError().isEmpty()&&System.nanoTime()<deadline)Thread.sleep(10);
            check(!monitor.recordingError().isEmpty(),"recording error surfaced");check(monitor.current().prediction()!=null,"recording failure does not kill observer or propagate into flight controller");
        }
        Path actual=Paths.get("research/local/models/braking.properties"),parity=Paths.get("research/local/models/braking-parity.csv");
        if(Files.isRegularFile(actual)&&Files.isRegularFile(parity)){
            BrakingModel trained=BrakingModel.load(actual);List<String> rows=Files.readAllLines(parity);
            for(String row:rows.subList(1,rows.size())){
                double[] v=Arrays.stream(row.split(",")).mapToDouble(Double::parseDouble).toArray();
                var result=trained.predict(new BrakingModel.Observation(v[0],v[2],v[3],v[4],v[5],v[6],v[7]));
                check(Math.abs(result.distanceM()-v[8])<1e-7,"Java distance matches Python export");check(Math.abs(result.timeS()-v[9])<1e-7,"Java time matches Python export");
            }
        }
        System.out.println("PASS: "+checks+" braking shadow checks (numerical parity, data validity, policy scope, recording isolation).");return checks;
    }
}
