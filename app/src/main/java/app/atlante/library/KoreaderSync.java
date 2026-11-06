package app.atlante.library;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.json.*;

/** KOReader progress receiver. EPUB XPointer positions must never be fabricated from a percentage. */
final class KoreaderSync {
    static final class Progress {
        final Book book; final double percentage;
        Progress(Book book,double percentage){this.book=book;this.percentage=percentage;}
    }
    static String document(String filename) { return SyncCore.md5(filename); }
    static double percentage(JSONObject json) throws IOException {
        double value=json.optDouble("percentage",Double.NaN);
        if(!Double.isFinite(value)||value<0||value>1)throw new IOException("Avanzamento KOReader non valido");
        return value;
    }
    static List<Progress> receive(String server,String user,String key,List<Book> books) throws Exception {
        URI uri=new URI(server);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IOException("Inserisci un indirizzo HTTPS valido per il server KOReader");
        if(user.trim().isEmpty()||key.isEmpty())throw new IOException("Configura prima utente e password KOReader");
        server=server.replaceAll("/+$","");
        Map<String,String> headers=new HashMap<>(); headers.put("x-auth-user",user);headers.put("x-auth-key",key);headers.put("Accept","application/vnd.koreader.v1+json");
        SyncCore.Response auth=SyncCore.http("GET",server+"/users/auth",null,headers,null,null,65536);
        if(auth.status!=200)throw new IOException(auth.status==401?"Utente o password KOReader non corretti":"Server KOReader: HTTP "+auth.status);
        ArrayList<Progress> results=new ArrayList<>();
        for(Book book:books){
            JSONObject link=SyncCore.link(book,"koreader"); if(link==null)continue;
            String id=link.optString("document"); if(!id.matches("[0-9a-f]{32}"))continue;
            SyncCore.Response r=SyncCore.http("GET",server+"/syncs/progress/"+id,null,headers,null,null,65536);
            if(r.status==404)continue;
            if(r.status!=200)throw new IOException("KOReader: HTTP "+r.status+". Nessun avanzamento applicato.");
            JSONObject data=new JSONObject(r.body);
            if(!data.has("percentage"))continue;
            results.add(new Progress(book,percentage(data)));
        }
        return results;
    }
    static void apply(Progress p){
        Book b=p.book;b.percent=(int)Math.round(p.percentage*100);
        if(b.totalPages>0)b.currentPage=(int)Math.round(p.percentage*b.totalPages);
        b.status=p.percentage>=1?Book.READ:p.percentage>0?Book.READING:Book.TO_READ;
        // A rounded 100% is not completion unless the server actually reports 1.0.
    }
}
