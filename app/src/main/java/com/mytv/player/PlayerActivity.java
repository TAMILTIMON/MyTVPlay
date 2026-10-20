package com.mytv.player;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_HANDOFF = "handoff";
    public static final String EXTRA_PLAYLIST = "playlist";
    public static final String EXTRA_INDEX = "index";

    // ---------- choices (shared with the phone page) ----------
    static final String[] ASPECT_VALUES = {"", "16:9", "4:3", "21:9", "235:100", "239:100", "185:100", "2:1",
            "16:10", "5:4", "1:1", "3:2", "32:9"};
    static final String[] ASPECT_NAMES = {"Auto (from video)", "16:9", "4:3", "21:9 (ultrawide)",
            "2.35:1 (CinemaScope)", "2.39:1 (anamorphic)", "1.85:1 (widescreen film)", "2:1 (18:9)", "16:10",
            "5:4", "1:1 (square)", "3:2", "32:9"};
    static final String[] FIT_NAMES = {"Best fit (whole picture)", "Fill screen (crop edges)",
            "Stretch to screen", "Original size"};
    private static final MediaPlayer.ScaleType[] FITS = {MediaPlayer.ScaleType.SURFACE_BEST_FIT,
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_ORIGINAL};
    static final int[] ZOOM_VALUES = {100, 104, 132, 134, 133, 110, 120, 125, 150, 175, 200};
    static final String[] ZOOM_NAMES = {"100% (no zoom)", "104% – crop bars of 1.85:1 film",
            "132% – crop bars of 21:9 / 2.35:1 film", "134% – crop bars of 2.39:1 film",
            "133% – crop side bars of 4:3 video", "110%", "120%", "125%", "150%", "175%", "200%"};
    static final int[] SUB_SIZES = {24, 20, 16, 12, 9};
    static final String[] SUB_SIZE_NAMES = {"Tiny", "Small", "Normal", "Large", "Huge"};
    static final String[] ENC_VALUES = {"", "UTF-8", "Windows-1252", "Windows-1256", "Windows-1251",
            "Windows-1250", "ISO-8859-9", "ISO-8859-7", "Windows-1255", "GB18030", "Big5", "Shift_JIS", "EUC-KR"};
    static final String[] ENC_NAMES = {"Automatic", "UTF-8", "Western (Windows-1252)", "Arabic (Windows-1256)",
            "Cyrillic (Windows-1251)", "Central European (Windows-1250)", "Turkish (ISO-8859-9)",
            "Greek (ISO-8859-7)", "Hebrew (Windows-1255)", "Chinese simplified (GB18030)",
            "Chinese traditional (Big5)", "Japanese (Shift_JIS)", "Korean (EUC-KR)"};
    static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f};
    static final int[] CACHES = {500, 1000, 2000, 5000, 10000, 20000};
    static final int[] SLEEPS = {0, 15, 30, 45, 60, 90, 120, -1};
    static final int[] VOLS = {25, 50, 75, 100, 125, 150, 175, 200};
    static final String[] DEFAULT_PRESETS = {"Flat", "Classical", "Club", "Dance", "Full bass",
            "Full bass and treble", "Full treble", "Headphones", "Large Hall", "Live", "Party", "Pop", "Reggae",
            "Rock", "Ska", "Soft", "Soft rock", "Techno"};
    static final float[] DEFAULT_FREQS = {60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000};

    static volatile String[] PRESETS;
    static volatile float[] BAND_FREQS;

    // ---------- engine ----------
    private LibVLC libVLC;
    private MediaPlayer mp;
    private boolean viewsAttached;
    private ParcelFileDescriptor pfd;

    // ---------- UI ----------
    private FrameLayout root;
    private VLCVideoLayout videoLayout;
    private LinearLayout panel;
    private TextView titleView, timeView, infoView, osdView, loadingView;
    private ProgressBar progress;
    private boolean overlayVisible;

    // ---------- state ----------
    private ArrayList<Store.Item> queue = new ArrayList<>();
    private int index = -1;
    private final ArrayList<String> addedSubs = new ArrayList<>();
    private int volume = 100;
    private boolean muted;
    private float rate = 1f;
    private long subDelayMs = 0;
    private long sleepAt = 0;
    private boolean sleepAfterItem;
    private boolean torrentUsed;
    private int resolveToken = 0;
    private int pendingAudio = Integer.MIN_VALUE, pendingSpu = Integer.MIN_VALUE;
    private long seekTarget = -1, seekAt = 0;
    private long lastSave = 0;
    private boolean ended;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            if (mp != null && !mp.isPlaying()) return; // keep it visible while paused
            panel.setVisibility(View.GONE);
            overlayVisible = false;
        }
    };
    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osdView.setVisibility(View.GONE);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            handler.postDelayed(this, 500);
        }
    };

    // =====================================================================
    // Lifecycle
    // =====================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Store.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        buildUi();
        volume = Store.getInt("vol", 100);
        try {
            createEngine();
        } catch (Throwable t) {
            Toast.makeText(this, "Could not start the video engine: " + t, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Remote.player = this;
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        savePosition();
        handleIntent(intent);
    }

    private void handleIntent(Intent in) {
        ArrayList<Store.Item> list = null;
        int start = in.getIntExtra(EXTRA_INDEX, 0);
        if (in.getBooleanExtra(EXTRA_HANDOFF, false)) {
            list = Remote.takeHandoff();
        } else if (in.getStringExtra(EXTRA_PLAYLIST) != null) {
            list = new ArrayList<>(Store.items(in.getStringExtra(EXTRA_PLAYLIST)));
        } else if (in.getData() != null) {
            Uri u = in.getData();
            String title = in.getStringExtra("title");
            Store.Item it = new Store.Item(title, u.toString(), "");
            if (!"content".equals(u.getScheme())) Store.addRecent(it);
            list = new ArrayList<>();
            list.add(it);
            start = 0;
        }
        if (list == null || list.isEmpty()) {
            if (queue.isEmpty()) finish();
            return;
        }
        queue = list;
        if (start < 0 || start >= queue.size()) start = 0;
        playIndex(start, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mp != null && !viewsAttached) {
            mp.attachViews(videoLayout, null, true, false);
            viewsAttached = true;
            applyFit();
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(tick);
        savePosition();
        if (mp != null) {
            mp.pause();
            if (viewsAttached) {
                mp.detachViews();
                viewsAttached = false;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (Remote.player == this) Remote.player = null;
        resolveToken++;
        releaseEngine();
        if (torrentUsed && isFinishing() && Remote.player == null) TorrentEngine.get(this).stopAsync();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // =====================================================================
    // UI
    // =====================================================================
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setShadowLayer(4, 0, 0, Color.BLACK);
        return t;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(true);

        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        loadingView = text(22, Color.WHITE);
        loadingView.setGravity(Gravity.CENTER);
        loadingView.setPadding(dp(24), dp(12), dp(24), dp(12));
        GradientDrawable lbg = new GradientDrawable();
        lbg.setColor(0xAA000000);
        lbg.setCornerRadius(dp(12));
        loadingView.setBackground(lbg);
        loadingView.setVisibility(View.GONE);
        root.addView(loadingView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        osdView = text(20, Color.WHITE);
        osdView.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable obg = new GradientDrawable();
        obg.setColor(0xCC1E2327);
        obg.setCornerRadius(dp(10));
        osdView.setBackground(obg);
        osdView.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        olp.setMargins(dp(32), dp(28), dp(32), dp(28));
        root.addView(osdView, olp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(48), dp(28), dp(48), dp(24));
        GradientDrawable pbg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000});
        panel.setBackground(pbg);

        titleView = text(24, Color.WHITE);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        panel.addView(titleView);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setFocusable(false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        plp.setMargins(0, dp(10), 0, dp(6));
        panel.addView(progress, plp);

        timeView = text(18, Color.WHITE);
        panel.addView(timeView);
        infoView = text(15, 0xFFB0B8C0);
        infoView.setPadding(0, dp(4), 0, 0);
        panel.addView(infoView);
        TextView hint = text(14, 0xFF8A9199);
        hint.setPadding(0, dp(6), 0, 0);
        hint.setText("OK pause · ◀ ▶ seek · ▼ / Menu options · ▲ info · CH+/CH− next/previous · Back exit");
        panel.addView(hint);

        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) applyZoom();
            }
        });
    }

    private void showOverlay() {
        panel.setVisibility(View.VISIBLE);
        overlayVisible = true;
        updateOverlay();
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, 5000);
    }

    private void osd(String msg) {
        osdView.setText(msg);
        osdView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 2500);
    }

    private void showLoading(String msg) {
        loadingView.setText(msg);
        loadingView.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingView.setVisibility(View.GONE);
    }

    private void updateOverlay() {
        if (mp == null) return;
        long t = mp.getTime(), len = mp.getLength();
        timeView.setText(len > 0 ? Net.fmtTime(t) + " / " + Net.fmtTime(len) + (mp.isPlaying() ? "" : "   ❚❚ Paused")
                : (mp.isPlaying() ? "● Live  " + Net.fmtTime(t) : "❚❚ Paused"));
        progress.setProgress(len > 0 ? (int) (1000 * Math.max(0, t) / len) : 0);
        StringBuilder sb = new StringBuilder();
        if (queue.size() > 1) sb.append(index + 1).append('/').append(queue.size()).append("   ");
        sb.append("Audio: ").append(trackName(mp.getAudioTracks(), mp.getAudioTrack()));
        sb.append("   Subs: ").append(trackName(mp.getSpuTracks(), mp.getSpuTrack()));
        sb.append("   Vol ").append(muted ? "muted" : volume + "%");
        if (rate != 1f) sb.append("   ").append(fmtRate(rate));
        if (sleepAt > 0 || sleepAfterItem) sb.append("   Sleep ").append(sleepLabel());
        String ts = torrentUsed ? TorrentEngine.get(this).shortStatus() : "";
        if (!ts.isEmpty()) sb.append("\n").append(ts);
        infoView.setText(sb.toString());
    }

    private void onTick() {
        if (overlayVisible) updateOverlay();
        if (loadingView.getVisibility() == View.VISIBLE && torrentUsed && mp != null && !mp.isPlaying()) {
            String ts = TorrentEngine.get(this).shortStatus();
            CharSequence cur = loadingView.getText();
            String base = cur.toString().split("\n")[0];
            if (!ts.isEmpty()) loadingView.setText(base + "\n" + ts);
        }
        if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
            sleepAt = 0;
            Toast.makeText(this, "Sleep timer: stopped", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (System.currentTimeMillis() - lastSave > 15000) savePosition();
    }

    // =====================================================================
    // Engine
    // =====================================================================
    private void createEngine() {
        ArrayList<String> o = new ArrayList<>();
        o.add("--network-caching=" + Store.getInt("cache", 2000));
        o.add("--http-reconnect");
        o.add("--audio-time-stretch");
        o.add("--freetype-rel-fontsize=" + Store.getInt("subsize", 16));
        o.add("--freetype-color=16777215");
        if (Store.getBool("subbg", false)) {
            o.add("--freetype-background-color=0");
            o.add("--freetype-background-opacity=170");
        } else {
            o.add("--freetype-background-opacity=0");
        }
        String enc = Store.getStr("subenc", "");
        if (!enc.isEmpty()) o.add("--subsdec-encoding=" + enc);
        libVLC = new LibVLC(this, o);
        mp = new MediaPlayer(libVLC);
        mp.setEventListener(events);
        mp.attachViews(videoLayout, null, true, false);
        viewsAttached = true;
        loadEqInfo();
        if (Store.getBool("eq_pending", false)) {
            Store.putBool("eq_pending", false);
            int p = Store.getInt("eq_preset", -1);
            if (p >= 0) loadPreset(p);
        }
        applyFit();
        applyZoom();
    }

    private void releaseEngine() {
        if (mp != null) {
            try {
                mp.setEventListener(null);
                mp.stop();
                if (viewsAttached) mp.detachViews();
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
            viewsAttached = false;
        }
        if (libVLC != null) {
            try {
                libVLC.release();
            } catch (Throwable ignored) {
            }
            libVLC = null;
        }
        closePfd();
    }

    /** Re-creates the engine (needed for subtitle style / buffer changes) and continues where it was. */
    private void restartEngine() {
        if (mp == null) return;
        long t = mp.getTime();
        int a = mp.getAudioTrack(), s = mp.getSpuTrack();
        ArrayList<String> subs = new ArrayList<>(addedSubs);
        long sd = subDelayMs;
        releaseEngine();
        createEngine();
        if (current() == null) return;
        startMedia(current(), Math.max(0, t), subs);
        subDelayMs = sd;
        pendingAudio = a;
        pendingSpu = s;
    }

    private void reloadCurrent() {
        if (mp == null || current() == null) return;
        long t = mp.getTime();
        int a = mp.getAudioTrack(), s = mp.getSpuTrack();
        ArrayList<String> subs = new ArrayList<>(addedSubs);
        long sd = subDelayMs;
        startMedia(current(), Math.max(0, t), subs);
        subDelayMs = sd;
        pendingAudio = a;
        pendingSpu = s;
    }

    private static void loadEqInfo() {
        if (PRESETS != null) return;
        try {
            int n = MediaPlayer.Equalizer.getPresetCount();
            String[] p = new String[n];
            for (int i = 0; i < n; i++) p[i] = MediaPlayer.Equalizer.getPresetName(i);
            int b = MediaPlayer.Equalizer.getBandCount();
            float[] f = new float[b];
            for (int i = 0; i < b; i++) f[i] = MediaPlayer.Equalizer.getBandFrequency(i);
            BAND_FREQS = f;
            PRESETS = p;
        } catch (Throwable ignored) {
        }
    }

    private Store.Item current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    private void playIndex(int i, boolean resume) {
        if (i < 0 || i >= queue.size() || mp == null) return;
        savePosition();
        index = i;
        Store.Item it = queue.get(i);
        if (Formats.isTorrent(it.u)) {
            resolveTorrent(i);
            return;
        }
        long start = resume ? Store.pos(it.u) : 0;
        if (start < 5000) start = 0;
        startMedia(it, start, null);
    }

    private void startMedia(Store.Item it, long startMs, List<String> keepSubs) {
        resolveToken++;
        ended = false;
        closePfd();
        addedSubs.clear();
        subDelayMs = 0;
        pendingAudio = Integer.MIN_VALUE;
        pendingSpu = Integer.MIN_VALUE;
        if (it.u.contains("127.0.0.1:" + RemoteService.PORT + "/torrent/")) torrentUsed = true;
        Media m;
        try {
            m = buildMedia(it.u);
        } catch (Exception e) {
            onPlayError("Cannot open: " + e.getMessage());
            return;
        }
        if (startMs > 0) m.addOption(String.format(Locale.US, ":start-time=%.2f", startMs / 1000.0));
        if (Store.getInt("hw", 0) == 1) m.setHWDecoderEnabled(false, false);
        if (it.u.startsWith("http")) {
            m.addOption(":http-user-agent=" + Net.UA);
        }
        mp.setMedia(m);
        m.release();
        if (keepSubs != null) for (String s : keepSubs) addSubtitle(s, false);
        if (!it.s.isEmpty()) loadSubtitleAsync(it.s);
        mp.play();
        applyAudio();
        titleView.setText(it.t);
        showLoading(startMs > 0 ? "Resuming at " + Net.fmtTime(startMs) + "…" : "Opening…");
        showOverlay();
    }

    private Media buildMedia(String url) throws Exception {
        if (url.startsWith("/")) return new Media(libVLC, url);
        Uri u = Uri.parse(url);
        if ("content".equals(u.getScheme())) {
            pfd = getContentResolver().openFileDescriptor(u, "r");
            if (pfd == null) throw new Exception("file not available");
            return new Media(libVLC, Uri.parse("fd://" + pfd.getFd()));
        }
        return new Media(libVLC, u);
    }

    private void closePfd() {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
            pfd = null;
        }
    }

    private void resolveTorrent(final int i) {
        final Store.Item it = queue.get(i);
        final int token = ++resolveToken;
        torrentUsed = true;
        if (mp != null) mp.stop();
        titleView.setText(it.t);
        showLoading("Getting torrent info…\n(this can take up to a minute)");
        showOverlay();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Store.Item> files = TorrentEngine.get(PlayerActivity.this).open(it.u);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != resolveToken || isFinishing() || i >= queue.size()) return;
                            queue.remove(i);
                            queue.addAll(i, files);
                            if (files.size() > 1) osd(files.size() + " videos in this torrent");
                            playIndex(i, true);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != resolveToken) return;
                            onPlayError("Torrent: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void onPlayError(String msg) {
        showLoading(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        final int token = resolveToken;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != resolveToken) return;
                if (index + 1 < queue.size()) playIndex(index + 1, true);
            }
        }, 4000);
    }

    private final MediaPlayer.EventListener events = new MediaPlayer.EventListener() {
        @Override
        public void onEvent(MediaPlayer.Event e) {
            switch (e.type) {
                case MediaPlayer.Event.Playing:
                    hideLoading();
                    applyAudio();
                    applyAspect();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            restoreTracks();
                        }
                    }, 800);
                    break;
                case MediaPlayer.Event.Paused:
                    showOverlay();
                    break;
                case MediaPlayer.Event.Buffering:
                    float b = e.getBuffering();
                    if (b < 100f) showLoading("Buffering " + (int) b + "%");
                    else hideLoading();
                    break;
                case MediaPlayer.Event.Vout:
                    applyAspect();
                    break;
                case MediaPlayer.Event.EndReached:
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            onEnded();
                        }
                    });
                    break;
                case MediaPlayer.Event.EncounteredError:
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Store.Item it = current();
                            onPlayError("Cannot play: " + (it == null ? "" : it.t));
                        }
                    });
                    break;
                default:
                    break;
            }
        }
    };

    private void onEnded() {
        if (ended) return;
        ended = true;
        Store.Item it = current();
        if (it != null) Store.setPos(it.u, 0);
        if (sleepAfterItem) {
            Toast.makeText(this, "Sleep timer: stopped", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (index + 1 < queue.size()) playIndex(index + 1, true);
        else finish();
    }

    private void restoreTracks() {
        if (mp == null) return;
        if (pendingAudio != Integer.MIN_VALUE) mp.setAudioTrack(pendingAudio);
        if (pendingSpu != Integer.MIN_VALUE) mp.setSpuTrack(pendingSpu);
        pendingAudio = Integer.MIN_VALUE;
        pendingSpu = Integer.MIN_VALUE;
        mp.setSpuDelay(subDelayMs * 1000);
    }

    private void savePosition() {
        lastSave = System.currentTimeMillis();
        Store.Item it = current();
        if (mp == null || it == null || ended) return;
        long pos = mp.getTime(), len = mp.getLength();
        if (len <= 0 || pos <= 0) return;
        boolean nearEnd = pos > len - 15000;
        Store.setPos(it.u, nearEnd ? 0 : pos);
    }

    // =====================================================================
    // Applying settings
    // =====================================================================
    private void applyAudio() {
        if (mp == null) return;
        mp.setVolume(muted ? 0 : volume);
        mp.setRate(rate);
        mp.setAudioDelay(Store.getInt("adelay", 0) * 1000L);
        applyEq();
    }

    private void applyEq() {
        if (mp == null) return;
        if (!Store.getBool("eq_on", false)) {
            mp.setEqualizer(null);
            return;
        }
        try {
            MediaPlayer.Equalizer eq = MediaPlayer.Equalizer.create();
            eq.setPreAmp(Store.getFloat("eq_preamp", 0f));
            float[] b = Store.getBands();
            int n = Math.min(b.length, MediaPlayer.Equalizer.getBandCount());
            for (int i = 0; i < n; i++) eq.setAmp(i, b[i]);
            mp.setEqualizer(eq);
        } catch (Throwable ignored) {
        }
    }

    private void loadPreset(int p) {
        try {
            MediaPlayer.Equalizer e = MediaPlayer.Equalizer.createFromPreset(p);
            float[] b = new float[Store.EQ_BANDS];
            int n = Math.min(b.length, MediaPlayer.Equalizer.getBandCount());
            for (int i = 0; i < n; i++) b[i] = e.getAmp(i);
            Store.putBands(b);
            Store.putFloat("eq_preamp", e.getPreAmp());
            Store.putInt("eq_preset", p);
            Store.putBool("eq_on", true);
        } catch (Throwable ignored) {
        }
        applyEq();
    }

    private void applyAspect() {
        if (mp == null) return;
        String a = Store.getStr("aspect", "");
        mp.setAspectRatio(a.isEmpty() ? null : a);
    }

    private void applyFit() {
        if (mp == null) return;
        int f = Store.getInt("fit", 0);
        if (f < 0 || f >= FITS.length) f = 0;
        mp.setVideoScale(FITS[f]);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyAspect();
            }
        }, 150);
    }

    /** Zoom/crop: makes the video area bigger than the screen; the screen edge cuts the rest. */
    private void applyZoom() {
        if (root == null || videoLayout == null) return;
        int z = Store.getInt("zoom", 100);
        int w = root.getWidth(), h = root.getHeight();
        FrameLayout.LayoutParams lp;
        if (z == 100 || w == 0 || h == 0) {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        } else {
            lp = new FrameLayout.LayoutParams(w * z / 100, h * z / 100, Gravity.CENTER);
        }
        videoLayout.setLayoutParams(lp);
    }

    // =====================================================================
    // Subtitles
    // =====================================================================
    private void addSubtitle(String pathOrUrl, boolean select) {
        if (mp == null) return;
        Uri u = pathOrUrl.startsWith("/") ? Uri.fromFile(new File(pathOrUrl)) : Uri.parse(pathOrUrl);
        mp.addSlave(IMedia.Slave.Type.Subtitle, u, select);
        if (!addedSubs.contains(pathOrUrl)) addedSubs.add(pathOrUrl);
    }

    private void loadSubtitleAsync(final String url) {
        if (url.startsWith("/") || url.startsWith("file:")) {
            addSubtitle(url, true);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File f = Net.downloadSubtitle(PlayerActivity.this, url);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            addSubtitle(f.getAbsolutePath(), true);
                            osd("Subtitle loaded");
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            osd("Subtitle failed: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // =====================================================================
    // Keys (TV remote)
    // =====================================================================
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && handleKey(event.getKeyCode(), event.getRepeatCount())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public boolean handleKey(int code, int repeat) {
        if (mp == null) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (repeat == 0) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                mp.play();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                mp.pause();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(repeat >= 3 ? -30000 : -10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(repeat >= 3 ? 30000 : 10000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-30000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(30000);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (repeat == 0) {
                    if (overlayVisible) {
                        handler.removeCallbacks(hideOverlay);
                        hideOverlay.run();
                    } else showOverlay();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                if (repeat == 0) showMenu();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (repeat == 0) next();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (repeat == 0) prev();
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
            case KeyEvent.KEYCODE_PROG_YELLOW:
                if (repeat == 0) cycleSubs();
                return true;
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
            case KeyEvent.KEYCODE_PROG_GREEN:
                if (repeat == 0) cycleAudio();
                return true;
            case KeyEvent.KEYCODE_PROG_RED:
                if (repeat == 0) {
                    int f = (Store.getInt("fit", 0) + 1) % FITS.length;
                    command("fit", String.valueOf(f), "");
                }
                return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
                if (repeat == 0) {
                    int z = Store.getInt("zoom", 100), k = 0;
                    for (int i = 0; i < ZOOM_VALUES.length; i++) if (ZOOM_VALUES[i] == z) k = i;
                    command("zoom", String.valueOf(ZOOM_VALUES[(k + 1) % ZOOM_VALUES.length]), "");
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_BACK:
                if (repeat == 0) finish();
                return true;
            default:
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && repeat == 0) {
                    long len = mp.getLength();
                    if (len > 0 && mp.isSeekable()) {
                        int pct = (code - KeyEvent.KEYCODE_0) * 10;
                        seekTo(len * pct / 100);
                        osd("Jump to " + pct + "%");
                    }
                    return true;
                }
                return false;
        }
    }

    private void togglePause() {
        if (mp.isPlaying()) mp.pause();
        else if (ended && current() != null) playIndex(index, false);
        else mp.play();
        showOverlay();
    }

    private void seekBy(long delta) {
        long now = System.currentTimeMillis();
        long base = (seekTarget >= 0 && now - seekAt < 1500) ? seekTarget : mp.getTime();
        seekTo(base + delta);
        osd((delta > 0 ? "+" : "−") + (Math.abs(delta) / 1000) + " s");
    }

    private void seekTo(long t) {
        if (!mp.isSeekable()) {
            osd("This stream cannot be seeked");
            return;
        }
        long len = mp.getLength();
        if (len > 0) t = Math.min(t, len - 1000);
        t = Math.max(0, t);
        seekTarget = t;
        seekAt = System.currentTimeMillis();
        mp.setTime(t);
        showOverlay();
    }

    private void next() {
        if (index + 1 < queue.size()) playIndex(index + 1, true);
        else osd("This is the last item");
    }

    private void prev() {
        if (mp.getTime() > 10000 && mp.isSeekable()) {
            seekTo(0);
            return;
        }
        if (index > 0) playIndex(index - 1, true);
        else seekTo(0);
    }

    private void cycleSubs() {
        MediaPlayer.TrackDescription[] t = mp.getSpuTracks();
        if (t == null || t.length == 0) {
            osd("No subtitles in this video");
            return;
        }
        int cur = mp.getSpuTrack(), k = -1;
        for (int i = 0; i < t.length; i++) if (t[i].id == cur) k = i;
        MediaPlayer.TrackDescription n = t[(k + 1) % t.length];
        mp.setSpuTrack(n.id);
        osd("Subtitles: " + niceName(n));
    }

    private void cycleAudio() {
        MediaPlayer.TrackDescription[] t = mp.getAudioTracks();
        if (t == null || t.length == 0) return;
        int cur = mp.getAudioTrack(), k = -1;
        for (int i = 0; i < t.length; i++) if (t[i].id == cur) k = i;
        for (int step = 1; step <= t.length; step++) {
            MediaPlayer.TrackDescription n = t[(k + step) % t.length];
            if (n.id < 0) continue; // skip "Disable"
            mp.setAudioTrack(n.id);
            osd("Audio: " + niceName(n));
            return;
        }
    }

    // =====================================================================
    // Menus on the TV
    // =====================================================================
    private interface IntCb {
        void on(int i);
    }

    private interface FloatCb {
        void on(float v);
    }

    private interface Fmt {
        String f(float v);
    }

    private void choose(String title, String[] items, int checked, final IntCb cb) {
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        cb.on(w);
                    }
                }).show();
    }

    /** A value you change with ◀ ▶ on the remote. */
    private void stepper(String title, float value, final float step, final float min, final float max,
                         final float reset, final Fmt fmt, final FloatCb cb) {
        final float[] cur = {value};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final TextView tv = new TextView(this);
        tv.setTextSize(32);
        tv.setGravity(Gravity.CENTER);
        tv.setText("◀  " + fmt.f(value) + "  ▶");
        box.addView(tv);
        TextView hint = new TextView(this);
        hint.setGravity(Gravity.CENTER);
        hint.setText("Use ◀ ▶ on the remote to change");
        box.addView(hint);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        cb.on(reset);
                    }
                }).create();
        d.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface di, int code, KeyEvent ev) {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
                float n = cur[0] + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? step : -step);
                n = Math.max(min, Math.min(max, n));
                n = Math.round(n * 1000f) / 1000f;
                cur[0] = n;
                tv.setText("◀  " + fmt.f(n) + "  ▶");
                cb.on(n);
                return true;
            }
        });
        d.show();
    }

    private void showMenu() {
        if (mp == null) return;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Audio track: " + trackName(mp.getAudioTracks(), mp.getAudioTrack()));
        acts.add(new Runnable() { public void run() { menuAudio(); } });
        labels.add("Subtitles: " + trackName(mp.getSpuTracks(), mp.getSpuTrack()));
        acts.add(new Runnable() { public void run() { menuSubs(); } });
        labels.add("Aspect ratio: " + ASPECT_NAMES[aspectIndex()]);
        acts.add(new Runnable() { public void run() { menuAspect(); } });
        labels.add("Picture fit: " + FIT_NAMES[Math.max(0, Math.min(3, Store.getInt("fit", 0)))]);
        acts.add(new Runnable() { public void run() { menuFit(); } });
        labels.add("Zoom / crop: " + Store.getInt("zoom", 100) + "%");
        acts.add(new Runnable() { public void run() { menuZoom(); } });
        labels.add("Equalizer: " + eqLabel());
        acts.add(new Runnable() { public void run() { menuEq(); } });
        labels.add("Volume / boost: " + (muted ? "muted" : volume + "%"));
        acts.add(new Runnable() { public void run() { menuVolume(); } });
        labels.add("Playback speed: " + fmtRate(rate));
        acts.add(new Runnable() { public void run() { menuSpeed(); } });
        labels.add("Audio delay: " + fmtMs(Store.getInt("adelay", 0)));
        acts.add(new Runnable() { public void run() { menuAudioDelay(); } });
        labels.add("Subtitle delay: " + fmtMs(subDelayMs));
        acts.add(new Runnable() { public void run() { menuSubDelay(); } });
        labels.add("Subtitle style (size, background, language encoding)");
        acts.add(new Runnable() { public void run() { menuSubStyle(); } });
        labels.add("Sleep timer: " + sleepLabel());
        acts.add(new Runnable() { public void run() { menuSleep(); } });
        labels.add("Decoder & buffer");
        acts.add(new Runnable() { public void run() { menuEngine(); } });
        if (queue.size() > 1) {
            labels.add("Playlist (" + (index + 1) + "/" + queue.size() + ")…");
            acts.add(new Runnable() { public void run() { menuQueue(); } });
            labels.add("Next item");
            acts.add(new Runnable() { public void run() { next(); } });
            labels.add("Previous item");
            acts.add(new Runnable() { public void run() { prev(); } });
        }
        if (torrentUsed && TorrentEngine.get(this).isActive()) {
            labels.add("Stop torrent and delete its data");
            acts.add(new Runnable() { public void run() { TorrentEngine.get(PlayerActivity.this).stopAsync(); finish(); } });
        }
        labels.add("Phone remote: http://" + Net.ip() + ":" + RemoteService.PORT);
        acts.add(new Runnable() { public void run() { osd("Open http://" + Net.ip() + ":" + RemoteService.PORT + " on your phone"); } });

        new AlertDialog.Builder(this).setTitle("Options")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        acts.get(w).run();
                    }
                }).show();
    }

    private void menuAudio() {
        final MediaPlayer.TrackDescription[] t = mp.getAudioTracks();
        if (t == null || t.length == 0) {
            osd("No audio tracks");
            return;
        }
        String[] names = new String[t.length];
        int sel = -1;
        for (int i = 0; i < t.length; i++) {
            names[i] = niceName(t[i]);
            if (t[i].id == mp.getAudioTrack()) sel = i;
        }
        choose("Audio track", names, sel, new IntCb() {
            public void on(int i) {
                command("audio", String.valueOf(t[i].id), "");
            }
        });
    }

    private void menuSubs() {
        final List<Integer> ids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        MediaPlayer.TrackDescription[] t = mp.getSpuTracks();
        boolean hasOff = false;
        if (t != null) for (MediaPlayer.TrackDescription d : t) if (d.id < 0) hasOff = true;
        if (!hasOff) {
            ids.add(-1);
            names.add("Off");
        }
        int sel = -1;
        if (t != null) {
            for (MediaPlayer.TrackDescription d : t) {
                if (d.id == mp.getSpuTrack()) sel = ids.size();
                ids.add(d.id);
                names.add(niceName(d));
            }
        }
        if (sel < 0 && mp.getSpuTrack() < 0) sel = 0;
        final int extra = ids.size();
        names.add("➕ Load a subtitle file from your phone…");
        names.add("Subtitle delay…");
        names.add("Subtitle style…");
        choose("Subtitles", names.toArray(new String[0]), sel, new IntCb() {
            public void on(int i) {
                if (i < extra) command("sub", String.valueOf(ids.get(i)), "");
                else if (i == extra) new AlertDialog.Builder(PlayerActivity.this).setTitle("Load subtitles")
                        .setMessage("On your phone open\n\nhttp://" + Net.ip() + ":" + RemoteService.PORT
                                + "\n\nthen go to “Audio & Subs” and upload a .srt/.ass file or paste a subtitle link.")
                        .setPositiveButton("OK", null).show();
                else if (i == extra + 1) menuSubDelay();
                else menuSubStyle();
            }
        });
    }

    private void menuAspect() {
        choose("Aspect ratio", ASPECT_NAMES, aspectIndex(), new IntCb() {
            public void on(int i) {
                command("aspect", ASPECT_VALUES[i], "");
            }
        });
    }

    private void menuFit() {
        choose("Picture fit", FIT_NAMES, Store.getInt("fit", 0), new IntCb() {
            public void on(int i) {
                command("fit", String.valueOf(i), "");
            }
        });
    }

    private void menuZoom() {
        String[] names = new String[ZOOM_NAMES.length + 1];
        System.arraycopy(ZOOM_NAMES, 0, names, 0, ZOOM_NAMES.length);
        names[ZOOM_NAMES.length] = "Custom…";
        int z = Store.getInt("zoom", 100), sel = -1;
        for (int i = 0; i < ZOOM_VALUES.length; i++) if (ZOOM_VALUES[i] == z) sel = i;
        choose("Zoom / crop to fill the screen", names, sel, new IntCb() {
            public void on(int i) {
                if (i < ZOOM_VALUES.length) {
                    command("zoom", String.valueOf(ZOOM_VALUES[i]), "");
                    return;
                }
                stepper("Zoom", Store.getInt("zoom", 100), 5, 50, 300, 100, new Fmt() {
                    public String f(float v) {
                        return Math.round(v) + "%";
                    }
                }, new FloatCb() {
                    public void on(float v) {
                        command("zoom", String.valueOf(Math.round(v)), "");
                    }
                });
            }
        });
    }

    private void menuVolume() {
        String[] names = new String[VOLS.length + 2];
        int sel = -1;
        for (int i = 0; i < VOLS.length; i++) {
            names[i] = VOLS[i] + "%" + (VOLS[i] > 100 ? "  (boost)" : "");
            if (VOLS[i] == volume) sel = i;
        }
        names[VOLS.length] = muted ? "Unmute" : "Mute";
        names[VOLS.length + 1] = "Custom…";
        choose("Volume / boost (up to 200%)", names, sel, new IntCb() {
            public void on(int i) {
                if (i < VOLS.length) command("vol", String.valueOf(VOLS[i]), "");
                else if (i == VOLS.length) command("mute", "", "");
                else stepper("Volume", volume, 5, 0, 200, 100, new Fmt() {
                    public String f(float v) {
                        return Math.round(v) + "%";
                    }
                }, new FloatCb() {
                    public void on(float v) {
                        command("vol", String.valueOf(Math.round(v)), "");
                    }
                });
            }
        });
    }

    private void menuSpeed() {
        String[] names = new String[SPEEDS.length];
        int sel = -1;
        for (int i = 0; i < SPEEDS.length; i++) {
            names[i] = fmtRate(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - rate) < 0.01f) sel = i;
        }
        choose("Playback speed", names, sel, new IntCb() {
            public void on(int i) {
                command("rate", String.valueOf(SPEEDS[i]), "");
            }
        });
    }

    private void menuAudioDelay() {
        stepper("Audio delay (fix lip-sync)", Store.getInt("adelay", 0), 50, -10000, 10000, 0, new Fmt() {
            public String f(float v) {
                return fmtMs((long) v);
            }
        }, new FloatCb() {
            public void on(float v) {
                command("adelay", String.valueOf(Math.round(v)), "");
            }
        });
    }

    private void menuSubDelay() {
        stepper("Subtitle delay", subDelayMs, 100, -60000, 60000, 0, new Fmt() {
            public String f(float v) {
                return fmtMs((long) v);
            }
        }, new FloatCb() {
            public void on(float v) {
                command("sdelay", String.valueOf(Math.round(v)), "");
            }
        });
    }

    private void menuSubStyle() {
        String[] items = {"Size: " + SUB_SIZE_NAMES[subSizeIndex()],
                "Background box: " + (Store.getBool("subbg", false) ? "On" : "Off"),
                "Text encoding: " + ENC_NAMES[encIndex()]};
        new AlertDialog.Builder(this).setTitle("Subtitle style")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) choose("Subtitle size", SUB_SIZE_NAMES, subSizeIndex(), new IntCb() {
                            public void on(int i) {
                                command("subsize", String.valueOf(SUB_SIZES[i]), "");
                            }
                        });
                        else if (w == 1) command("subbg", Store.getBool("subbg", false) ? "0" : "1", "");
                        else choose("Subtitle text encoding", ENC_NAMES, encIndex(), new IntCb() {
                            public void on(int i) {
                                command("subenc", ENC_VALUES[i], "");
                            }
                        });
                    }
                }).show();
    }

    private void menuSleep() {
        String[] names = new String[SLEEPS.length];
        for (int i = 0; i < SLEEPS.length; i++) {
            names[i] = SLEEPS[i] == 0 ? "Off" : SLEEPS[i] < 0 ? "At the end of this video" : SLEEPS[i] + " minutes";
        }
        choose("Sleep timer", names, -1, new IntCb() {
            public void on(int i) {
                command("sleep", String.valueOf(SLEEPS[i]), "");
            }
        });
    }

    private void menuEngine() {
        String[] items = {"Decoder: " + (Store.getInt("hw", 0) == 1 ? "Software (slower, most compatible)" : "Hardware (auto)"),
                "Network buffer: " + Store.getInt("cache", 2000) / 1000f + " s"};
        new AlertDialog.Builder(this).setTitle("Decoder & buffer")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            command("hw", Store.getInt("hw", 0) == 1 ? "0" : "1", "");
                        } else {
                            String[] n = new String[CACHES.length];
                            int sel = -1;
                            for (int i = 0; i < CACHES.length; i++) {
                                n[i] = (CACHES[i] / 1000f) + " seconds";
                                if (CACHES[i] == Store.getInt("cache", 2000)) sel = i;
                            }
                            choose("Network buffer (bigger = fewer stalls)", n, sel, new IntCb() {
                                public void on(int i) {
                                    command("cache", String.valueOf(CACHES[i]), "");
                                }
                            });
                        }
                    }
                }).show();
    }

    private void menuQueue() {
        String[] names = new String[queue.size()];
        for (int i = 0; i < queue.size(); i++) names[i] = (i + 1) + ". " + queue.get(i).t;
        choose("Playlist", names, index, new IntCb() {
            public void on(int i) {
                playIndex(i, true);
            }
        });
    }

    /** Equalizer: on/off, presets, preamp and 10 bands (move with ▲▼, change with ◀ ▶). */
    private void menuEq() {
        loadEqInfo();
        final String[] presets = PRESETS != null ? PRESETS : DEFAULT_PRESETS;
        final float[] freqs = BAND_FREQS != null ? BAND_FREQS : DEFAULT_FREQS;
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        sv.addView(box);

        final CheckBox on = new CheckBox(this);
        on.setText("Equalizer on");
        on.setTextSize(18);
        on.setChecked(Store.getBool("eq_on", false));
        on.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                Store.putBool("eq_on", c);
                applyEq();
            }
        });
        box.addView(on);

        final Button preset = new Button(this);
        preset.setText("Preset: " + eqPresetName());
        box.addView(preset);

        final List<SeekBar> bars = new ArrayList<>();
        bars.add(eqBar(box, "Preamp", -1, on, preset));
        int n = Math.min(Store.EQ_BANDS, freqs.length);
        for (int i = 0; i < n; i++) bars.add(eqBar(box, freqLabel(freqs[i]), i, on, preset));

        preset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Equalizer preset", presets, Store.getInt("eq_preset", -1), new IntCb() {
                    public void on(int i) {
                        loadPreset(i);
                        on.setChecked(true);
                        preset.setText("Preset: " + eqPresetName());
                        refreshEqBars(bars);
                    }
                });
            }
        });

        Button reset = new Button(this);
        reset.setText("Reset (flat)");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                command("eq_reset", "", "");
                preset.setText("Preset: " + eqPresetName());
                refreshEqBars(bars);
            }
        });
        box.addView(reset);

        new AlertDialog.Builder(this).setTitle("Equalizer").setView(sv).setPositiveButton("Done", null).show();
    }

    private SeekBar eqBar(LinearLayout box, final String name, final int band, final CheckBox on, final Button preset) {
        float val = band < 0 ? Store.getFloat("eq_preamp", 0f) : Store.getBands()[band];
        final TextView lab = new TextView(this);
        lab.setText(name + ":  " + fmtDb(val));
        lab.setPadding(0, dp(8), 0, 0);
        box.addView(lab);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(400);
        sb.setKeyProgressIncrement(10);
        sb.setProgress(Math.round((val + 20f) * 10f));
        sb.setTag(band);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float db = p / 10f - 20f;
                lab.setText(name + ":  " + fmtDb(db));
                if (!fromUser) return;
                if (band < 0) command("eq_preamp", String.valueOf(db), "");
                else command("eq_band", String.valueOf(db), String.valueOf(band));
                on.setChecked(true);
                preset.setText("Preset: " + eqPresetName());
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb);
        return sb;
    }

    private void refreshEqBars(List<SeekBar> bars) {
        float[] b = Store.getBands();
        for (SeekBar sb : bars) {
            int band = (Integer) sb.getTag();
            float v = band < 0 ? Store.getFloat("eq_preamp", 0f) : b[band];
            sb.setProgress(Math.round((v + 20f) * 10f));
        }
    }

    // =====================================================================
    // Commands (from the TV menus and the phone remote)
    // =====================================================================

    /** Stores a setting. Works even when nothing is playing. Returns a message, or null if not a setting. */
    public static String applySetting(String c, String v, String b) {
        try {
            switch (c) {
                case "vol": {
                    int x = Math.max(0, Math.min(200, Math.round(Float.parseFloat(v))));
                    Store.putInt("vol", x);
                    return "Volume " + x + "%";
                }
                case "aspect":
                    for (int i = 0; i < ASPECT_VALUES.length; i++) {
                        if (ASPECT_VALUES[i].equals(v)) {
                            Store.putStr("aspect", v);
                            return "Aspect ratio: " + ASPECT_NAMES[i];
                        }
                    }
                    return "Unknown aspect ratio";
                case "fit": {
                    int f = Math.max(0, Math.min(FITS.length - 1, Integer.parseInt(v)));
                    Store.putInt("fit", f);
                    return FIT_NAMES[f];
                }
                case "zoom": {
                    int z = Math.max(50, Math.min(300, Math.round(Float.parseFloat(v))));
                    Store.putInt("zoom", z);
                    return "Zoom " + z + "%";
                }
                case "eq_on":
                    Store.putBool("eq_on", "1".equals(v) || "true".equals(v));
                    return "Equalizer " + (Store.getBool("eq_on", false) ? "on" : "off");
                case "eq_preset": {
                    int p = Integer.parseInt(v);
                    Store.putInt("eq_preset", p);
                    Store.putBool("eq_on", true);
                    Store.putBool("eq_pending", true);
                    return "Equalizer preset set";
                }
                case "eq_band": {
                    int band = Integer.parseInt(b);
                    float[] bands = Store.getBands();
                    if (band < 0 || band >= bands.length) return "Bad band";
                    bands[band] = Math.max(-20f, Math.min(20f, Float.parseFloat(v)));
                    Store.putBands(bands);
                    Store.putInt("eq_preset", -1);
                    Store.putBool("eq_on", true);
                    Store.putBool("eq_pending", false);
                    return "Band " + (band + 1) + ": " + fmtDb(bands[band]);
                }
                case "eq_preamp": {
                    float x = Math.max(-20f, Math.min(20f, Float.parseFloat(v)));
                    Store.putFloat("eq_preamp", x);
                    Store.putInt("eq_preset", -1);
                    Store.putBool("eq_on", true);
                    Store.putBool("eq_pending", false);
                    return "Preamp " + fmtDb(x);
                }
                case "eq_reset":
                    Store.putBands(new float[Store.EQ_BANDS]);
                    Store.putFloat("eq_preamp", 0f);
                    Store.putInt("eq_preset", 0);
                    Store.putBool("eq_pending", false);
                    return "Equalizer reset";
                case "subsize":
                    Store.putInt("subsize", Integer.parseInt(v));
                    return "Subtitle size changed";
                case "subbg":
                    Store.putBool("subbg", "1".equals(v) || "true".equals(v));
                    return "Subtitle background " + (Store.getBool("subbg", false) ? "on" : "off");
                case "subenc":
                    Store.putStr("subenc", v);
                    return "Subtitle encoding: " + (v.isEmpty() ? "automatic" : v);
                case "hw":
                    Store.putInt("hw", "1".equals(v) ? 1 : 0);
                    return "Decoder: " + ("1".equals(v) ? "software" : "hardware");
                case "cache":
                    Store.putInt("cache", Math.max(300, Math.min(60000, Integer.parseInt(v))));
                    return "Network buffer " + Store.getInt("cache", 2000) / 1000f + " s";
                case "adelay":
                    Store.putInt("adelay", Math.max(-30000, Math.min(30000, Math.round(Float.parseFloat(v)))));
                    return "Audio delay " + fmtMs(Store.getInt("adelay", 0));
                default:
                    return null;
            }
        } catch (Exception e) {
            return "Bad value";
        }
    }

    /** Runs a command on the TV (main thread). */
    public String command(String c, String v, String b) {
        if (mp == null) return "Player is not ready";
        String setting = applySetting(c, v, b);
        String msg = setting;
        switch (c) {
            // ---- settings that need applying ----
            case "vol":
                volume = Store.getInt("vol", 100);
                muted = false;
                applyAudio();
                break;
            case "aspect":
                applyAspect();
                break;
            case "fit":
                applyFit();
                break;
            case "zoom":
                applyZoom();
                break;
            case "eq_on": case "eq_band": case "eq_preamp": case "eq_reset":
                applyEq();
                break;
            case "eq_preset":
                Store.putBool("eq_pending", false);
                loadPreset(Store.getInt("eq_preset", 0));
                msg = "Equalizer: " + eqPresetName();
                break;
            case "subsize": case "subbg": case "subenc": case "cache":
                restartEngine();
                break;
            case "hw":
                reloadCurrent();
                break;
            case "adelay":
                mp.setAudioDelay(Store.getInt("adelay", 0) * 1000L);
                break;

            // ---- player actions ----
            case "toggle": {
                boolean was = mp.isPlaying();
                togglePause();
                msg = was ? "Paused" : "Playing";
                break;
            }
            case "play":
                mp.play();
                break;
            case "pause":
                mp.pause();
                break;
            case "stop":
                finish();
                msg = "Stopped";
                break;
            case "next":
                next();
                break;
            case "prev":
                prev();
                break;
            case "item":
                playIndex(Integer.parseInt(v), true);
                break;
            case "seek":
                seekTo(Long.parseLong(v));
                break;
            case "jump":
                seekBy(Long.parseLong(v));
                break;
            case "mute":
                muted = !muted;
                applyAudio();
                msg = muted ? "Muted" : "Volume " + volume + "%";
                break;
            case "audio":
                mp.setAudioTrack(Integer.parseInt(v));
                msg = "Audio: " + trackName(mp.getAudioTracks(), Integer.parseInt(v));
                break;
            case "sub":
                mp.setSpuTrack(Integer.parseInt(v));
                msg = "Subtitles: " + (Integer.parseInt(v) < 0 ? "Off" : trackName(mp.getSpuTracks(), Integer.parseInt(v)));
                break;
            case "sdelay":
                subDelayMs = Math.max(-600000, Math.min(600000, Math.round(Float.parseFloat(v))));
                mp.setSpuDelay(subDelayMs * 1000);
                msg = "Subtitle delay " + fmtMs(subDelayMs);
                break;
            case "rate":
                rate = Math.max(0.25f, Math.min(4f, Float.parseFloat(v)));
                mp.setRate(rate);
                msg = "Speed " + fmtRate(rate);
                break;
            case "sleep": {
                int m = Integer.parseInt(v);
                sleepAfterItem = m < 0;
                sleepAt = m > 0 ? System.currentTimeMillis() + m * 60000L : 0;
                msg = "Sleep timer: " + sleepLabel();
                break;
            }
            case "subfile":
                addSubtitle(v, true);
                msg = "Subtitle loaded";
                break;
            case "menu":
                showMenu();
                msg = "";
                break;
            case "osd":
                showOverlay();
                msg = "";
                break;
            default:
                if (setting == null) return "Unknown command";
        }
        if (msg != null && !msg.isEmpty()) osd(msg);
        return msg == null ? "" : msg;
    }

    public void enqueue(Store.Item it) {
        queue.add(it);
        osd("Added to queue: " + it.t);
    }

    // =====================================================================
    // State for the phone page
    // =====================================================================
    public JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        o.put("player", mp != null);
        if (mp == null) return o;
        Store.Item it = current();
        o.put("title", it == null ? "" : it.t);
        o.put("idx", index);
        o.put("count", queue.size());
        o.put("pos", Math.max(0, mp.getTime()));
        o.put("len", Math.max(0, mp.getLength()));
        o.put("playing", mp.isPlaying());
        o.put("seekable", mp.isSeekable());
        o.put("vol", volume);
        o.put("muted", muted);
        o.put("rate", Math.round(rate * 100) / 100.0);
        o.put("sdelay", subDelayMs);
        o.put("audio", tracksJson(mp.getAudioTracks(), false));
        o.put("audioSel", mp.getAudioTrack());
        o.put("subs", tracksJson(mp.getSpuTracks(), true));
        o.put("subSel", mp.getSpuTrack());
        o.put("sleep", sleepLabel());
        o.put("loading", loadingView.getVisibility() == View.VISIBLE ? loadingView.getText().toString() : "");
        JSONArray q = new JSONArray();
        for (int i = 0; i < queue.size() && i < 500; i++) q.put(queue.get(i).t);
        o.put("queue", q);
        return o;
    }

    /** Settings that the phone page shows even when nothing plays. */
    public static void putSettings(JSONObject o) throws Exception {
        if (!o.has("vol")) o.put("vol", Store.getInt("vol", 100));
        o.put("aspect", Store.getStr("aspect", ""));
        o.put("fit", Store.getInt("fit", 0));
        o.put("zoom", Store.getInt("zoom", 100));
        o.put("subsize", Store.getInt("subsize", 16));
        o.put("subbg", Store.getBool("subbg", false));
        o.put("subenc", Store.getStr("subenc", ""));
        o.put("hw", Store.getInt("hw", 0));
        o.put("cache", Store.getInt("cache", 2000));
        o.put("adelay", Store.getInt("adelay", 0));
        JSONObject eq = new JSONObject();
        eq.put("on", Store.getBool("eq_on", false));
        eq.put("preset", Store.getInt("eq_preset", -1));
        eq.put("preamp", Math.round(Store.getFloat("eq_preamp", 0f) * 10) / 10.0);
        JSONArray bands = new JSONArray();
        float[] b = Store.getBands();
        float[] f = BAND_FREQS != null ? BAND_FREQS : DEFAULT_FREQS;
        for (int i = 0; i < b.length && i < f.length; i++) {
            bands.put(new JSONObject().put("f", freqLabel(f[i])).put("v", Math.round(b[i] * 10) / 10.0));
        }
        eq.put("bands", bands);
        o.put("eq", eq);
    }

    public static JSONObject optionsJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("aspects", pairs(ASPECT_VALUES, ASPECT_NAMES));
        String[] fitVals = new String[FIT_NAMES.length];
        for (int i = 0; i < fitVals.length; i++) fitVals[i] = String.valueOf(i);
        o.put("fits", pairs(fitVals, FIT_NAMES));
        String[] z = new String[ZOOM_VALUES.length];
        for (int i = 0; i < z.length; i++) z[i] = String.valueOf(ZOOM_VALUES[i]);
        o.put("zooms", pairs(z, ZOOM_NAMES));
        String[] ss = new String[SUB_SIZES.length];
        for (int i = 0; i < ss.length; i++) ss[i] = String.valueOf(SUB_SIZES[i]);
        o.put("subsizes", pairs(ss, SUB_SIZE_NAMES));
        o.put("encs", pairs(ENC_VALUES, ENC_NAMES));
        JSONArray sp = new JSONArray();
        for (float s : SPEEDS) sp.put(Math.round(s * 100) / 100.0);
        o.put("speeds", sp);
        JSONArray ca = new JSONArray();
        for (int c : CACHES) ca.put(c);
        o.put("caches", ca);
        JSONArray sl = new JSONArray();
        for (int s : SLEEPS) sl.put(s);
        o.put("sleeps", sl);
        String[] pr = PRESETS != null ? PRESETS : DEFAULT_PRESETS;
        o.put("presets", new JSONArray(java.util.Arrays.asList(pr)));
        return o;
    }

    private static JSONArray pairs(String[] vals, String[] names) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < vals.length; i++) {
            JSONArray p = new JSONArray();
            p.put(vals[i]);
            p.put(names[i]);
            a.put(p);
        }
        return a;
    }

    private static JSONArray tracksJson(MediaPlayer.TrackDescription[] t, boolean addOff) throws Exception {
        JSONArray a = new JSONArray();
        boolean hasOff = false;
        if (t != null) for (MediaPlayer.TrackDescription d : t) if (d.id < 0) hasOff = true;
        if (addOff && !hasOff) a.put(new JSONObject().put("id", -1).put("name", "Off"));
        if (t != null) for (MediaPlayer.TrackDescription d : t) {
            a.put(new JSONObject().put("id", d.id).put("name", niceName(d)));
        }
        return a;
    }

    // =====================================================================
    // Small helpers
    // =====================================================================
    private static String niceName(MediaPlayer.TrackDescription d) {
        if (d.id < 0) return "Off";
        return d.name == null || d.name.isEmpty() ? "Track " + d.id : d.name;
    }

    private static String trackName(MediaPlayer.TrackDescription[] t, int id) {
        if (id < 0) return "Off";
        if (t != null) for (MediaPlayer.TrackDescription d : t) if (d.id == id) return niceName(d);
        return "—";
    }

    private int aspectIndex() {
        String a = Store.getStr("aspect", "");
        for (int i = 0; i < ASPECT_VALUES.length; i++) if (ASPECT_VALUES[i].equals(a)) return i;
        return 0;
    }

    private int subSizeIndex() {
        int s = Store.getInt("subsize", 16);
        for (int i = 0; i < SUB_SIZES.length; i++) if (SUB_SIZES[i] == s) return i;
        return 2;
    }

    private int encIndex() {
        String e = Store.getStr("subenc", "");
        for (int i = 0; i < ENC_VALUES.length; i++) if (ENC_VALUES[i].equals(e)) return i;
        return 0;
    }

    private String eqPresetName() {
        int p = Store.getInt("eq_preset", -1);
        String[] pr = PRESETS != null ? PRESETS : DEFAULT_PRESETS;
        return p >= 0 && p < pr.length ? pr[p] : "Custom";
    }

    private String eqLabel() {
        return Store.getBool("eq_on", false) ? eqPresetName() : "Off";
    }

    private String sleepLabel() {
        if (sleepAfterItem) return "end of video";
        if (sleepAt <= 0) return "Off";
        long left = Math.max(0, sleepAt - System.currentTimeMillis());
        return (left / 60000 + 1) + " min";
    }

    private static String fmtMs(long ms) {
        return (ms > 0 ? "+" : "") + String.format(Locale.US, "%.2f s", ms / 1000f);
    }

    private static String fmtRate(float r) {
        return String.format(Locale.US, "%.2fx", r).replace(".00x", ".0x");
    }

    static String fmtDb(float v) {
        return String.format(Locale.US, "%+.1f dB", v);
    }

    static String freqLabel(float f) {
        return f >= 1000 ? String.format(Locale.US, "%.0f kHz", f / 1000f) : Math.round(f) + " Hz";
    }
}
