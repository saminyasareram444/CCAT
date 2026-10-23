package alia;

/** Optional, individually fresh X-Plane weather measurements. Never a destination forecast. */
record PilotWeather(double windFromTrue,double windKnots,double visibilitySm,double temperatureC,double rainRatio) {
    static PilotWeather from(NativeLink.Snapshot s) {
        return new PilotWeather(value(s,0,0,360),value(s,1,0,150)*1.943844,
            value(s,2,0,1000),value(s,3,-100,70),value(s,4,0,1));
    }
    private static double value(NativeLink.Snapshot s,int offset,double min,double max) {
        int i=NativeLink.WEATHER_OFFSET+offset;
        if(s==null||i>=s.refs.length||i>=s.times.length||s.times[i]==0||System.nanoTime()-s.times[i]>2_000_000_000L)return Double.NaN;
        double v=s.refs[i];return Double.isFinite(v)&&v>=min&&v<=max?v:Double.NaN;
    }
    boolean windAvailable(){return Double.isFinite(windKnots)&&Double.isFinite(windFromTrue);}
    boolean complete(){return windAvailable()&&Double.isFinite(visibilitySm)&&Double.isFinite(temperatureC)&&Double.isFinite(rainRatio);}
}
