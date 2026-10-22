package alia;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** Read-only view of the flight window's existing connection; it never sends simulator commands. */
final class TelemetryView {
    private final JFrame frame=new JFrame("CCAT | ALIA live telemetry");
    private final JTextArea values=new JTextArea(37,63);

    TelemetryView() {
        values.setEditable(false);
        values.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
        values.setMargin(new Insets(10,12,10,12));
        frame.add(new JScrollPane(values));
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.pack();
        values.setText("Waiting for telemetry. Load ALIA in X-Plane.\nMonitoring does not engage flight controls.");
    }
    void openBeside(JFrame owner) {
        Rectangle screen=owner.getGraphicsConfiguration().getBounds();
        int x=owner.getX()+owner.getWidth()+8,y=owner.getY();
        if(x+frame.getWidth()>screen.x+screen.width)x=Math.max(screen.x,screen.x+screen.width-frame.getWidth());
        y=Math.max(screen.y,Math.min(y,screen.y+screen.height-frame.getHeight()));
        frame.setLocation(x,y);frame.setVisible(true);frame.toFront();
    }
    void update(String text) {values.setText(text);values.setCaretPosition(0);}
    void close() {frame.dispose();}

    static String format(NativeLink.Snapshot s,String phase,double elapsed,
                         NativeLink.Position home,HoverMission.Output output) {
        StringBuilder b=new StringBuilder("CCAT / ALIA LIVE TELEMETRY\n");
        b.append("Connection: ").append(s.fresh()?"LIVE":"WAITING / STALE").append("\n");
        b.append("Aircraft:   ").append(s.alia()?"ALIA-250 confirmed":"not confirmed").append("\n");
        b.append("Stage:      ").append(phase).append(String.format(Locale.ROOT,"    Elapsed: %.1f s\n",elapsed));
        if(s.pos!=null) {
            NativeLink.Position p=s.pos;
            b.append(String.format(Locale.ROOT,"Position sample age: %.0f ms\n\n",(System.nanoTime()-p.received)/1e6));
            b.append(String.format(Locale.ROOT,
                "Latitude / longitude: %.7f, %.7f deg\n"+
                "Altitude MSL:         %.1f m / %.1f ft\n"+
                "Height above terrain: %.1f m / %.1f ft\n"+
                "Ground speed:         %.2f m/s / %.1f kt\n"+
                "Vertical speed:       %+.2f m/s / %+.0f ft/min\n"+
                "Pitch / roll:         %+.1f / %+.1f deg\n"+
                "Heading:              %.1f deg true\n"+
                "North / east speed:   %+.2f / %+.2f m/s\n",
                p.lat,p.lon,p.elevation,p.elevation*3.28084,p.agl,p.agl*3.28084,
                Math.hypot(p.east,p.south),Math.hypot(p.east,p.south)*1.943844,
                p.up,p.up*196.8504,p.pitch,p.roll,p.heading,-p.south,p.east));
            if(home!=null)b.append(String.format(Locale.ROOT,"Height above start:   %.2f m\n",p.elevation-home.elevation));
        } else b.append("\nPosition: waiting for X-Plane\n");
        b.append("Indicated airspeed:   ").append(number(s,2,"%.1f kt")).append("\n");
        b.append("Paused / on ground:   ").append(flag(s,0,"YES","NO")).append(" / ")
            .append(flag(s,1,"YES","NO")).append("\n\n");
        b.append("MOTOR THROTTLES (actual handle positions)\n");
        for(int i=0;i<5;i++)b.append(String.format(Locale.ROOT,"%-20s %s\n",
            i<4?"Lift motor "+(i+1):"Pusher motor",percent(s,5+i)));
        if(output!=null)b.append(String.format(Locale.ROOT,"Commanded lift:      %.1f%%\n",output.lift*100));
        b.append("\nBATTERY SWITCHES\n");
        for(int i=0;i<5;i++)b.append("Battery ").append(i+1).append(": ")
            .append(flag(s,13+i,"ON","OFF")).append(i==2||i==4?"\n":"   ");
        BatteryStatus battery=BatteryStatus.from(s);
        b.append(String.format(Locale.ROOT,"Remaining charge: %s   Energy: %s\nElectrical power: %s (sum of signed V × I)\n",
            metric(battery.chargePercent(),"%.1f%%"),metric(battery.remainingKwh(),"%.1f kWh"),metric(battery.powerKw(),"%.1f kW")));
        for(int i=0;i<5;i++)b.append(String.format(Locale.ROOT,"Pack %d: %s   %s   %s\n",i+1,metric(battery.packPercent()[i],"%.1f%%"),metric(battery.volts()[i],"%.0f V"),metric(battery.amps()[i],"%+.1f A")));
        b.append("\nJoystick overrides (pitch / roll / yaw):\n");
        for(int i=10;i<=12;i++)b.append(flag(s,i,"ON","OFF")).append(i==12?"\n":" / ");
        if(output!=null)b.append(String.format(Locale.ROOT,"Target distance: %.1f m\n",output.distance));
        b.append("\n").append(readiness(s)).append("\n");
        b.append("Read-only display. Closing this window does not stop flight.\n");
        b.append("Stop with P in X-Plane or Pause & take over in CCAT.");
        return b.toString();
    }
    private static String metric(double value,String format){return Double.isFinite(value)?String.format(Locale.ROOT,format,value):"N/A";}
    private static boolean known(NativeLink.Snapshot s,int index) {
        return s.times[index]!=0&&Double.isFinite(s.refs[index]);
    }
    private static String flag(NativeLink.Snapshot s,int index,String on,String off) {
        return known(s,index)?(s.refs[index]!=0?on:off):"--";
    }
    private static String number(NativeLink.Snapshot s,int index,String format) {
        return known(s,index)?String.format(Locale.ROOT,format,s.refs[index]):"--";
    }
    private static String percent(NativeLink.Snapshot s,int index) {
        return known(s,index)?String.format(Locale.ROOT,"%5.1f%%",s.refs[index]*100):"--";
    }
    static String readiness(NativeLink.Snapshot s) {
        if(!s.fresh())return "Preflight: waiting for complete, fresh ALIA telemetry.";
        if(!s.alia())return "Preflight: load the ALIA-250.";
        for(int i=10;i<=12;i++)if(s.refs[i]!=0)return "Controls engaged: joystick override is ON.";
        for(int i=13;i<=17;i++)if(s.refs[i]!=1)return "Preflight: turn on ALIA's cockpit START/power button.";
        for(int i=5;i<=9;i++)if(s.refs[i]>.05)return "Preflight: hold F1 (lift idle) and F4 (pusher idle) in X-Plane.";
        if(s.refs[0]!=0)return "Preflight: press P in X-Plane to unpause before starting.";
        if(s.refs[1]!=1||Math.hypot(s.pos.east,s.pos.south)>.5||Math.abs(s.pos.up)>.3)
            return "Preflight: start stationary on the ground.";
        if(Math.abs(s.pos.roll)>5||Math.abs(s.pos.pitch)>8)return "Preflight: choose a level takeoff area.";
        return "Preflight readings OK. Review route and clear area before Start.";
    }
}
