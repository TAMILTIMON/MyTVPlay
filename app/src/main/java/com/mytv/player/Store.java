package com.mytv.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Keeps playlists, resume positions and player settings on the TV. */
public class Store {
    public static final String RECENT = "Recently played";
    private static final int MAX_RECENT = 40;
    public static final int EQ_BANDS = 10;

    public static class Item {
        public final String t, u, s; // title, url, subtitle url (optional)

        public Item(String t, String u, String s) {
            this.u = u == null ? "" : u.trim();
            this.t = (t == null || t.trim().isEmpty()) ? Formats.titleFromUrl(this.u) : t.trim();
            this.s = s == null ? "" : s.trim();
        }
    }

    private static SharedPreferences prefs, posPrefs, setPrefs;
    private static final LinkedHashMap<String, List<Item>> data = new LinkedHashMap<>();
    public static volatile int version = 0;

    public static synchronized void init(Context c) {
        if (prefs != null) return;
        Context app = c.getApplicationContext();
        prefs = app.getSharedPreferences("mytv", Context.MODE_PRIVATE);
        posPrefs = app.getSharedPreferences("mytv_pos", Context.MODE_PRIVATE);
        setPrefs = app.getSharedPreferences("mytv_settings", Context.MODE_PRIVATE);
        load();
    }

    private static void load() {
        data.clear();
        try {
            JSONObject o = new JSONObject(prefs.getString("data", "{\"playlists\":[]}"));
            JSONArray a = o.getJSONArray("playlists");
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                JSONArray it = p.getJSONArray("items");
                List<Item> l = new ArrayList<>();
                for (int j = 0; j < it.length(); j++) {
                    JSONObject x = it.getJSONObject(j);
                    l.add(new Item(x.optString("t"), x.optString("u"), x.optString("s", "")));
                }
                data.put(p.getString("name"), l);
            }
        } catch (Exception ignored) {
        }
    }

    private static String buildJson() {
        try {
            JSONArray arr = new JSONArray();
            for (String name : data.keySet()) {
                JSONArray items = new JSONArray();
                for (Item it : data.get(name)) {
                    JSONObject x = new JSONObject().put("t", it.t).put("u", it.u);
                    if (!it.s.isEmpty()) x.put("s", it.s);
                    items.put(x);
                }
                arr.put(new JSONObject().put("name", name).put("items", items));
            }
            return new JSONObject().put("playlists", arr).toString();
        } catch (Exception e) {
            return "{\"playlists\":[]}";
        }
    }

    private static void save() {
        prefs.edit().putString("data", buildJson()).apply();
        version++;
    }

    private static String cleanName(String n) {
        n = n == null ? "" : n.trim();
        return n.isEmpty() ? "My Playlist" : n;
    }

    public static synchronized String toJson() {
        return buildJson();
    }

    /** Playlist names, "Recently played" first. */
    public static synchronized List<String> names() {
        List<String> l = new ArrayList<>();
        if (data.containsKey(RECENT)) l.add(RECENT);
        for (String n : data.keySet()) if (!RECENT.equals(n)) l.add(n);
        return l;
    }

    public static synchronized List<Item> items(String name) {
        List<Item> l = data.get(name);
        return l == null ? new ArrayList<Item>() : new ArrayList<>(l);
    }

    private static List<Item> list(String name) {
        List<Item> l = data.get(name);
        if (l == null) {
            l = new ArrayList<>();
            data.put(name, l);
        }
        return l;
    }

    public static synchronized void add(String name, Item item) {
        list(cleanName(name)).add(item);
        save();
    }

    public static synchronized int addAll(String name, List<Item> items) {
        list(cleanName(name)).addAll(items);
        save();
        return items.size();
    }

    public static synchronized void addRecent(Item item) {
        List<Item> l = list(RECENT);
        Iterator<Item> i = l.iterator();
        while (i.hasNext()) if (i.next().u.equals(item.u)) i.remove();
        l.add(0, item);
        while (l.size() > MAX_RECENT) l.remove(l.size() - 1);
        save();
    }

    public static synchronized void delItem(String name, int idx) {
        List<Item> l = data.get(name);
        if (l != null && idx >= 0 && idx < l.size()) {
            l.remove(idx);
            save();
        }
    }

    public static synchronized void moveItem(String name, int idx, int dir) {
        List<Item> l = data.get(name);
        if (l == null) return;
        int to = idx + dir;
        if (idx < 0 || idx >= l.size() || to < 0 || to >= l.size()) return;
        Item it = l.remove(idx);
        l.add(to, it);
        save();
    }

    public static synchronized void delPlaylist(String name) {
        if (data.remove(name) != null) save();
    }

    // ---------- resume positions ----------
    public static long pos(String url) {
        return posPrefs == null ? 0 : posPrefs.getLong(url, 0);
    }

    public static void setPos(String url, long p) {
        if (posPrefs != null) posPrefs.edit().putLong(url, p).apply();
    }

    // ---------- settings ----------
    public static int getInt(String k, int d) {
        return setPrefs == null ? d : setPrefs.getInt(k, d);
    }

    public static void putInt(String k, int v) {
        if (setPrefs != null) setPrefs.edit().putInt(k, v).apply();
    }

    public static float getFloat(String k, float d) {
        return setPrefs == null ? d : setPrefs.getFloat(k, d);
    }

    public static void putFloat(String k, float v) {
        if (setPrefs != null) setPrefs.edit().putFloat(k, v).apply();
    }

    public static String getStr(String k, String d) {
        return setPrefs == null ? d : setPrefs.getString(k, d);
    }

    public static void putStr(String k, String v) {
        if (setPrefs != null) setPrefs.edit().putString(k, v).apply();
    }

    public static boolean getBool(String k, boolean d) {
        return setPrefs == null ? d : setPrefs.getBoolean(k, d);
    }

    public static void putBool(String k, boolean v) {
        if (setPrefs != null) setPrefs.edit().putBoolean(k, v).apply();
    }

    public static float[] getBands() {
        float[] b = new float[EQ_BANDS];
        String[] p = getStr("eq_bands", "").split(",");
        for (int i = 0; i < b.length && i < p.length; i++) {
            try {
                b[i] = Float.parseFloat(p[i]);
            } catch (Exception ignored) {
            }
        }
        return b;
    }

    public static void putBands(float[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.1f", b[i]));
        }
        putStr("eq_bands", sb.toString());
    }

    /** Reads an M3U playlist, or a plain list of links, one per line. */
    public static List<Item> parseM3u(String text) {
        List<Item> out = new ArrayList<>();
        String title = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.startsWith("#EXTINF")) {
                int c = line.lastIndexOf(',');
                title = c >= 0 ? line.substring(c + 1).trim() : null;
            } else if (line.startsWith("#")) {
                continue;
            } else if (Formats.looksPlayable(line)) {
                out.add(new Item(title, line, ""));
                title = null;
            }
        }
        return out;
    }
}
