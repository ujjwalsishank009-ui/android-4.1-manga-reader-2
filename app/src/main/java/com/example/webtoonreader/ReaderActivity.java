package com.example.webtoonreader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Continuous vertical (webtoon) reader.
 * Every page is cut into slices of at most MAX_DECODED_H pixels so that very tall
 * strips never exceed old GPU texture limits, and each slice is decoded at
 * screen width (power-of-two downsampling) in RGB_565 to keep memory low.
 */
public class ReaderActivity extends Activity {

    private static final int MAX_DECODED_H = 1800;

    private static class Slice {
        int page, top, bottom, srcW, srcH, sample, dispH;
    }

    private final List<Slice> slices = new ArrayList<Slice>();
    private final Set<Integer> inFlight = new HashSet<Integer>();
    private final Handler handler = new Handler();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private volatile int wantLo = 0, wantHi = Integer.MAX_VALUE;

    private LruCache<Integer, Bitmap> cache;
    private PageSource source;
    private LoadTask task;
    private ListView list;
    private TextView status;
    private FrameLayout root;
    private File file;
    private SharedPreferences prefs;
    private int screenW;
    private boolean navHidden = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String path = getIntent().getStringExtra("path");
        if (path == null && getIntent().getData() != null
                && "file".equals(getIntent().getData().getScheme())) {
            path = getIntent().getData().getPath();
        }
        if (path == null) {
            Toast.makeText(this, "Open a local .cbz/.zip or image folder", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        file = new File(path);
        prefs = getSharedPreferences("reader", 0);
        screenW = getResources().getDisplayMetrics().widthPixels;

        int maxBytes = (int) (Runtime.getRuntime().maxMemory() / 4);
        cache = new LruCache<Integer, Bitmap>(maxBytes) {
            @Override protected int sizeOf(Integer k, Bitmap v) {
                return v.getRowBytes() * v.getHeight();
            }
        };

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setGravity(android.view.Gravity.CENTER);
        status.setText("Loading…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        task = new LoadTask();
        task.execute();
    }

    // ---------------------------------------------------------------- loading

    private class LoadTask extends AsyncTask<Void, Integer, String> {
        PageSource src;
        final List<Slice> out = new ArrayList<Slice>();

        @Override
        protected String doInBackground(Void... v) {
            try {
                src = PageSource.create(file);
            } catch (IOException e) {
                return "Cannot open file: " + e.getMessage();
            }
            int n = src.names.size();
            if (n == 0) return "No images found";
            for (int i = 0; i < n; i++) {
                if (isCancelled()) return "cancelled";
                InputStream in = null;
                try {
                    in = new BufferedInputStream(src.open(i), 16384);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, o);
                    if (o.outWidth > 0 && o.outHeight > 0) addSlices(i, o.outWidth, o.outHeight);
                } catch (Exception ignored) {
                } finally {
                    close(in);
                }
                if (i % 8 == 0) publishProgress(i, n);
            }
            return out.isEmpty() ? "Could not read any images" : null;
        }

        private void addSlices(int page, int w, int h) {
            float scale = (float) screenW / w;
            int s = 1;
            while (w / (s * 2) >= screenW) s *= 2;
            int step = MAX_DECODED_H * s;
            for (int top = 0; top < h; top += step) {
                Slice sl = new Slice();
                sl.page = page;
                sl.top = top;
                sl.bottom = Math.min(h, top + step);
                sl.srcW = w;
                sl.srcH = h;
                sl.sample = s;
                // rounding cumulative edges avoids 1px seams between slices
                sl.dispH = Math.max(1, Math.round(sl.bottom * scale) - Math.round(top * scale));
                out.add(sl);
            }
        }

        @Override
        protected void onProgressUpdate(Integer... p) {
            status.setText("Loading " + p[0] + " / " + p[1]);
        }

        @Override
        protected void onPostExecute(String err) {
            if (err != null) {
                status.setText(err);
                return;
            }
            source = src;
            slices.addAll(out);
            setupList();
        }
    }

    private static void close(InputStream in) {
        if (in != null) try { in.close(); } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------- list

    private void setupList() {
        root.removeView(status);
        list = new ListView(this);
        list.setBackgroundColor(Color.BLACK);
        list.setCacheColorHint(Color.BLACK);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setScrollingCacheEnabled(false);
        list.setFastScrollEnabled(true);
        list.addFooterView(buildFooter(), null, false);
        list.setAdapter(new SliceAdapter());

        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                wantLo = first - 1;
                wantHi = first + visible + 2;
                for (int i = Math.max(0, first); i <= wantHi; i++) request(i);
            }
        });

