package alia;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Sidecar provenance for new runs; absent human-entered settings remain explicitly unconfirmed. */
final class ResearchRunMetadata {
    static void write(Path csv,AirportPlan plan) throws IOException {
        Properties scenario=new Properties();Path scenarioFile=Paths.get("research/local/active-scenario.properties");
        if(Files.isRegularFile(scenarioFile))try(Reader reader=Files.newBufferedReader(scenarioFile)){scenario.load(reader);}
        StringBuilder text=new StringBuilder("{\"schema_version\":1,\"run_id\":").append(ExplainabilityModel.quote(csv.getFileName().toString().replace(".csv","")))
            .append(",\"recorded_utc\":").append(ExplainabilityModel.quote(Instant.now().toString()))
            .append(",\"controller_commit\":").append(ExplainabilityModel.quote(System.getProperty("ccat.research.commit","UNKNOWN")))
            .append(",\"working_tree_dirty\":").append(ExplainabilityModel.quote(System.getProperty("ccat.research.dirty","UNKNOWN")))
            .append(",\"airport_source_sha256\":").append(ExplainabilityModel.quote(hash(Paths.get("src/alia/AirportMission.java"))))
            .append(",\"braking_policy\":").append(ExplainabilityModel.quote(plan==null?"hover-test":AirportMission.BRAKING_POLICY_ID))
            .append(",\"java_version\":").append(ExplainabilityModel.quote(System.getProperty("java.version")))
            .append(",\"elapsed_time_source\":\"capped wall/controller clock, not independent simulator time\",\"scenario_settings_are_operator_reported\":true")
            .append(",\"scenario\":{");
        boolean comma=false;
        for(String key:new String[]{"scenario_id","trial_id","xplane_version","aircraft_version","weather_preset","wind_from_true_deg","wind_speed_kt","simulation_speed","departure_id","start_location","battery_initial_percent","surface_visually_checked","pilot_id","intervention_notes"}){
            if(comma)text.append(',');comma=true;text.append(ExplainabilityModel.quote(key)).append(':').append(ExplainabilityModel.quote(scenario.getProperty(key,"UNCONFIRMED")));
        }
        text.append("},\"mission\":");
        if(plan==null)text.append("null");else text.append("{\"airport\":").append(ExplainabilityModel.quote(plan.airport().id())).append(",\"site\":").append(ExplainabilityModel.quote(plan.site().name()))
            .append(",\"latitude\":").append(plan.site().latitude()).append(",\"longitude\":").append(plan.site().longitude())
            .append(",\"cruise_knots\":").append(plan.cruiseKnots()).append(",\"clearance_m\":").append(plan.cruiseClearanceM())
            .append(",\"scenery_source\":").append(ExplainabilityModel.quote(plan.airport().source())).append(",\"scenery_sha256\":").append(ExplainabilityModel.quote(hash(Paths.get(plan.airport().source())))).append('}');
        text.append("}\n");Files.writeString(csv.resolveSibling(csv.getFileName().toString().replace(".csv",".metadata.json")),text);
    }
    private static String hash(Path path) throws IOException {
        if(!Files.isRegularFile(path))return "UNAVAILABLE";
        try(InputStream stream=Files.newInputStream(path)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];int count;
            while((count=stream.read(buffer))!=-1)digest.update(buffer,0,count);return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException ex){throw new IOException(ex);}
    }
}
