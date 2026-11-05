package app.atlante.library;

import android.graphics.*;
import android.view.*;
import java.util.*;

/** Author → series → books, using the reference's dotted paper and connecting arrows. */
final class ReadingMap extends View {
    interface OpenBook { void open(Book book); }
    private final MainActivity activity;
    private final String author;
    private final LinkedHashMap<String, ArrayList<Book>> groups = new LinkedHashMap<>();
    private final ArrayList<Hit> hits = new ArrayList<>();
    private final OpenBook open;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bookCount;
    private float downX, downY;
    private final int mapHeight;
    private static final class Hit {
        final RectF rect; final Book book;
        Hit(RectF rect, Book book) { this.rect = rect; this.book = book; }
    }

    ReadingMap(MainActivity activity, String author, ArrayList<Book> books, OpenBook open) {
        super(activity); this.activity = activity; this.author = author; this.open = open; bookCount = books.size();
        books.sort((a, b) -> {
            int c = a.series.compareToIgnoreCase(b.series);
            if (c == 0) c = Double.compare(a.number, b.number);
            if (c == 0) c = a.title.compareToIgnoreCase(b.title);
            return c;
        });
        int height = 28;
        for (Book b : books) groups.computeIfAbsent(b.series.isEmpty() ? "Libri singoli" : b.series, k -> new ArrayList<>()).add(b);
        for (List<Book> group : groups.values()) height += Math.max(150, group.size() * 72) + 24;
        mapHeight = Math.max(340, height);
        setContentDescription("Mappa di " + author + ": " + books.size() + " libri. Le stesse opere sono disponibili nella scheda Autori.");
        setFocusable(true); setClickable(true);
    }
    private int d(float value) { return activity.dp(value); }
    @Override protected void onMeasure(int width, int height) { setMeasuredDimension(d(970), d(mapHeight)); }
    private void box(Canvas c, RectF rect, int fill, int border) {
        paint.setColor(fill); paint.setStyle(Paint.Style.FILL); c.drawRect(rect, paint);
        paint.setColor(border); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(d(1)); c.drawRect(rect, paint);
        paint.setStyle(Paint.Style.FILL);
    }
    private void label(Canvas c, String value, float x, float y, float size, int color, boolean serif) {
        paint.setTypeface(Typeface.create(serif ? "serif" : "sans-serif", Typeface.NORMAL));
        paint.setTextSize(d(size)); paint.setColor(color); c.drawText(value, d(x), d(y), paint);
    }
    private ArrayList<String> wrap(String value, float size, float width) {
        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL)); paint.setTextSize(d(size));
        ArrayList<String> lines = new ArrayList<>(); String line = "";
        for (String word : value.split("\\s+")) {
            if (!line.isEmpty() && paint.measureText(line + " " + word) > d(width)) { lines.add(line); line = word; }
            else line += (line.isEmpty() ? "" : " ") + word;
        }
        if (!line.isEmpty()) lines.add(line); return lines;
    }
    private void arrow(Canvas c, float x1, float y1, float x2, float y2) {
        paint.setColor(0xff8eaf9a); paint.setStrokeWidth(d(1.2f)); paint.setStyle(Paint.Style.STROKE);
        Path p = new Path(); p.moveTo(d(x1), d(y1)); p.cubicTo(d(x1 + 45), d(y1), d(x2 - 45), d(y2), d(x2), d(y2)); c.drawPath(p, paint);
        paint.setStyle(Paint.Style.FILL); p.reset(); p.moveTo(d(x2), d(y2)); p.lineTo(d(x2 - 7), d(y2 - 4)); p.lineTo(d(x2 - 7), d(y2 + 4)); p.close(); c.drawPath(p, paint);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.drawColor(0xfffaf9f3); hits.clear();
        paint.setColor(0xffc5d2bd);
        for (int x = 10; x < 970; x += 22) for (int y = 10; y < mapHeight; y += 22) c.drawCircle(d(x), d(y), d(.8f), paint);
        float center = mapHeight / 2f;
        int groupCount = groups.size(); float top = 28;
        for (Map.Entry<String, ArrayList<Book>> group : groups.entrySet()) {
            float height = Math.max(150, group.getValue().size() * 72), mid = top + height / 2;
            arrow(c, 212, center, 290, mid);
            int index = 0;
            for (Book b : group.getValue()) {
                float y = top + index * 72 + (height - group.getValue().size() * 72) / 2;
                arrow(c, 490, mid, 554, y + 28);
                RectF rect = new RectF(d(554), d(y), d(946), d(y + 56)); box(c, rect, MainActivity.WHITE, MainActivity.LINE);
                label(c, b.number > 0 ? MainActivity.number(b.number) : "·", 568, y + 35, 22, MainActivity.COPPER, true);
                paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(d(15));
                String title = android.text.TextUtils.ellipsize(b.title, new android.text.TextPaint(paint), d(300), android.text.TextUtils.TruncateAt.END).toString();
                label(c, title, 610, y + 34, 15, MainActivity.INK, false);
                label(c, "↗", 925, y + 34, 16, MainActivity.MUTED, false);
                hits.add(new Hit(rect, b)); index++;
            }
            RectF saga = new RectF(d(290), d(mid - 65), d(490), d(mid + 65)); box(c, saga, 0xffe9eee4, 0xffa6bfad);
            label(c, "SAGA / GRUPPO", 308, mid - 39, 10, MainActivity.SAGE, false);
            ArrayList<String> names = wrap(group.getKey(), 21, 164); int line = 0;
            for (String name : names) { if (line == 2) break; label(c, name, 308, mid - 10 + 24 * line++, 21, MainActivity.INK, true); }
            label(c, group.getValue().size() + " titoli", 308, mid + 49, 12, MainActivity.SAGE, false);
            top += height + 24;
        }
        RectF person = new RectF(d(18), d(center - 117), d(212), d(center + 117)); box(c, person, MainActivity.INK, MainActivity.INK);
        paint.setColor(MainActivity.COPPER); c.drawRect(d(18), d(center - 117), d(22), d(center + 117), paint);
        StringBuilder initials = new StringBuilder(); for (String word : author.split("\\s+")) if (!word.isEmpty() && initials.length() < 3) initials.append(word.charAt(0));
        label(c, initials.toString(), 40, center - 62, 38, MainActivity.GOLD, true);
        paint.setColor(0xff466266); c.drawLine(d(40), d(center - 42), d(188), d(center - 42), paint);
        int line = 0; for (String name : wrap(author, 24, 150)) { if (line == 3) break; label(c, name, 40, center - 12 + line++ * 27, 24, MainActivity.WHITE, true); }
        label(c, bookCount + " titoli nella libreria", 40, center + 86, 11, 0xffc2d1cb, false);
        label(c, groupCount + " gruppi", 40, center + 104, 11, 0xffc2d1cb, false);
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); return true; }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            if (Math.abs(e.getX() - downX) < d(10) && Math.abs(e.getY() - downY) < d(10)) {
                performClick();
                for (Hit h : hits) if (h.rect.contains(e.getX(), e.getY())) { open.open(h.book); break; }
            }
            return true;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
