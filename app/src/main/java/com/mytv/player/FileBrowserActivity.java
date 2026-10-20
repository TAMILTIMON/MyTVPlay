package com.mytv.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Browse the TV storage and USB drives and play any media file (subtitles next to the file load automatically). */
public class FileBrowserActivity extends AppCompatActivity {
    private TextView header, info;
    private final List<String> rows = new ArrayList<>();
    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private File dir = null; // null = list of drives

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1E2327"));
        root.setPadding(dp(48), dp(32), dp(48), dp(24));
        header = new TextView(this);
        header.setTextSize(28);
        header.setTextColor(Color.WHITE);
        info = new TextView(this);
        info.setTextSize(15);
        info.setTextColor(Color.parseColor("#9AA0A6"));
        info.setPadding(0, dp(6), 0, dp(12));
        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextSize(20);
                t.setTextColor(Color.WHITE);
                t.setPadding(dp(16), dp(12), dp(16), dp(12));
                return t;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> open(pos));
        root.addView(header);
        root.addView(info);
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        askPermission();
        show(null);
    }

    private void askPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO}, 1);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        show(dir);
    }

    private boolean needAllFiles() {
        return Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager();
    }

    private List<File> roots() {
        Set<File> out = new LinkedHashSet<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) out.add(primary);
        File[] st = new File("/storage").listFiles();
        if (st != null) {
            for (File f : st) {
                String n = f.getName();
                if (n.equals("self") || n.equals("emulated")) continue;
                if (f.isDirectory()) out.add(f);
            }
        }
        for (String p : new String[]{"/mnt/usb", "/mnt/usbhost", "/mnt/media_rw", "/mnt/sdcard"}) {
            File f = new File(p);
            if (f.isDirectory() && f.canRead()) out.add(f);
        }
        return new ArrayList<>(out);
    }

    private void show(File d) {
        dir = d;
        rows.clear();
        files.clear();
        info.setText(needAllFiles()
                ? "To see every file (and subtitle files), allow “All files access”: select the first row, or run\n"
                + "adb shell appops set com.mytv.player MANAGE_EXTERNAL_STORAGE allow"
                : "Subtitle files with the same name as the video are loaded automatically.");
        if (needAllFiles()) {
            rows.add("🔓  Allow access to all files");
            files.add(null);
        }
        if (d == null) {
            header.setText("Local files");
            for (File r : roots()) {
                rows.add("💾  " + r.getAbsolutePath());
                files.add(r);
            }
        } else {
            header.setText(d.getAbsolutePath());
            rows.add("⬆  ..");
            files.add(d.getParentFile() == null || isRoot(d) ? new File("") : d.getParentFile());
            File[] kids = d.listFiles();
            if (kids == null) kids = new File[0];
            Arrays.sort(kids, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File k : kids) {
                if (k.getName().startsWith(".")) continue;
                if (k.isDirectory()) {
                    rows.add("📁  " + k.getName());
                    files.add(k);
                } else if (Formats.isMedia(k.getName())) {
                    rows.add((Formats.isAudio(k.getName()) ? "🎵  " : "🎬  ") + k.getName());
                    files.add(k);
                }
            }
            if (kids.length == 0) {
                rows.add("(empty or no permission)");
                files.add(null);
            }
        }
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
    }

    private boolean isRoot(File d) {
        for (File r : roots()) if (r.getAbsolutePath().equals(d.getAbsolutePath())) return true;
        return false;
    }

    private void open(int pos) {
        if (pos < 0 || pos >= files.size()) return;
        File f = files.get(pos);
        if (f == null) {
            if (needAllFiles() && pos == 0) requestAllFiles();
            return;
        }
        if (f.getPath().isEmpty()) {
            show(null);
            return;
        }
        if (f.isDirectory()) {
            show(f);
            return;
        }
        // Play this file and the rest of the folder after it.
        List<Store.Item> items = new ArrayList<>();
        int start = 0;
        for (File x : files) {
            if (x == null || x.getPath().isEmpty() || x.isDirectory()) continue;
            if (x.equals(f)) start = items.size();
            items.add(new Store.Item(x.getName(), x.getAbsolutePath(), ""));
        }
        Store.addRecent(new Store.Item(f.getName(), f.getAbsolutePath(), ""));
        Remote.play(this, items, start);
    }

    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                Toast.makeText(this, "This TV has no settings page for it. Use the adb command shown on screen.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(dir);
    }

    @Override
    public void onBackPressed() {
        if (dir != null) {
            show(isRoot(dir) ? null : dir.getParentFile());
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
