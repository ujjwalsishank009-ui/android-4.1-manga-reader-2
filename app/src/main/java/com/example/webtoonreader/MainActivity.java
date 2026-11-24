package com.example.webtoonreader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Simple file browser. Works on API 16 (no Storage Access Framework there). */
public class MainActivity extends Activity {

    private static class Entry {
        String label;
        File file;
        boolean up, readFolder;
        @Override public String toString() { return label; }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private ArrayAdapter<Entry> adapter;
    private TextView pathView;
    private File dir;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("reader", 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        pathView = new TextView(this);
        pathView.setPadding(24, 20, 24, 20);
        pathView.setTextColor(Color.LTGRAY);
        pathView.setTextSize(13);
        root.addView(pathView);

        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<Entry>(this, android.R.layout.simple_list_item_1, entries);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Entry e = entries.get(pos);
                if (e.up || (e.file.isDirectory() && !e.readFolder)) {
                    load(e.file);
                } else {
                    Intent i = new Intent(MainActivity.this, ReaderActivity.class);
                    i.putExtra("path", e.file.getAbsolutePath());
                    startActivity(i);
                }
            }
        });
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        File start = new File(prefs.getString("lastDir", ""));
        if (!start.isDirectory()) start = Environment.getExternalStorageDirectory();
        load(start);
    }

    private void load(File d) {
        File[] kids = d.listFiles();
        if (kids == null) {
            Toast.makeText(this, "Can't read this folder", Toast.LENGTH_SHORT).show();
            return;
        }
        dir = d;
        entries.clear();

        File parent = d.getParentFile();
        if (parent != null) {
            Entry up = new Entry();
            up.label = ".. (up)";
            up.file = parent;
            up.up = true;
            entries.add(up);
        }

        List<File> dirs = new ArrayList<File>();
        List<File> archives = new ArrayList<File>();
        int images = 0;
        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) dirs.add(f);
            else if (PageSource.isArchive(n)) archives.add(f);
            else if (PageSource.isImage(n)) images++;
        }
        Comparator<File> byName = new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return PageSource.natural(a.getName(), b.getName());
            }
        };
        Collections.sort(dirs, byName);
        Collections.sort(archives, byName);

        if (images > 0) {
            Entry r = new Entry();
            r.label = ">> Read this folder (" + images + " images)";
            r.file = d;
            r.readFolder = true;
            entries.add(r);
        }
        for (File f : dirs) {
            Entry e = new Entry();
            e.label = f.getName() + "/";
            e.file = f;
            entries.add(e);
        }
        for (File f : archives) {
            Entry e = new Entry();
            e.label = f.getName();
            e.file = f;
            entries.add(e);
        }
        pathView.setText(d.getAbsolutePath() + "  (" + archives.size() + " archives)");
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onBackPressed() {
        File root = Environment.getExternalStorageDirectory();
        if (dir != null && dir.getParentFile() != null && !dir.equals(root)) {
            load(dir.getParentFile());
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dir != null) prefs.edit().putString("lastDir", dir.getAbsolutePath()).apply();
    }
}