        final GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                int h = list.getHeight();
                int jump = (int) (h * 0.85f);
                if (e.getY() < h * 0.25f) list.smoothScrollBy(-jump, 250);
                else if (e.getY() > h * 0.75f) list.smoothScrollBy(jump, 250);
                else { navHidden = !navHidden; applyNav(); }
                return true;
            }
        });
        list.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                gd.onTouchEvent(e);
                return false;
            }
        });
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));

        String saved = prefs.getString("pos_" + file.getAbsolutePath(), null);
        if (saved != null) {
            try {
                String[] p = saved.split(",");
                final int pos = Math.min(Integer.parseInt(p[0]), slices.size() - 1);
                final int off = Integer.parseInt(p[1]);
                list.post(new Runnable() {
                    @Override public void run() { list.setSelectionFromTop(pos, off); }
                });
            } catch (Exception ignored) {}
        }
        applyNav();
    }

    private class SliceAdapter extends BaseAdapter {
        @Override public int getCount() { return slices.size(); }
        @Override public Object getItem(int i) { return slices.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int i) { return false; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            ImageView iv = (ImageView) cv;
            if (iv == null) {
                iv = new ImageView(ReaderActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
            }
            iv.setLayoutParams(new AbsListView.LayoutParams(screenW, slices.get(pos).dispH));
            iv.setTag(Integer.valueOf(pos));
            Bitmap bmp = cache.get(pos);
            iv.setImageBitmap(bmp);
            if (bmp == null) request(pos);
            return iv;
        }
    }

    private void request(final int pos) {
        if (pos < 0 || pos >= slices.size() || cache.get(pos) != null || !inFlight.add(pos)) return;
        final Slice s = slices.get(pos);
        try {
            pool.execute(new Runnable() {
                @Override public void run() {
                    // skip work for slices the user has already scrolled past
                    final Bitmap bmp = (pos < wantLo || pos > wantHi) ? null : decode(s);
                    handler.post(new Runnable() {
                        @Override public void run() {
                            inFlight.remove(pos);
                            if (bmp != null) {
                                cache.put(pos, bmp);
                                showIfVisible(pos, bmp);
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            inFlight.remove(pos);
        }
    }

    private void showIfVisible(int pos, Bitmap bmp) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c instanceof ImageView && Integer.valueOf(pos).equals(c.getTag())) {
                ((ImageView) c).setImageBitmap(bmp);
            }
        }
    }

    private Bitmap decode(Slice s) {
        InputStream in = null;
        try {
            in = new BufferedInputStream(source.open(s.page), 65536);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = s.sample;
            if (s.top == 0 && s.bottom >= s.srcH) {
                return BitmapFactory.decodeStream(in, null, o);
            }
            BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(in, false);
            try {
                return d.decodeRegion(new Rect(0, s.top, s.srcW, s.bottom), o);
            } finally {
                d.recycle();
            }
        } catch (Throwable t) {   // includes OutOfMemoryError
            cache.evictAll();
            return null;
        } finally {
            close(in);
        }
    }

    // ----------------------------------------------------------- next chapter

    private File findNext() {
        File parent = file.getParentFile();
        if (parent == null) return null;
        File[] kids = parent.listFiles();
        if (kids == null) return null;
        List<File> sibs = new ArrayList<File>();
        for (File f : kids) {
            if (f.getName().startsWith(".")) continue;
            if (file.isDirectory() ? f.isDirectory() : PageSource.isArchive(f.getName())) sibs.add(f);
        }
        Collections.sort(sibs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return PageSource.natural(a.getName(), b.getName());
            }
        });
        int i = sibs.indexOf(file);
        return (i >= 0 && i + 1 < sibs.size()) ? sibs.get(i + 1) : null;
    }

    private View buildFooter() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setGravity(android.view.Gravity.CENTER);
        f.setPadding(32, 96, 32, 160);
        TextView t = new TextView(this);
        t.setTextColor(Color.LTGRAY);
        t.setGravity(android.view.Gravity.CENTER);
        t.setText("End of " + file.getName());
        f.addView(t);
        final File next = findNext();
        if (next != null) {
            Button bt = new Button(this);
            bt.setText("Next: " + next.getName());
            bt.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    savePosition(0, 0);
                    Intent i = new Intent(ReaderActivity.this, ReaderActivity.class);
                    i.putExtra("path", next.getAbsolutePath());
                    startActivity(i);
                    finish();
                }
            });
            f.addView(bt);
        }
        return f;
    }

    // ------------------------------------------------------- input / lifecycle

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (list != null && (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)) {
            int jump = (int) (list.getHeight() * 0.85f);
            list.smoothScrollBy(code == KeyEvent.KEYCODE_VOLUME_DOWN ? jump : -jump, 250);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void applyNav() {
        int flags = View.SYSTEM_UI_FLAG_VISIBLE;
        if (navHidden) {
            flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;   // last flag only used on 4.4+
        }
        root.setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && root != null) applyNav();
    }

    private void savePosition(int pos, int off) {
        prefs.edit().putString("pos_" + file.getAbsolutePath(), pos + "," + off).apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (list != null && !slices.isEmpty()) {
            View c = list.getChildAt(0);
            savePosition(list.getFirstVisiblePosition(), c == null ? 0 : c.getTop());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (task != null) task.cancel(true);
        pool.shutdownNow();
        if (cache != null) cache.evictAll();
        if (source != null) source.close();
    }
}
