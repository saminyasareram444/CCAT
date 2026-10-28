package alia;

import java.util.*;

/** Documented simulator instruments. No claim of physical/noise independence. */
final class SimulatorSensors {
    static final int OFFSET=43;
    enum Key {
        PITCH_1("AHRS pilot pitch","sim/cockpit2/gauges/indicators/pitch_AHARS_deg_pilot","deg"),
        PITCH_2("AHRS copilot pitch","sim/cockpit2/gauges/indicators/pitch_AHARS_deg_copilot","deg"),
        ROLL_1("AHRS pilot roll","sim/cockpit2/gauges/indicators/roll_AHARS_deg_pilot","deg"),
        ROLL_2("AHRS copilot roll","sim/cockpit2/gauges/indicators/roll_AHARS_deg_copilot","deg"),
        HEADING_1("AHRS pilot heading","sim/cockpit2/gauges/indicators/heading_AHARS_deg_mag_pilot","deg magnetic"),
        HEADING_2("AHRS copilot heading","sim/cockpit2/gauges/indicators/heading_AHARS_deg_mag_copilot","deg magnetic"),
        RADIO_ALT_1("Radio altimeter pilot","sim/cockpit2/gauges/indicators/radio_altimeter_height_ft_pilot","ft indicated"),
        RADIO_ALT_2("Radio altimeter copilot","sim/cockpit2/gauges/indicators/radio_altimeter_height_ft_copilot","ft indicated"),
        GPS_FAILURE_1("GPS 1 fault code","sim/operation/failures/rel_gps","enum"),
        GPS_FAILURE_2("GPS 2 fault code","sim/operation/failures/rel_gps2","enum"),
        GPS_CDI_1("GPS 1 course deviation","sim/cockpit2/radios/indicators/gps_hdef_dots_pilot","dots"),
        GPS_CDI_2("GPS 2 course deviation","sim/cockpit2/radios/indicators/gps2_hdef_dots_pilot","dots"),
        GPS_DME_VALID("GPS 1 DME signal","sim/cockpit2/radios/indicators/gps_has_dme","flag"),
        GPS_DME("GPS 1 DME distance","sim/cockpit2/radios/indicators/gps_dme_distance_nm","NM"),
        P("Truth roll rate","sim/flightmodel/position/P","deg/s"),
        Q("Truth pitch rate","sim/flightmodel/position/Q","deg/s"),
        R("Truth yaw rate","sim/flightmodel/position/R","deg/s"),
        AX("Truth east acceleration","sim/flightmodel/position/local_ax","m/s2"),
        AY("Truth up acceleration","sim/flightmodel/position/local_ay","m/s2"),
        AZ("Truth south acceleration","sim/flightmodel/position/local_az","m/s2"),
        CLOCK("Simulator clock","sim/time/total_running_time_sec","s"),
        SPEED("Simulation speed","sim/time/sim_speed","x"),
        ACTUAL_SPEED("Actual simulation speed","sim/time/sim_speed_actual","x");
        final String label,ref,unit;
        Key(String label,String ref,String unit){this.label=label;this.ref=ref;this.unit=unit;}
    }
    static double value(NativeLink.Snapshot s,Key key){
        int i=OFFSET+key.ordinal();long now=System.nanoTime();
        return s!=null&&i<s.refs.length&&i<s.times.length&&s.times[i]>0&&now-s.times[i]<1_000_000_000L&&Double.isFinite(s.refs[i])?s.refs[i]:Double.NaN;
    }
    static String formatted(NativeLink.Snapshot s,Key key){double v=value(s,key);return Double.isFinite(v)?String.format(Locale.ROOT,"%.2f %s",v,key.unit):"UNAVAILABLE / STALE";}
    static double attitudeDisagreement(NativeLink.Snapshot s){
        double p1=value(s,Key.PITCH_1),p2=value(s,Key.PITCH_2),r1=value(s,Key.ROLL_1),r2=value(s,Key.ROLL_2);
        return Double.isFinite(p1)&&Double.isFinite(p2)&&Double.isFinite(r1)&&Double.isFinite(r2)?Math.max(Math.abs(p1-p2),Math.abs(HoverMission.wrap(r1-r2))):Double.NaN;
    }
}
