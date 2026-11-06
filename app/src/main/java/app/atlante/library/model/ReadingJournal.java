package app.atlante.library.model;

import org.json.*;
import java.time.*;
import java.util.*;

/** Additive personal data; bibliographic refreshes must never replace this object. */
public final class ReadingJournal {
    public static JSONArray array(Book b, String key) {
        JSONArray a = b.journal.optJSONArray(key);
        return a == null ? new JSONArray() : a;
    }
    public static void put(Book b, String key, Object value) {
        try { b.journal.put(key, value); } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    public static double rating(Book b) { return b.journal.optDouble("rating", b.rating); }
    public static boolean inCollection(Book b, String name) {
        JSONArray a = array(b, "collections");
        for (int i=0;i<a.length();i++) if (name.equals(a.optString(i))) return true;
        return false;
    }
    public static int attempt(Book b) { return Math.max(1, b.journal.optInt("attempt", 1)); }
    public static void session(Book b, int from, int to, long seconds, LocalDate day) {
        try {
            JSONArray a = array(b, "sessions");
            a.put(new JSONObject().put("date",day.toString()).put("at",System.currentTimeMillis())
                .put("from",from).put("to",to).put("pages",Math.max(0,to-from))
                .put("seconds",Math.max(0,seconds)).put("attempt",attempt(b)));
            put(b,"sessions",a);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    /** The stable timer ID also prevents replay if the process stops between saving the book and clearing the timer. */
    public static void sessionOnce(Book b,int from,int to,long seconds,LocalDate day,String timerId) {
        JSONArray sessions=array(b,"sessions");
        for(int i=0;i<sessions.length();i++)if(sessions.optJSONObject(i)!=null&&timerId.equals(sessions.optJSONObject(i).optString("timerId")))return;
        session(b,from,to,seconds,day);
        sessions=array(b,"sessions");
        try { sessions.getJSONObject(sessions.length()-1).put("timerId",timerId); } catch(JSONException e){throw new IllegalArgumentException(e);}
    }
    public static long[] totals(List<Book> books, LocalDate day) {
        long pages=0, seconds=0;
        for (Book b:books) { JSONArray a=array(b,"sessions");
            for(int i=0;i<a.length();i++) { JSONObject s=a.optJSONObject(i); if(s==null) continue;
                if(day==null || day.toString().equals(s.optString("date"))) { pages+=s.optInt("pages"); seconds+=s.optLong("seconds"); }
            }
        }
        return new long[]{pages,seconds};
    }
    public static int streak(List<Book> books, LocalDate today) {
        Set<String> days=new HashSet<>();
        for(Book b:books) { JSONArray a=array(b,"sessions"); for(int i=0;i<a.length();i++) {
            JSONObject s=a.optJSONObject(i); if(s!=null && (s.optInt("pages")>0 || s.optLong("seconds")>0)) days.add(s.optString("date"));
        }}
        LocalDate d=days.contains(today.toString())?today:today.minusDays(1); int count=0;
        while(days.contains(d.toString())) { count++; d=d.minusDays(1); } return count;
    }
    public static int completed(List<Book> books, LocalDate day, boolean monthly) {
        int n=0;
        for(Book b:books) {
            Set<String> finishes=new HashSet<>();
            if(!b.finished.isEmpty()) finishes.add(b.finished);
            JSONArray a=array(b,"attempts"); for(int i=0;i<a.length();i++) { JSONObject j=a.optJSONObject(i); if(j!=null) finishes.add(j.optString("finished")); }
            for(String date:finishes) try { LocalDate d=LocalDate.parse(date); if(d.getYear()==day.getYear() && (!monthly || d.getMonth()==day.getMonth())) n++; } catch(Exception ignored) { }
        } return n;
    }
}
