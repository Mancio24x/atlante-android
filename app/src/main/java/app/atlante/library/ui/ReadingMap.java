package app.atlante.library.ui;

import app.atlante.library.model.Book;
import app.atlante.library.MainActivity;

import android.graphics.*;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.*;
import java.util.*;

/**
 * Vertical reading map for phones: author at the top, then each series with its books
 * in reading order, connected by a spine. Fills the screen width and scrolls vertically.
 */
public final class ReadingMap extends View {
    public interface OpenBook { void open(Book book); }
    private final MainActivity activity;
    private final String author;
    private final List<MapGroups.Group> groups;
    private final Map<String, MapGroups.Known> known;
    private final ArrayList<Hit> hits = new ArrayList<>();
    private final OpenBook open;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bookCount;
    private final int mapHeight;
    private float downX, downY;

    private static final int PAD = 16, AUTHOR_H = 112, GROUP_GAP = 26, SAGA_H = 64, BOOK_H = 56, BOOK_GAP = 8;
    private static final int SPINE = 36, SAGA_X = 56, BOOK_SPINE = 76, BOOK_X = 96;

    private static final class Hit {
        public final RectF rect; public final Book book;
        public Hit(RectF rect, Book book) { this.rect = rect; this.book = book; }
    }

    /** {@code known}: sagas recognized by the saga engine, by book id (see {@link MapGroups}). */
    public ReadingMap(MainActivity activity, String author, ArrayList<Book> books, Map<String, MapGroups.Known> known, OpenBook open) {
        super(activity); this.activity = activity; this.author = author; this.open = open; bookCount = books.size(); this.known = known;
        groups = MapGroups.group(books, known);
        ArrayList<List<Book>> sizes = new ArrayList<>(); for (MapGroups.Group g : groups) sizes.add(g.books);
        mapHeight = height(sizes);
        setContentDescription("Mappa di " + author + ": " + books.size() + " libri in " + groups.size() + " gruppi. Tocca un libro per aprire la scheda.");
        setFocusable(true); setClickable(true);
    }

    /** Height in dp: depends only on how many groups and books there are, not on the screen width. */
    public static int height(Collection<? extends List<Book>> groups) {
        int y = PAD + AUTHOR_H;
        for (List<Book> group : groups) y += GROUP_GAP + SAGA_H + BOOK_GAP + group.size() * (BOOK_H + BOOK_GAP);
        return y + PAD;
    }

