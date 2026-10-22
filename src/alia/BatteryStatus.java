package alia;

/** Five independent pack measurements. Optional battery instruments never block core telemetry. */
record BatteryStatus(double chargePercent,double minimumPercent,double remainingKwh,double powerKw,
                     double[] packPercent,double[] volts,double[] amps,boolean chargeAvailable,boolean powerAvailable) {
    static BatteryStatus from(NativeLink.Snapshot s) {
        double[] percent=new double[5],volts=new double[5],amps=new double[5];
        double totalWh=0,min=Double.POSITIVE_INFINITY,power=0;boolean capacity=fresh(s,22)&&s.refs[22]>0,charge=capacity,validPower=true;
        for(int i=0;i<5;i++) {
            boolean validCharge=capacity&&fresh(s,23+i)&&s.refs[23+i]>=0&&s.refs[23+i]<=s.refs[22]*1.05;
            percent[i]=validCharge?Math.min(100,s.refs[23+i]/s.refs[22]*100):Double.NaN;
            if(!validCharge)charge=false;else {totalWh+=s.refs[23+i];min=Math.min(min,percent[i]);}
            volts[i]=fresh(s,28+i)&&s.refs[28+i]>=0?s.refs[28+i]:Double.NaN;
            amps[i]=fresh(s,33+i)?s.refs[33+i]:Double.NaN;
            if(!Double.isFinite(volts[i])||!Double.isFinite(amps[i]))validPower=false;else power+=volts[i]*amps[i];
        }
        return new BatteryStatus(charge?totalWh/(s.refs[22]*5)*100:Double.NaN,charge?min:Double.NaN,
            charge?totalWh/1000:Double.NaN,validPower?power/1000:Double.NaN,percent,volts,amps,charge,validPower);
    }
    private static boolean fresh(NativeLink.Snapshot s,int i) {
        return i<s.refs.length&&s.times[i]!=0&&System.nanoTime()-s.times[i]<1_000_000_000L&&Double.isFinite(s.refs[i]);
    }
}
