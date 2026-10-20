package com.mytv.player;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private TextView header, info;
    private final List<String> rows = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private String current = null; // playlist being viewed, or null for the home list
    private int seen = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (Store.version != seen) refresh();
            updateInfo();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Store.init(this);
        RemoteService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1E2327"));
        root.setPadding(dp(48), dp(32), dp(48), dp(24));

        header = new TextView(this);
        header.setTextSize(30);
        header.setTextColor(Color.WHITE);

        info = new TextView(this);
        info.setTextSize(16);
        info.setTextColor(Color.parseColor("#9AA0A6"));
        info.setPadding(0, dp(6), 0, dp(16));

        ListView listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextSize(22);
                t.setTextColor(Color.WHITE);
                t.setPadding(dp(16), dp(14), dp(16), dp(14));
                return t;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < actions.size() && actions.get(position) != null) actions.get(position).run();
        });

        root.addView(header);
        root.addView(info);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        listView.requestFocus();
        refresh();
        updateInfo();
    }

    private void updateInfo() {
        StringBuilder sb = new StringBuilder();
        if (RemoteService.error != null) sb.append(RemoteService.error);
        else sb.append("Phone remote & add links (same Wi-Fi):  http://").append(Net.ip()).append(":").append(RemoteService.PORT);
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            sb.append("\nTip: to start videos from the phone while this app is closed, run once on your PC:\n"
                    + "adb shell appops set com.mytv.player SYSTEM_ALERT_WINDOW allow");
        }
        info.setText(sb.toString());
    }

    private void refresh() {
        seen = Store.version;
        rows.clear();
        actions.clear();
        if (current == null) {
            header.setText("My TV Player");
            rows.add("📁  Local files (USB drive / storage)");
            actions.add(() -> startActivity(new Intent(this, FileBrowserActivity.class)));
            for (final String n : Store.names()) {
                rows.add((Store.RECENT.equals(n) ? "🕘  " : "▶  ") + n + "   (" + Store.items(n).size() + ")");
                actions.add(() -> {
                    current = n;
                    refresh();
                });
            }
            if (Store.names().isEmpty()) {
                rows.add("No playlists yet. Open the address above on your phone to add videos, links or magnets.");
                actions.add(null);
            }
        } else {
            if (!Store.names().contains(current)) {
                current = null;
                refresh();
                return;
            }
            header.setText(current);
            final List<Store.Item> items = Store.items(current);
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                rows.add(items.get(i).t);
                actions.add(() -> Remote.play(this, Store.items(current), idx));
            }
            if (items.isEmpty()) {
                rows.add("This playlist is empty.");
                actions.add(null);
            }
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        if (current != null) {
            current = null;
            refresh();
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