    private float d(float value) { return activity.dp(value); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(d(mapHeight)));
    }

    private void box(Canvas c, RectF rect, int fill, int border) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(fill); c.drawRoundRect(rect, d(16), d(16), paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(d(1)); paint.setColor(border); c.drawRoundRect(rect, d(16), d(16), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Draws text at dp coordinates, shortened with an ellipsis to fit {@code maxWidth} pixels. */
    private void label(Canvas c, String value, float x, float y, float size, int color, boolean serif, float maxWidth) {
        paint.setTypeface(Typeface.create(serif ? "serif" : "sans-serif", Typeface.NORMAL));
        paint.setTextSize(d(size)); paint.setColor(color);
        String text = maxWidth > 0 ? TextUtils.ellipsize(value, new TextPaint(paint), maxWidth, TextUtils.TruncateAt.END).toString() : value;
        c.drawText(text, d(x), d(y), paint);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        paint.setColor(activity.SAGE); paint.setStrokeWidth(d(1.4f)); c.drawLine(d(x1), d(y1), d(x2), d(y2), paint);
    }

    private void branch(Canvas c, float x1, float x2, float y) {
        line(c, x1, y, x2 - 2, y);
        paint.setStyle(Paint.Style.FILL); Path p = new Path();
        p.moveTo(d(x2), d(y)); p.lineTo(d(x2 - 7), d(y - 4)); p.lineTo(d(x2 - 7), d(y + 4)); p.close(); c.drawPath(p, paint);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.drawColor(activity.PAPER); hits.clear();
        float width = getWidth() / getResources().getDisplayMetrics().density, right = width - PAD;
        paint.setColor(activity.LINE);
        for (float x = 10; x < width; x += 22) for (float y = 10; y < mapHeight; y += 22) c.drawCircle(d(x), d(y), d(.8f), paint);

        // Author card.
        RectF person = new RectF(d(PAD), d(PAD), d(right), d(PAD + AUTHOR_H)); box(c, person, activity.SAGE, activity.SAGE);
        paint.setColor(activity.COPPER); c.drawRect(d(PAD), d(PAD + 14), d(PAD + 4), d(PAD + AUTHOR_H - 14), paint);
        StringBuilder initials = new StringBuilder();
        for (String word : author.split("\\s+")) if (!word.isEmpty() && initials.length() < 3) initials.append(word.charAt(0));
        label(c, initials.toString(), PAD + 20, PAD + 66, 38, activity.GOLD, true, 0);
        float textX = PAD + 96, textWidth = d(right - textX - 12);
        label(c, "AUTORE", textX, PAD + 30, 10, activity.ON_ACCENT, false, textWidth);
        label(c, author, textX, PAD + 60, 23, activity.ON_ACCENT, true, textWidth);
        label(c, bookCount + " titoli · " + groups.size() + (groups.size() == 1 ? " gruppo" : " gruppi"), textX, PAD + 88, 12, activity.ON_ACCENT, false, textWidth);

        float y = PAD + AUTHOR_H, lastSagaMid = y;
        for (MapGroups.Group group : groups) {
            y += GROUP_GAP;
            float sagaMid = y + SAGA_H / 2f; lastSagaMid = sagaMid;
            branch(c, SPINE, SAGA_X, sagaMid);
            RectF saga = new RectF(d(SAGA_X), d(y), d(right), d(y + SAGA_H)); box(c, saga, activity.WHITE, activity.LINE);
            String count = group.books.size() + (group.books.size() == 1 ? " titolo" : " titoli");
            paint.setTextSize(d(12)); float countWidth = paint.measureText(count) / getResources().getDisplayMetrics().density;
            boolean single = group.name.equals(MapGroups.SINGLE);
            label(c, single ? "SENZA SAGA" : "SAGA", SAGA_X + 16, y + 22, 10, activity.SAGE, false, 0);
            label(c, group.name, SAGA_X + 16, y + 48, 19, activity.INK, true, d(right - SAGA_X - 40 - countWidth));
            label(c, count, right - 14 - countWidth, y + 48, 12, activity.SAGE, false, 0);
            y += SAGA_H + BOOK_GAP;

            float bookTop = y, lastBookMid = y;
            for (Book b : group.books) {
                double n = single ? 0 : MapGroups.number(b, known.get(b.id));
                float mid = y + BOOK_H / 2f; lastBookMid = mid;
                branch(c, BOOK_SPINE, BOOK_X, mid);
                RectF rect = new RectF(d(BOOK_X), d(y), d(right), d(y + BOOK_H)); box(c, rect, activity.WHITE, activity.LINE);
                label(c, n > 0 ? MainActivity.number(n) : "·", BOOK_X + 14, y + 36, 20, activity.COPPER, true, 0);
                boolean done = b.status.equals("Letto");
                label(c, b.title, BOOK_X + 52, y + (done || !b.status.equals("Da leggere") ? 25 : 34), 15, activity.INK, false, d(right - BOOK_X - 52 - 30));
                if (!b.status.equals("Da leggere")) label(c, b.status, BOOK_X + 52, y + 44, 11, done ? activity.SAGE : activity.COPPER, false, d(right - BOOK_X - 82));
                label(c, "↗", right - 24, y + 34, 15, activity.MUTED, false, 0);
                hits.add(new Hit(rect, b));
                y += BOOK_H + BOOK_GAP;
            }
            line(c, BOOK_SPINE, bookTop - BOOK_GAP, BOOK_SPINE, lastBookMid);
            line(c, BOOK_SPINE, sagaMid + SAGA_H / 2f, BOOK_SPINE, bookTop - BOOK_GAP);
            y -= BOOK_GAP;
        }
        line(c, SPINE, PAD + AUTHOR_H, SPINE, lastSagaMid);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
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
