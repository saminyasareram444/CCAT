package alia;

import java.awt.geom.Point2D;
import java.util.List;

/** Web Mercator camera shared by the raster layer and every aircraft overlay. */
final class MapProjection {
    static final double RADIUS=6371000,MAX_LAT=85.05112878,CIRCUMFERENCE=40075016.686;
    record Geo(double lat,double lon){boolean valid(){return Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=MAX_LAT;}}
    record Camera(double x,double y,int zoom,int width,int height){
        double world(){return 256.0*Math.scalb(1,zoom);}
        Point2D.Double point(Geo p){return new Point2D.Double(width/2.0+wrap(MapProjection.x(p.lon())-x)*world(),height/2.0+(MapProjection.y(p.lat())-y)*world());}
        Camera pan(double dx,double dy){return new Camera(x-dx/world(),y-dy/world(),zoom,width,height);}
        double metresPerPixel(){return Math.cos(Math.toRadians(latitude(y)))*CIRCUMFERENCE/world();}
    }
    static double x(double lon){return (lon+180)/360;}
    static double y(double lat){double s=Math.sin(Math.toRadians(Math.max(-MAX_LAT,Math.min(MAX_LAT,lat))));return .5-Math.log((1+s)/(1-s))/(4*Math.PI);}
    static double latitude(double y){return Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*y))));}
    static double longitude(double x){return ((x*360)%360+360)%360-180;}
    static double wrap(double x){return x-Math.floor(x+.5);}
    static Geo local(Geo origin,double north,double east){return new Geo(origin.lat()+Math.toDegrees(north/RADIUS),origin.lon()+Math.toDegrees(east/(RADIUS*Math.cos(Math.toRadians(origin.lat())))));}
    static Camera fit(List<Geo> points,Geo anchor,int width,int height,int zoomOffset,double panX,double panY){
        double ax=x(anchor.lon()),minX=ax,maxX=ax,minY=y(anchor.lat()),maxY=minY;
        for(Geo p:points)if(p.valid()){double px=ax+wrap(x(p.lon())-ax),py=y(p.lat());minX=Math.min(minX,px);maxX=Math.max(maxX,px);minY=Math.min(minY,py);maxY=Math.max(maxY,py);}
        double minimumSpan=500/(CIRCUMFERENCE*Math.max(.05,Math.cos(Math.toRadians(anchor.lat()))));
        double scale=Math.min(Math.max(40,width-84)/(256*Math.max(minimumSpan,maxX-minX)),Math.max(40,height-110)/(256*Math.max(minimumSpan,maxY-minY)));
        int zoom=Math.max(2,Math.min(18,(int)Math.floor(Math.log(scale)/Math.log(2))+zoomOffset));
        return new Camera((minX+maxX)/2+panX,Math.max(0,Math.min(1,(minY+maxY)/2+panY)),zoom,width,height);
    }
}
