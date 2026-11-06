package app.atlante.library;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/** Library grid/list: only the visible cards exist; position 0 is a full-width header. */
final class BookAdapter extends RecyclerView.Adapter<BookAdapter.Holder> {
    interface Cards {
        View header();
        View card(Book book);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout slot;
        Holder(FrameLayout slot) { super(slot); this.slot = slot; }
    }

    private static final int HEADER = 0, BOOK = 1;
    private final Cards cards;
    private final ArrayList<Book> books = new ArrayList<>();

    BookAdapter(Cards cards) { this.cards = cards; }

    @SuppressWarnings("NotifyDataSetChanged") // filters and sorting can change every position at once
    void submit(List<Book> list) {
        books.clear(); books.addAll(list);
        notifyDataSetChanged();
    }

    /** Header spans the whole row; each book takes one column. */
    GridLayoutManager.SpanSizeLookup spans(GridLayoutManager manager) {
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

    @Override public void onBindViewHolder(Holder holder, int position) {
        holder.slot.removeAllViews();
        holder.slot.addView(position == 0 ? cards.header() : cards.card(books.get(position - 1)));
    }
}
