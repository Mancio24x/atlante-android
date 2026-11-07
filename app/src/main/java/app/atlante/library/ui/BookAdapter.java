package app.atlante.library.ui;

import app.atlante.library.model.Book;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/** Library grid/list: only the visible cards exist; position 0 is a full-width header. */
public final class BookAdapter extends RecyclerView.Adapter<BookAdapter.Holder> {
    public interface Cards {
        public View header();
        public View card(Book book);
        /** Updates a card made before for another book; null when it cannot be reused (a new one is made). */
        default View rebind(View card, Book book) { return null; }
    }

    public static final class Holder extends RecyclerView.ViewHolder {
        public final FrameLayout slot;
        public Holder(FrameLayout slot) { super(slot); this.slot = slot; }
    }

    private static final int HEADER = 0, BOOK = 1;
    private final Cards cards;
    private final ArrayList<Book> books = new ArrayList<>();

    public BookAdapter(Cards cards) { this.cards = cards; }

    @SuppressWarnings("NotifyDataSetChanged") // filters and sorting can change every position at once
    public void submit(List<Book> list) {
        books.clear(); books.addAll(list);
        notifyDataSetChanged();
    }

    /** Header spans the whole row; each book takes one column. */
    public GridLayoutManager.SpanSizeLookup spans(GridLayoutManager manager) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return position == 0 ? manager.getSpanCount() : 1; }
        };
    }

    @Override public int getItemCount() { return books.size() + 1; }
    @Override public int getItemViewType(int position) { return position == 0 ? HEADER : BOOK; }

    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
        FrameLayout slot = new FrameLayout(parent.getContext());
        slot.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return new Holder(slot);
    }

    /**
     * A recycled book slot keeps its card and only refreshes its content (no new views while scrolling);
     * the header, and cards that cannot be refreshed, are made again.
     */
    @Override public void onBindViewHolder(Holder holder, int position) {
        if (position > 0 && holder.slot.getChildCount() == 1 && cards.rebind(holder.slot.getChildAt(0), books.get(position - 1)) != null) return;
        holder.slot.removeAllViews();
        holder.slot.addView(position == 0 ? cards.header() : cards.card(books.get(position - 1)));
    }
}
