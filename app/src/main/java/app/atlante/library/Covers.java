package app.atlante.library;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cover cache and loader: grid thumbnails are decoded off the UI thread at card size. */
final class Covers {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) Math.min(64 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
    };

    /** Large cover for detail and preview dialogs (decoded once, then cached). */
    Bitmap large(String id, File file) {
        Bitmap cached = cache.get(id); if (cached != null) return cached;
        if (!file.exists()) return null;
        Bitmap bitmap = decode(file.getPath(), null, 512);
        if (bitmap != null) cache.put(id, bitmap);
        return bitmap;
    }

    /**
     * Shows a cached thumbnail at once, otherwise decodes it in the background.
     * {@code fallback} is shown only when the book has no readable cover.
     * Recycled views are protected by the tag: a late result for another book is dropped.
     */
    void thumbnail(String id, File file, int size, ImageView image, View fallback) {
        String key = "t:" + id;
        image.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) { image.setImageBitmap(cached); fallback.setVisibility(View.INVISIBLE); return; }
        image.setImageDrawable(null); fallback.setVisibility(View.INVISIBLE);
        workers.execute(() -> {
            if (!key.equals(image.getTag())) return; // scrolled away before decoding started
            Bitmap bitmap = null;
            try { if (file.isFile()) bitmap = thumbnail(file.getPath(), size); } catch (Exception | OutOfMemoryError ignored) { }
            if (bitmap != null) cache.put(key, bitmap);
            final Bitmap result = bitmap;
            ui.post(() -> {
                if (!key.equals(image.getTag())) return;
                if (result != null) image.setImageBitmap(result); else fallback.setVisibility(View.VISIBLE);
            });
        });
    }

    void forget(String id) { cache.remove(id); cache.remove("t:" + id); }
    void clear() { cache.evictAll(); }
    void shutdown() { workers.shutdownNow(); }

    static Bitmap thumbnail(String file, int max) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = sampleSize(options.outWidth, options.outHeight, max);
        options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file, options);
    }

    /** Largest power of two that keeps both sides at least {@code max} (or the original size if smaller). */
    static int sampleSize(int width, int height, int max) {
        int sample = 1;
        while (width / (sample * 2) >= max || height / (sample * 2) >= max) sample *= 2;
        return sample;
    }

    static Bitmap decode(String file, byte[] bytes, int max) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        if (file != null) BitmapFactory.decodeFile(file, options); else BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > max || options.outHeight / options.inSampleSize > max) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return file != null ? BitmapFactory.decodeFile(file, options) : BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
