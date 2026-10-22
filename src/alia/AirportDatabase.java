package alia;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.util.*;

/** Reads enabled X-Plane apt.dat scenery in priority order; caches the small searchable index locally. */
final class AirportDatabase {
    record Site(String name,double latitude,double longitude,double heading) {public String toString(){return name;}}
    record Airport(String id,String name,double latitude,double longitude,double elevationM,String type,List<Site> sites) {
        Airport {sites=List.copyOf(sites);}
        public String toString(){return id+" — "+name;}
    }
    private final List<Airport> airports;
    AirportDatabase(List<Airport> airports){this.airports=List.copyOf(airports);}
    int size(){return airports.size();}
    List<Airport> search(String query,NativeLink.Position origin,int limit) {
        String q=query.strip().toLowerCase(Locale.ROOT);
        return airports.stream().filter(a->q.isEmpty()||a.id().toLowerCase(Locale.ROOT).contains(q)||a.name().toLowerCase(Locale.ROOT).contains(q))
            .sorted(Comparator.<Airport>comparingInt(a->a.id().equalsIgnoreCase(query.strip())?0:1)
                .thenComparingDouble(a->origin==null?0:distance(origin.lat,origin.lon,a.latitude,a.longitude))
                .thenComparing(Airport::id)).limit(limit).toList();
    }
    static AirportDatabase load(Path root) throws IOException {
        List<Path> sources=sources(root);if(sources.isEmpty())throw new IOException("No apt.dat airport database found. Select the X-Plane 12 installation folder.");
        StringBuilder signature=new StringBuilder("CCAT-airports-v2");
        for(Path file:sources)signature.append('|').append(file.toAbsolutePath()).append(':').append(Files.size(file)).append(':').append(Files.getLastModifiedTime(file).toMillis());
        String fingerprint=signature.toString();Path cache=Paths.get("build/alia/airport-index.bin");
        if(Files.isRegularFile(cache))try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(cache)))) {
            if(in.readUTF().equals(fingerprint)) {
                int size=in.readInt();if(size<0||size>100000)throw new IOException("Invalid airport cache");List<Airport> list=new ArrayList<>();
                for(int i=0;i<size;i++) {
                    String id=in.readUTF(),name=in.readUTF(),type=in.readUTF();double lat=in.readDouble(),lon=in.readDouble(),elev=in.readDouble();
                    int count=in.readInt();if(count<0||count>1000)throw new IOException("Invalid landing sites");List<Site> sites=new ArrayList<>();
                    for(int j=0;j<count;j++)sites.add(new Site(in.readUTF(),in.readDouble(),in.readDouble(),in.readDouble()));
                    list.add(new Airport(id,name,lat,lon,elev,type,sites));
                }
                return new AirportDatabase(list);
            }
        }catch(IOException ignored){}
        Map<String,Airport> index=new LinkedHashMap<>();
        for(Path source:sources)try(BufferedReader reader=new BufferedReader(new InputStreamReader(Files.newInputStream(source),
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)))){parse(reader,index);}
        List<Airport> list=new ArrayList<>(index.values());
        try {
            Files.createDirectories(cache.getParent());
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(cache)))) {
                out.writeUTF(fingerprint);out.writeInt(list.size());
                for(Airport a:list) {
                    out.writeUTF(a.id);out.writeUTF(a.name);out.writeUTF(a.type);out.writeDouble(a.latitude);out.writeDouble(a.longitude);out.writeDouble(a.elevationM);out.writeInt(a.sites.size());
                    for(Site s:a.sites){out.writeUTF(s.name);out.writeDouble(s.latitude);out.writeDouble(s.longitude);out.writeDouble(s.heading);}
                }
            }
        }catch(IOException ignored){} // A read-only cache location must not prevent airport selection.
        return new AirportDatabase(list);
    }
    private static List<Path> sources(Path root) throws IOException {
        List<Path> files=new ArrayList<>();Path packs=root.resolve("Custom Scenery/scenery_packs.ini");
        Path global=root.resolve("Global Scenery/Global Airports/Earth nav data/apt.dat");
        if(Files.isRegularFile(packs))for(String line:Files.readAllLines(packs,StandardCharsets.UTF_8)) {
            if(!line.startsWith("SCENERY_PACK "))continue;String path=line.substring(13).strip();
            Path file=path.equals("*GLOBAL_AIRPORTS*")?global:root.resolve(path).resolve("Earth nav data/apt.dat");
            if(Files.isRegularFile(file)&&!files.contains(file))files.add(file);
        }
        if(Files.isRegularFile(global)&&!files.contains(global))files.add(global);return files;
    }
    static void parse(BufferedReader reader,Map<String,Airport> result) throws IOException {
        Builder current=null;String line;
        while((line=reader.readLine())!=null) {
            line=line.stripLeading();
            if(code(line,"1")||code(line,"16")||code(line,"17")) {
                if(current!=null)current.finish(result);String[] p=line.split("\\s+",6);
                current=null;if(p.length==6)try{current=new Builder(p[4],p[5],Double.parseDouble(p[1])*.3048,p[0].equals("17")?"Heliport":p[0].equals("16")?"Seaplane base":"Airport");}catch(NumberFormatException ignored){}
            }else if(current!=null)try {
                if(code(line,"100")) {
                    String[] p=line.split("\\s+");if(p.length>=20) {
                        double lat1=Double.parseDouble(p[9]),lon1=Double.parseDouble(p[10]),lat2=Double.parseDouble(p[18]),lon2=Double.parseDouble(p[19]);
                        if(valid(lat1,lon1)&&valid(lat2,lon2)) {
                            double[] mid=interpolate(lat1,lon1,lat2,lon2,.5);
                            current.sites.add(new Site("Runway "+p[8]+"/"+p[17]+" midpoint",mid[0],mid[1],bearing(lat1,lon1,lat2,lon2)));
                        }
                    }
                }else if(code(line,"102")) {
                    String[] p=line.split("\\s+");if(p.length>=5) {
                        double lat=Double.parseDouble(p[2]),lon=Double.parseDouble(p[3]);
                        if(valid(lat,lon))current.sites.add(new Site("Helipad "+p[1],lat,lon,Double.parseDouble(p[4])));
                    }
                }else if(code(line,"1302")) {
                    String[] p=line.split("\\s+",3);if(p.length==3)switch(p[1]) {
                        case "datum_lat" -> current.lat=Double.parseDouble(p[2]);case "datum_lon" -> current.lon=Double.parseDouble(p[2]);case "icao_code" -> current.icao=p[2];default -> {}
                    }
                }
                else if(line.equals("99")){current.finish(result);current=null;}
            }catch(NumberFormatException ignored){}
        }
        if(current!=null)current.finish(result);
    }
    private static boolean code(String line,String code){return line.startsWith(code)&&line.length()>code.length()&&Character.isWhitespace(line.charAt(code.length()));}
    private static final class Builder {
        String id,name,type,icao="";double elevation,lat=Double.NaN,lon=Double.NaN;List<Site> sites=new ArrayList<>();
        Builder(String id,String name,double elevation,String type){this.id=id;this.name=name;this.elevation=elevation;this.type=type;}
        void finish(Map<String,Airport> result) {
            if(!valid(lat,lon)&&!sites.isEmpty()){lat=sites.get(0).latitude;lon=sites.get(0).longitude;}
            if(!valid(lat,lon))return;String code=icao.isBlank()?id:icao;
            result.putIfAbsent(code,new Airport(code,name,lat,lon,elevation,type,sites));
        }
    }
    static boolean valid(double lat,double lon){return Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180;}
    static double distance(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.pow(Math.sin(Math.toRadians(lat2-lat1)/2),2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.pow(Math.sin(Math.toRadians(lon2-lon1)/2),2);
        return 6371000*2*Math.atan2(Math.sqrt(Math.max(0,a)),Math.sqrt(Math.max(0,1-a)));
    }
    static double bearing(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2),d=Math.toRadians(lon2-lon1);
        return (Math.toDegrees(Math.atan2(Math.sin(d)*Math.cos(b),Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(d)))+360)%360;
    }
    static double[] interpolate(double lat1,double lon1,double lat2,double lon2,double fraction) {
        double a=Math.toRadians(lat1),b=Math.toRadians(lon1),c=Math.toRadians(lat2),d=Math.toRadians(lon2),angle=distance(lat1,lon1,lat2,lon2)/6371000;
        if(angle<1e-10)return new double[]{lat1,lon1};
        double x=Math.sin((1-fraction)*angle)/Math.sin(angle),y=Math.sin(fraction*angle)/Math.sin(angle);
        double xx=x*Math.cos(a)*Math.cos(b)+y*Math.cos(c)*Math.cos(d),yy=x*Math.cos(a)*Math.sin(b)+y*Math.cos(c)*Math.sin(d),zz=x*Math.sin(a)+y*Math.sin(c);
        return new double[]{Math.toDegrees(Math.atan2(zz,Math.hypot(xx,yy))),Math.toDegrees(Math.atan2(yy,xx))};
    }
}
