package alia;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Visible-viewport-only OSM client. Disk/network work never runs on Swing/flight threads. */
final class MapTiles {
    static final String ATTRIBUTION=System.getProperty("ccat.map.attribution","© OpenStreetMap contributors");
    static final String ATTRIBUTION_URL=System.getProperty("ccat.map.attributionUrl","https://www.openstreetmap.org/copyright");
    private static final String TEMPLATE=System.getProperty("ccat.map.tileUrl","https://tile.openstreetmap.org/{z}/{x}/{y}.png");
    static final MapTiles INSTANCE=new MapTiles();
    record Tile(int z,int x,int y){String id(){return z+"/"+x+"/"+y;}}
    static final class Entry {volatile BufferedImage image,dark;volatile long expiry,nextAttempt;volatile boolean loading,checked;}
    private final Map<Tile,Entry> memory=new LinkedHashMap<>(32,.75f,true);
    private final Set<JComponent> views=Collections.newSetFromMap(new WeakHashMap<>());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),r->{Thread t=new Thread(r,"ccat-basemap");t.setDaemon(true);return t;});
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Path root=Path.of("research/local/map-cache",Integer.toUnsignedString(TEMPLATE.hashCode(),16));
    private volatile long providerRetry;
    void watch(JComponent view){synchronized(views){views.add(view);}}
    synchronized Entry get(Tile tile,boolean visible){
        Entry e=memory.computeIfAbsent(tile,k->new Entry());long now=System.currentTimeMillis();
        boolean network=visible&&!Boolean.getBoolean("ccat.map.offline");
        if(!e.loading&&((!e.checked&&now>=e.nextAttempt)||(network&&now>=e.expiry&&now>=e.nextAttempt&&now>=providerRetry))){
            e.loading=true;try{worker.execute(()->load(tile,e,network));}catch(RejectedExecutionException full){e.loading=false;e.nextAttempt=now+1000;}
        }
        while(memory.size()>128){var it=memory.entrySet().iterator();boolean removed=false;while(it.hasNext()){var candidate=it.next();if(!candidate.getValue().loading){it.remove();removed=true;break;}}if(!removed)break;}
        return e;
    }
    private void load(Tile tile,Entry e,boolean network){
        Path png=root.resolve(tile.id()+".png"),meta=root.resolve(tile.id()+".properties");Properties p=new Properties();
        try{
            if(!e.checked){
                if(Files.isRegularFile(meta))try(var in=Files.newInputStream(meta)){p.load(in);}
                if(Files.isRegularFile(png)){e.image=ImageIO.read(png.toFile());if(e.image!=null)e.dark=dark(e.image);}
                e.expiry=Long.parseLong(p.getProperty("expires",e.image==null?"0":""+(Files.getLastModifiedTime(png).toMillis()+7L*86400000)));e.checked=true;
            }else if(Files.isRegularFile(meta))try(var in=Files.newInputStream(meta)){p.load(in);}
            if(!network||System.currentTimeMillis()<e.expiry)return;
            String url=TEMPLATE.replace("{z}",""+tile.z()).replace("{x}",""+tile.x()).replace("{y}",""+tile.y());URI uri=URI.create(url);
            if(!uri.getScheme().equals("https"))throw new IOException("Basemap requires HTTPS");
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(7)).header("User-Agent","CCAT-Soar-Research/1.0 (+https://github.com/saminyasareram444/CCAT)");
            if(p.containsKey("etag"))request.header("If-None-Match",p.getProperty("etag"));
            if(p.containsKey("modified"))request.header("If-Modified-Since",p.getProperty("modified"));
            var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream body=response.body()){
                if(response.statusCode()==429||response.statusCode()==503){providerRetry=System.currentTimeMillis()+retryDelay(response.headers());throw new IOException("Basemap temporarily rate limited");}
                if(response.statusCode()!=200&&response.statusCode()!=304)throw new IOException("Map HTTP "+response.statusCode());
                e.expiry=expiry(response.headers(),System.currentTimeMillis());
                if(response.statusCode()==200){byte[] bytes=body.readNBytes(1_000_001);if(bytes.length>1_000_000)throw new IOException("Oversized tile");BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));if(image==null||image.getWidth()!=256||image.getHeight()!=256)throw new IOException("Invalid map tile");e.image=image;e.dark=dark(image);
                    if(!response.headers().firstValue("Cache-Control").orElse("").contains("no-store")){Files.createDirectories(png.getParent());Path temp=png.resolveSibling(png.getFileName()+".tmp");Files.write(temp,bytes);Files.move(temp,png,StandardCopyOption.REPLACE_EXISTING);}
                }else if(e.image==null)throw new IOException("304 without cached image");
                e.expiry=expiry(response.headers(),System.currentTimeMillis());p.setProperty("expires",""+e.expiry);response.headers().firstValue("ETag").ifPresent(v->p.setProperty("etag",v));response.headers().firstValue("Last-Modified").ifPresent(v->p.setProperty("modified",v));
                if(Files.isRegularFile(png)){Files.createDirectories(meta.getParent());try(var out=Files.newOutputStream(meta)){p.store(out,"Visible-viewport basemap HTTP cache");}}
            }
        }catch(Exception ex){e.checked=true;e.nextAttempt=System.currentTimeMillis()+60_000;}
        finally{e.loading=false;SwingUtilities.invokeLater(()->{synchronized(views){for(var v:views)if(v!=null)v.repaint();}});}
    }
    static long expiry(HttpHeaders headers,long now){
        String control=headers.firstValue("Cache-Control").orElse("");Matcher m=Pattern.compile("(?:^|[, ])max-age=(\\d+)").matcher(control);
        if(m.find())try{return now+Math.max(0,Long.parseLong(m.group(1))-Long.parseLong(headers.firstValue("Age").orElse("0")))*1000;}catch(NumberFormatException ignored){}
        try{return ZonedDateTime.parse(headers.firstValue("Expires").orElse(""),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception ignored){return now+7L*24*3600*1000;}
    }
    private static long retryDelay(HttpHeaders headers){try{return Math.max(60,Long.parseLong(headers.firstValue("Retry-After").orElse("60")))*1000;}catch(Exception ignored){return 60_000;}}
    static BufferedImage dark(BufferedImage source){
        BufferedImage result=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++){int rgb=source.getRGB(x,y),r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;double l=.2126*r+.7152*g+.0722*b;int base=(int)(185-l*.59);boolean water=b>r+8&&b>g-10;int rr=Math.max(20,base-(water?7:0)),gg=Math.max(20,base),bb=Math.max(22,base+(water?10:2));result.setRGB(x,y,(rr<<16)|(gg<<8)|bb);}
        return result;
    }
}
