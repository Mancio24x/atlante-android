package app.atlante.library;

import app.atlante.library.model.Book;
import app.atlante.library.sync.KoreaderSync;
import app.atlante.library.online.OnlineCatalog;
import app.atlante.library.online.SeriesCatalog;
import app.atlante.library.sync.SyncCore;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class EnhancementsTest {
 private Book book(String title,String author){Book b=new Book();b.title=title;b.author=author;return b;}
 @Test public void seriesHandlesTranslatedTitlesAndEra(){
  Book b=book("Mistborn Era Due - 4. Il metallo perduto","Brandon Sanderson");
  assertEquals("Mistborn · Era 2",SeriesCatalog.suggest(b).series);assertEquals(4,SeriesCatalog.suggest(b).number,0);
  b=book("Dungeon Crawler Carl Vol. 2 Il giorno del giudizio","Matt Dinniman");assertEquals(2,SeriesCatalog.suggest(b).number,0);
  b=book("Dungeon Crawler Carl 4. Il varco degli dèi selvaggi","Matt Dinniman");assertEquals(4,SeriesCatalog.suggest(b).number,0);
 }
 @Test public void noInventedSagaForOmnibusOrWrongAuthor(){
  assertNull(SeriesCatalog.suggest(book("Harry Potter - La saga completa","J.K. Rowling")));
  assertNull(SeriesCatalog.suggest(book("Giuramento","Un altro autore")));
  assertTrue(SeriesCatalog.compareNumber(0,1)>0);
 }
 @Test public void cleansNumberInMetadata(){Book b=book("Qualunque titolo","Autore");b.series="Una saga 3";assertEquals("Una saga",SeriesCatalog.suggest(b).series);assertEquals(3,SeriesCatalog.suggest(b).number,0);}
 @Test public void koreaderKeepsPercentAndDoesNotFinishRounded100() throws Exception {
  Book b=book("Libro","Autore");b.totalPages=300;
  KoreaderSync.apply(new KoreaderSync.Progress(b,.5));assertEquals(150,b.currentPage);assertEquals(50,b.percent);assertEquals(Book.READING,b.status);
  KoreaderSync.apply(new KoreaderSync.Progress(b,.999));assertEquals(Book.READING,b.status);
  assertEquals(SyncCore.md5("libro.epub"),KoreaderSync.document("libro.epub"));
 }
 @Test(expected=java.io.IOException.class) public void koreaderRejectsInvalidPercent() throws Exception {KoreaderSync.percentage(new JSONObject().put("percentage",1.5));}
}
