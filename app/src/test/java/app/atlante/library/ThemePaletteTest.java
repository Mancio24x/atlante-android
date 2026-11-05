package app.atlante.library;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashSet;

public class ThemePaletteTest {
    private double luminance(int color) {
        double total=0; double[] weights={.2126,.7152,.0722}; int[] shifts={16,8,0};
        for(int i=0;i<3;i++) { double channel=((color >> shifts[i]) & 255)/255.0; total+=weights[i]*(channel<=.04045 ? channel/12.92 : Math.pow((channel+.055)/1.055,2.4)); } return total;
    }
    private double contrast(int a,int b) { double x=luminance(a),y=luminance(b); return (Math.max(x,y)+.05)/(Math.min(x,y)+.05); }
    @Test public void textAndActionLabelsRemainReadableInEveryTheme() {
        for(ThemePalette p:ThemePalette.ALL) {
            assertTrue(p.id+" text",contrast(p.ink,p.paper)>=4.5);
            assertTrue(p.id+" secondary",contrast(p.muted,p.surface)>=4.5);
            assertTrue(p.id+" action",contrast(p.onAccent,p.accent)>=4.5);
            assertTrue(p.id+" accent text",contrast(p.accent,p.paper)>=4.5);
        }
    }
    @Test public void stableIdsAndAmoledBlackSurvivePreferenceLookup() {
        HashSet<String> ids=new HashSet<>();
        for(ThemePalette p:ThemePalette.ALL) { assertTrue(ids.add(p.id)); assertSame(p,ThemePalette.find(p.id)); }
        assertEquals(0xff000000,ThemePalette.find("amoled").paper);
        assertSame(ThemePalette.ALL[0],ThemePalette.find("unknown"));
    }
}
