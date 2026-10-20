package com.mytv.player;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionHandle;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Streams magnet links / .torrent files: downloads pieces in order and serves the
 * video to the player through http://127.0.0.1:8080/torrent/... while it downloads.
 * Only one torrent is active at a time; its data is deleted when you stop it.
 */
public final class TorrentEngine {
    private static TorrentEngine inst;

    public static synchronized TorrentEngine get(Context c) {
        if (inst == null) inst = new TorrentEngine(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private SessionManager session;
    private volatile TorrentHandle handle;
    private volatile TorrentInfo info;
    private volatile String hash = "";
    private volatile int activeFile = -1;
    private volatile String state = "idle";
    private volatile String name = "";
    private volatile int openToken = 0;

    private TorrentEngine(Context c) {
        ctx = c;
    }

    private synchronized SessionManager session() {
        if (session == null) {
            SettingsPack sp = new SettingsPack();
            sp.connectionsLimit(200);
            sp.activeDownloads(3);
            SessionManager s = new SessionManager(false);
            s.start(new SessionParams(sp));
            session = s;
        }
        return session;
    }

    /** Folder with the most free space (USB drives / SD cards are used when available). */
    private File baseDir() {
        File best = ctx.getCacheDir();
        long free = best.getUsableSpace();
        File[] dirs = ctx.getExternalCacheDirs();
        if (dirs != null) {
            for (File d : dirs) {
                if (d == null) continue;
                long f = d.getUsableSpace();
                if (f > free) {
                    free = f;
                    best = d;
                }
            }
        }
        File out = new File(best, "torrents");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        return out;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Removes leftovers from an earlier run. */
    public static void cleanupAsync(final Context c) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<File> roots = new ArrayList<>();
                roots.add(c.getCacheDir());
                File[] ext = c.getExternalCacheDirs();
                if (ext != null) for (File e : ext) if (e != null) roots.add(e);
                for (File r : roots) {
                    File t = new File(r, "torrents");
                    if (t.exists() && (inst == null || inst.handle == null)) deleteTree(t);
                }
            }
        }).start();
    }

    /**
     * Gets the torrent info and starts it. Blocks (up to ~2 minutes for magnets), so call it
     * from a background thread. Returns one playable item per video/audio file.
     */
    public List<Store.Item> open(String uri) throws Exception {
        final int token;
        synchronized (this) {
            stopInternal();
            token = ++openToken;
            state = "Getting torrent info…";
            name = Formats.titleFromUrl(uri);
        }
        SessionManager s = session();
        File dir = baseDir();
        TorrentInfo ti;
        if (uri.trim().toLowerCase(Locale.US).startsWith("magnet:")) {
            byte[] data = s.fetchMagnet(uri.trim(), 120, dir);
            if (data == null) throw new Exception("Could not get the torrent info (no peers found). Try again later or use another link.");
            ti = TorrentInfo.bdecode(data);
        } else {
            ti = TorrentInfo.bdecode(Net.fetch(uri.trim(), 20_000_000).data);
        }
        if (token != openToken) throw new Exception("Cancelled");

        final FileStorage fs = ti.files();
        List<Integer> media = new ArrayList<>();
        int biggest = -1;
        long biggestSize = -1;
        for (int i = 0; i < fs.numFiles(); i++) {
            if (fs.padFileAt(i)) continue;
            String n = fs.fileName(i);
            if (Formats.isVideo(n) || Formats.isAudio(n)) media.add(i);
            if (fs.fileSize(i) > biggestSize) {
                biggestSize = fs.fileSize(i);
                biggest = i;
            }
        }
        if (media.isEmpty() && biggest >= 0) media.add(biggest);
        if (media.isEmpty()) throw new Exception("This torrent has no files");
        Collections.sort(media, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return fs.filePath(a).compareToIgnoreCase(fs.filePath(b));
            }
        });

        Priority[] pr = Priority.array(Priority.IGNORE, fs.numFiles());
        pr[media.get(0)] = Priority.DEFAULT;
        s.download(ti, dir, null, pr, null, TorrentFlags.SEQUENTIAL_DOWNLOAD);

        TorrentHandle th = null;
        for (int k = 0; k < 150 && th == null; k++) {
            TorrentHandle h = s.find(ti.infoHash());
            if (h != null && h.isValid()) th = h;
            else Thread.sleep(100);
        }
        if (th == null) throw new Exception("Could not start the torrent");
        th.unsetFlags(TorrentFlags.AUTO_MANAGED);
        th.resume();

        String h = ti.infoHash().toHex();
        synchronized (this) {
            if (token != openToken) {
                s.remove(th, SessionHandle.DELETE_FILES);
                throw new Exception("Cancelled");
            }
            handle = th;
            info = ti;
            hash = h;
            activeFile = media.get(0);
            name = ti.name();
            state = "Downloading";
        }

        List<Store.Item> out = new ArrayList<>();
        for (int idx : media) {
            String fn = fs.fileName(idx);
            out.add(new Store.Item(fn, "http://127.0.0.1:" + RemoteService.PORT + "/torrent/" + h + "/" + idx + "/"
                    + Uri.encode(fn), ""));
        }
        return out;
    }

    public synchronized void stop() {
        openToken++;
        stopInternal();
    }

    public void stopAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                stop();
            }
        }).start();
    }

    private void stopInternal() {
        TorrentHandle th = handle;
        handle = null;
        info = null;
        hash = "";
        activeFile = -1;
        state = "idle";
        if (th != null && session != null) {
            try {
                session.remove(th, SessionHandle.DELETE_FILES);
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isActive() {
        return handle != null;
    }

    /** Makes the requested file the one being downloaded. Returns its size, or -1. */
    public synchronized long prepare(String h, int file) {
        TorrentHandle th = handle;
        TorrentInfo ti = info;
        if (th == null || ti == null || !hash.equals(h)) return -1;
        FileStorage fs = ti.files();
        if (file < 0 || file >= fs.numFiles()) return -1;
        if (file != activeFile) {
            Priority[] pr = Priority.array(Priority.IGNORE, fs.numFiles());
            pr[file] = Priority.DEFAULT;
            th.prioritizeFiles(pr);
            th.clearPieceDeadlines();
            activeFile = file;
        }
        return fs.fileSize(file);
    }

    public InputStream stream(String h, int file, long from, long to) throws IOException {
        TorrentHandle th = handle;
        TorrentInfo ti = info;
        if (th == null || ti == null || !hash.equals(h)) throw new IOException("Torrent is not active");
        FileStorage fs = ti.files();
        String path = fs.filePath(file, th.savePath());
        return new PieceStream(th, path, fs.fileOffset(file), fs.fileSize(file), ti.pieceLength(),
                ti.numPieces(), from, to);
    }

    private final class PieceStream extends InputStream {
        private final TorrentHandle th;
        private final String path;
        private final long fileOffset, fileSize;
        private final int pieceLen, lastPiece;
        private final long end;
        private long pos;
        private RandomAccessFile raf;
        private volatile boolean closed;
        private int lastPrioritized = -1;

        PieceStream(TorrentHandle th, String path, long fileOffset, long fileSize, int pieceLen, int numPieces,
                    long from, long to) {
            this.th = th;
            this.path = path;
            this.fileOffset = fileOffset;
            this.fileSize = fileSize;
            this.pieceLen = pieceLen;
            this.lastPiece = (int) Math.min(numPieces - 1, (fileOffset + Math.max(0, fileSize - 1)) / pieceLen);
            this.pos = from;
            this.end = to;
        }

        private void prioritize(int piece) {
            // read-ahead window of ~16 MB (at least 6 pieces) with rising deadlines
            int window = Math.max(6, (16 * 1024 * 1024) / Math.max(1, pieceLen));
            for (int k = 0; k < window; k++) {
                int p = piece + k;
                if (p > lastPiece) break;
                if (!th.havePiece(p)) th.setPieceDeadline(p, 300 + k * 250);
            }
            lastPrioritized = piece;
        }

        private void waitFor(int piece) throws IOException {
            if (piece != lastPrioritized) prioritize(piece);
            long lastPush = System.currentTimeMillis();
            while (!th.havePiece(piece)) {
                if (closed || handle != th) throw new IOException("Torrent stopped");
                if (System.currentTimeMillis() - lastPush > 3000) {
                    prioritize(piece);
                    lastPush = System.currentTimeMillis();
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted");
                }
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("closed");
            if (pos > end || pos >= fileSize) return -1;
            if (len == 0) return 0;
            long abs = fileOffset + pos;
            int piece = (int) (abs / pieceLen);
            waitFor(piece);
            long pieceEnd = (long) (piece + 1) * pieceLen;
            int n = (int) Math.min(len, Math.min(pieceEnd - abs, end - pos + 1));
            for (int tries = 0; ; tries++) {
                try {
                    if (raf == null) raf = new RandomAccessFile(path, "r");
                    raf.seek(pos);
                    int r = raf.read(b, off, n);
                    if (r > 0) {
                        pos += r;
                        return r;
                    }
                } catch (IOException e) {
                    if (tries > 40) throw e;
                    if (raf != null) {
                        try {
                            raf.close();
                        } catch (IOException ignored) {
                        }
                        raf = null;
                    }
                }
                if (tries > 40) throw new IOException("Could not read torrent data");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted");
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (raf != null) raf.close();
        }
    }

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            TorrentHandle th = handle;
            o.put("active", th != null);
            o.put("name", name);
            o.put("state", state);
            if (th != null && th.isValid()) {
                TorrentStatus st = th.status();
                o.put("peers", st.numPeers());
                o.put("seeds", st.numSeeds());
                o.put("speed", st.downloadPayloadRate());
                o.put("progress", Math.round(st.progress() * 1000) / 10.0);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public String shortStatus() {
        TorrentHandle th = handle;
        if (th == null) return state.equals("idle") ? "" : state;
        try {
            TorrentStatus st = th.status();
            return String.format(Locale.US, "Torrent: %d peers · %.1f MB/s · %.1f%%",
                    st.numPeers(), st.downloadPayloadRate() / 1048576f, st.progress() * 100f);
        } catch (Exception e) {
            return "Torrent";
        }
    }
}
