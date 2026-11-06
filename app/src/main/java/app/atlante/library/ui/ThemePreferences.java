package app.atlante.library.ui;
import android.content.SharedPreferences;
import android.graphics.Color;
import androidx.core.graphics.ColorUtils;
import org.json.JSONObject;

/** Additive preferences, shared by Compose and existing View screens. */
public final class ThemePreferences {
    public static ThemePalette resolve(SharedPreferences prefs, ThemePalette base, boolean systemDark) {
        return resolve(prefs, base, systemDark, prefs.getString("appearanceMode","theme"));
    }
    /** Same, with the appearance mode given ("light", "dark"…): a widget can be light or dark on its own. */
    public static ThemePalette resolve(SharedPreferences prefs, ThemePalette base, boolean systemDark, String mode) {
        if(mode.equals("theme")&&!base.id.equals("custom"))return base;
        boolean dark=mode.equals("auto")?systemDark:mode.equals("dark")?true:mode.equals("light")?false:base.dark;
        int paper=base.paper,surface=base.surface,accent=base.accent;
        if(base.id.equals("custom")) {
            try { JSONObject j=new JSONObject(prefs.getString("customPalette","{}"));paper=Color.parseColor(j.getString("paper"));surface=Color.parseColor(j.getString("surface"));accent=Color.parseColor(j.getString("accent")); }
            catch(Exception ignored) { }
            if(mode.equals("theme"))dark=ColorUtils.calculateLuminance(paper)<.4;
        }
        if(!mode.equals("theme") && dark!=(ColorUtils.calculateLuminance(paper)<.4)) {
            paper=dark?0xff101014:0xfff6f5f2;surface=dark?0xff1c1c22:0xffffffff;
        }
        int ink=ColorUtils.calculateLuminance(paper)>.4?0xff192126:0xfff6f6f8;
        int onAccent=ColorUtils.calculateContrast(Color.BLACK,accent)>ColorUtils.calculateContrast(Color.WHITE,accent)?Color.BLACK:Color.WHITE;
        return new ThemePalette(base.id,base.name,base.description,dark,paper,surface,ink,ColorUtils.blendARGB(ink,paper,.3f),accent,onAccent,ColorUtils.blendARGB(ink,paper,.82f),base.warm);
    }
}
