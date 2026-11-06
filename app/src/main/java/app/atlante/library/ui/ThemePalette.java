package app.atlante.library.ui;

/** Complete palettes shared by library, forms, book sheets and maps. */
public final class ThemePalette {
    public final String id, name, description;
    public final boolean dark;
    public final int paper, surface, ink, muted, accent, onAccent, line, warm;
    public ThemePalette(String id, String name, String description, boolean dark, int paper, int surface, int ink, int muted, int accent, int onAccent, int line, int warm) {
        this.id=id; this.name=name; this.description=description; this.dark=dark;
        this.paper=paper; this.surface=surface; this.ink=ink; this.muted=muted;
        this.accent=accent; this.onAccent=onAccent; this.line=line; this.warm=warm;
    }
    public static final ThemePalette[] ALL = {
        new ThemePalette("atlante", "Atlante", "Crema, petrolio e rame", false, 0xffF4F1EA,0xffFFFDFA,0xff203637,0xff626C69,0xff326359,0xffFFFFFF,0xffE2E4DB,0xff9B482E),
        new ThemePalette("rose", "Rosa petalo", "Soft · rosa cipria e malva", false, 0xffFFF3F5,0xffFFFCFD,0xff4C2939,0xff775662,0xff99496C,0xffFFFFFF,0xffF0D9E2,0xff99496C),
        new ThemePalette("lavender", "Lavanda", "Soft · lilla e prugna", false, 0xffF5F1FC,0xffFDFCFF,0xff362C50,0xff6F6182,0xff76539C,0xffFFFFFF,0xffE4DCEF,0xff80548B),
        new ThemePalette("sage", "Salvia", "Soft · verde naturale e lino", false, 0xffEFF5EF,0xffFBFEFA,0xff2B4439,0xff5B7065,0xff416D53,0xffFFFFFF,0xffD7E4D9,0xff8B583C),
        new ThemePalette("blue", "Blu polvere", "Sobrio · azzurro e ardesia", false, 0xffEDF3F9,0xffFBFDFF,0xff24394F,0xff5D6E80,0xff365F8C,0xffFFFFFF,0xffD5E1EE,0xff6C538D),
        new ThemePalette("sand", "Sabbia", "Caldo · beige e terracotta", false, 0xffF8F0E5,0xffFFFCF6,0xff4B382A,0xff786451,0xff8A5035,0xffFFFFFF,0xffE8DBCA,0xff8A5035),
        new ThemePalette("graphite", "Grafite", "Scuro · acciaio e blu ghiaccio", true, 0xff171C24,0xff232C38,0xffEDF3FA,0xffB0BFD0,0xffADCFFF,0xff142A43,0xff3A485A,0xffF3BB97),
        new ThemePalette("midnight", "Notte viola", "Scuro · lavanda e indaco", true, 0xff191724,0xff282337,0xffF3ECFF,0xffBFB1D2,0xffD3B6FF,0xff2E164A,0xff493D5B,0xffF4B5CB),
        new ThemePalette("amoled", "AMOLED nero", "Nero puro · contrasto e dettagli rossi", true, 0xff000000,0xff101012,0xffF6F6F8,0xffB7B7C0,0xffFFB4B4,0xff480A14,0xff303036,0xffFFC894)
    };
    public static ThemePalette find(String id) { for (ThemePalette p : ALL) if (p.id.equals(id)) return p; return ALL[0]; }
}
