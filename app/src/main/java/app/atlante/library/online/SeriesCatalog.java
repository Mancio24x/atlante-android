package app.atlante.library.online;

import app.atlante.library.model.Book;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Conservative, reviewable series proposals. Never treats a genre or a shared universe as a reading order. */
public final class SeriesCatalog {
    public static final class Proposal {
        public final String series; public final double number;
        public Proposal(String series,double number){this.series=series;this.number=number;}
    }
    private static final class Entry {
        public final String author,series;final String[] titles;
        public Entry(String author,String series,String...titles){this.author=author;this.series=series;this.titles=titles;}
    }
    private static final Entry[] CATALOG={
        new Entry("sanderson","Mistborn · Era 1","l ultimo impero|the final empire","il pozzo dell ascensione|the well of ascension","il campione delle ere|the hero of ages"),
        new Entry("sanderson","Mistborn · Era 2","la legge delle lande|the alloy of law","le ombre residue|shadows of self","le fasce del lutto|the bands of mourning","il metallo perduto|the lost metal"),
        new Entry("sanderson","Le Cronache della Folgoluce","la via dei re|the way of kings","parole di luce|words of radiance","giuramento|oathbringer","il ritmo della guerra|rhythm of war","vento e verita|wind and truth"),
        new Entry("sanderson","Reckoners","steelheart","firefight","calamity"),
        new Entry("herbert","Dune","dune","il messia di dune|dune messiah","i figli di dune|children of dune","l imperatore dio di dune|god emperor of dune","gli eretici di dune|heretics of dune","la rifondazione di dune|chapterhouse dune"),
        new Entry("pierce brown","Red Rising","red rising|il canto proibito","golden son|il segreto di darrow","morning star|la guerra del mietitore","iron gold","dark age","light bringer"),
        new Entry("bardugo","Alex Stern","la nona casa|ninth house","hell bent|portale per l inferno"),
        new Entry("bardugo","Sei di corvi","sei di corvi|six of crows","il regno corrotto|crooked kingdom"),
        new Entry("rowling","Harry Potter","harry potter e la pietra filosofale|harry potter and the philosopher s stone","harry potter e la camera dei segreti|harry potter and the chamber of secrets","harry potter e il prigioniero di azkaban|harry potter and the prisoner of azkaban","harry potter e il calice di fuoco|harry potter and the goblet of fire","harry potter e l ordine della fenice|harry potter and the order of the phoenix","harry potter e il principe mezzosangue|harry potter and the half blood prince","harry potter e i doni della morte|harry potter and the deathly hallows"),
        new Entry("dinniman","Dungeon Crawler Carl","dungeon crawler carl","il giorno del giudizio|carl s doomsday scenario","il ricettario dell anarchico|the dungeon anarchist s cookbook","il varco degli dei selvaggi|the gate of the feral gods")
    };
    public static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();}
    public static Proposal suggest(Book b){
        String title=normalize(b.title),author=normalize(b.author);
        if(title.matches(".*\\b(omnibus|integrale|completa|trilogia|raccolta)\\b.*"))return null;
        if(author.contains("dinniman")) {
            Matcher d=Pattern.compile("^dungeon crawler carl (?:vol )?(\\d+)\\b").matcher(title);
            if(d.find())return new Proposal("Dungeon Crawler Carl",Double.parseDouble(d.group(1)));
        }
        for(Entry e:CATALOG)if(author.contains(e.author)){
            // Match longer titles first, so Dune does not swallow Il messia di Dune.
            for(int i=e.titles.length-1;i>=0;i--)for(String t:e.titles[i].split("\\|"))
                if(title.equals(t)||title.startsWith(t+" ")||title.endsWith(" "+t))return new Proposal(e.series,i+1);
        }
        // Explicit sequence numbers in metadata, e.g. "Le cronache della Folgoluce 3".
        Matcher m=Pattern.compile("^(.+?)\\s*(?:#|vol\\.?|volume|libro|book)?\\s+(\\d+(?:[.,]\\d+)?)$",Pattern.CASE_INSENSITIVE).matcher(b.series.trim());
        if(m.matches())return new Proposal(m.group(1).trim(),Double.parseDouble(m.group(2).replace(',','.')));
        // Numbered title prefix: "Saga #2", "Saga Vol. 2", "Saga - 2. Titolo".
        m=Pattern.compile("^(.+?)\\s+(?:#|vol\\.?\\s*|volume\\s+|libro\\s+|book\\s+|[-–]\\s*)(\\d+(?:[.,]\\d+)?)(?:\\s|[.:–-]|$)",Pattern.CASE_INSENSITIVE).matcher(b.title);
        if(m.find())return new Proposal(m.group(1).trim(),Double.parseDouble(m.group(2).replace(',','.')));
        return null;
    }
    public static int compareNumber(double a,double b){return Double.compare(a>0?a:Double.MAX_VALUE,b>0?b:Double.MAX_VALUE);}
}
