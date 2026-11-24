package com.example.webtoonreader;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A chapter: either a .cbz/.zip archive or a plain folder of images. */
abstract class PageSource implements Closeable {

    final List<String> names = new ArrayList<String>();

    abstract InputStream open(int index) throws IOException;

    @Override
    public void close() {}

    static PageSource create(File f) throws IOException {
        PageSource s = f.isDirectory() ? new DirSource(f) : new ZipSource(f);
        Collections.sort(s.names, NATURAL);
        return s;
    }

    static boolean isImage(String n) {
        String l = n.toLowerCase(Locale.US);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp");
    }

    static boolean isArchive(String n) {
        String l = n.toLowerCase(Locale.US);
        return l.endsWith(".cbz") || l.endsWith(".zip");
    }

    static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    static final Comparator<String> NATURAL = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return natural(a, b);
        }
    };

    /** "page2" < "page10" */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char c = a.charAt(i), d = b.charAt(j);
            if (Character.isDigit(c) && Character.isDigit(d)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String x = a.substring(si, i).replaceFirst("^0+", "");
                String y = b.substring(sj, j).replaceFirst("^0+", "");
                if (x.length() != y.length()) return x.length() - y.length();
                int r = x.compareTo(y);
                if (r != 0) return r;
            } else {
                if (c != d) {
                    char lc = Character.toLowerCase(c), ld = Character.toLowerCase(d);
                    if (lc != ld) return lc - ld;
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    static class ZipSource extends PageSource {
        private final ZipFile zip;
        private final Map<String, ZipEntry> map = new HashMap<String, ZipEntry>();

        ZipSource(File f) throws IOException {
            zip = new ZipFile(f);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !isImage(n) || n.startsWith("__MACOSX")
                        || baseName(n).startsWith(".")) continue;
                map.put(n, e);
                names.add(n);
            }
        }

        @Override
        InputStream open(int i) throws IOException {
            return zip.getInputStream(map.get(names.get(i)));
        }

        @Override
        public void close() {
            try { zip.close(); } catch (IOException ignored) {}
        }
    }

    static class DirSource extends PageSource {
        private final File dir;

        DirSource(File d) {
            dir = d;
            File[] kids = d.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (k.isFile() && isImage(k.getName()) && !k.getName().startsWith(".")) {
                        names.add(k.getName());
                    }
                }
            }
        }

        @Override
        InputStream open(int i) throws IOException {
            return new FileInputStream(new File(dir, names.get(i)));
        }
    }
}
