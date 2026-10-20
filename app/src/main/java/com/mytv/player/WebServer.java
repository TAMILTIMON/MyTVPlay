package com.mytv.player;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import fi.iki.elonen.NanoHTTPD;

/** Phone web page: add links, play links/magnets now, and a full remote control. */
public class WebServer extends NanoHTTPD {
    private final Context ctx;

    public WebServer(Context c, int port) {
        super(port);
        ctx = c.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession s) {
        try {
            String uri = s.getUri();
            if (uri.startsWith("/torrent/")) return torrent(s, uri);

            Map<String, String> files = new HashMap<>();
            if (Method.POST.equals(s.getMethod())) s.parseBody(files);
            @SuppressWarnings("deprecation") Map<String, String> p = s.getParms();

            switch (uri) {
                case "/data":
                    return json(Store.toJson());

                case "/add": {
                    String u = val(p, "url");
                    if (!Formats.looksPlayable(u)) return fail("That does not look like a link (http://, https://, magnet:, rtsp:// …)");
                    Store.add(val(p, "playlist"), new Store.Item(val(p, "title"), u, val(p, "sub")));
                    return ok();
                }

                case "/import": {
                    String text = val(p, "text");
                    String link = val(p, "link");
                    if (text.isEmpty() && !link.isEmpty()) text = Net.fetchText(link);
                    List<Store.Item> l = Store.parseM3u(text);
                    if (l.isEmpty()) return fail("No video links found in that playlist.");
                    int n = Store.addAll(val(p, "playlist"), l);
                    return json("{\"ok\":true,\"added\":" + n + "}");
                }

                case "/del":
                    Store.delItem(val(p, "playlist"), Integer.parseInt(val(p, "idx")));
                    return ok();

                case "/move":
                    Store.moveItem(val(p, "playlist"), Integer.parseInt(val(p, "idx")), Integer.parseInt(val(p, "dir")));
                    return ok();

                case "/delpl":
                    Store.delPlaylist(val(p, "playlist"));
                    return ok();

                case "/playlist": {
                    List<Store.Item> items = Store.items(val(p, "playlist"));
                    if (items.isEmpty()) return fail("That playlist is empty");
                    int idx = 0;
                    try {
                        idx = Integer.parseInt(val(p, "idx"));
                    } catch (Exception ignored) {
                    }
                    Remote.play(ctx, items, Math.max(0, Math.min(idx, items.size() - 1)));
                    return msg("Playing on the TV");
                }

                case "/play": {
                    String u = val(p, "url");
                    if (!Formats.looksPlayable(u)) return fail("Paste a link first (http://, https://, magnet:, rtsp:// …)");
                    final Store.Item it = new Store.Item(val(p, "title"), u, val(p, "sub"));
                    Store.addRecent(it);
                    final PlayerActivity pl = Remote.player;
                    if ("queue".equals(val(p, "mode")) && pl != null) {
                        Remote.call(new Callable<Object>() {
                            @Override
                            public Object call() {
                                pl.enqueue(it);
                                return null;
                            }
                        });
                        return msg("Added to the TV queue");
                    }
                    Remote.play(ctx, Collections.singletonList(it), 0);
                    return msg(Formats.isTorrent(u) ? "Starting torrent on the TV… (getting info can take a minute)"
                            : "Playing on the TV");
                }

                case "/state":
                    return json(state().toString());

                case "/options":
                    return json(PlayerActivity.optionsJson().toString());

                case "/cmd": {
                    String c = val(p, "c");
                    String v = val(p, "v");
                    String b = val(p, "b");
                    if ("subadd".equals(c)) {
                        if (!Formats.looksPlayable(v)) return fail("Paste a subtitle link first");
                        File f = Net.downloadSubtitle(ctx, v);
                        c = "subfile";
                        v = f.getAbsolutePath();
                    }
                    return msg(command(c, v, b));
                }

                case "/key": {
                    String k = val(p, "k");
                    if ("home".equals(k)) {
                        Remote.openHome(ctx);
                        return ok();
                    }
                    String err = Remote.pressKey(Remote.keyCode(k));
                    return err == null ? ok() : fail(err);
                }

                case "/subupload": {
                    String tmp = files.get("file");
                    if (tmp == null) return fail("Choose a subtitle file first");
                    byte[] data = readFile(new File(tmp));
                    File f = Net.saveSubtitle(ctx, data, val(p, "file"));
                    return msg(command("subfile", f.getAbsolutePath(), ""));
                }

                case "/torrentstop":
                    TorrentEngine.get(ctx).stopAsync();
                    return msg("Torrent stopped and its data deleted");

                case "/":
                case "/index.html":
                    try (InputStream in = ctx.getAssets().open("index.html")) {
                        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                                new String(readStream(in), "UTF-8"));
                    }

                default:
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
        } catch (Exception e) {
            return fail(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    private JSONObject state() throws Exception {
        final PlayerActivity pl = Remote.player;
        JSONObject o = null;
        if (pl != null) {
            try {
                o = Remote.call(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() throws Exception {
                        return pl.state();
                    }
                });
            } catch (Exception ignored) {
            }
        }
        if (o == null) o = new JSONObject().put("player", false);
        PlayerActivity.putSettings(o);
        o.put("torrent", TorrentEngine.get(ctx).status());
        return o;
    }

    private String command(final String c, final String v, final String b) throws Exception {
        final PlayerActivity pl = Remote.player;
        if (pl != null) {
            return Remote.call(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return pl.command(c, v, b);
                }
            });
        }
        String r = PlayerActivity.applySetting(c, v, b);
        return r != null ? r + " (applies when a video plays)" : "Nothing is playing on the TV";
    }

    // ---------- torrent streaming with HTTP range support ----------
    private Response torrent(IHTTPSession s, String uri) throws Exception {
        String[] parts = uri.split("/");
        if (parts.length < 4) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Bad link");
        String h = parts[2];
        int idx = Integer.parseInt(parts[3]);
        String fname = parts.length > 4 ? parts[4] : "";
        TorrentEngine te = TorrentEngine.get(ctx);
        long size = te.prepare(h, idx);
        if (size < 0) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Torrent is not active");

        String range = s.getHeaders().get("range");
        long start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String r = range.substring(6);
            int comma = r.indexOf(',');
            if (comma >= 0) r = r.substring(0, comma);
            int dash = r.indexOf('-');
            if (dash >= 0) {
                String a = r.substring(0, dash).trim(), b = r.substring(dash + 1).trim();
                if (a.isEmpty()) {
                    if (!b.isEmpty()) start = Math.max(0, size - Long.parseLong(b));
                } else {
                    start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Math.min(Long.parseLong(b), size - 1);
                }
                partial = true;
            }
        }
        if (start >= size || start > end) {
            Response r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
            r.addHeader("Content-Range", "bytes */" + size);
            return r;
        }
        long len = end - start + 1;
        InputStream in = te.stream(h, idx, start, end);
        Response r = newFixedLengthResponse(partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                Formats.mime(fname), in, len);
        r.addHeader("Accept-Ranges", "bytes");
        if (partial) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        return r;
    }

    // ---------- helpers ----------
    private static byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in);
        }
    }

    private static String val(Map<String, String> p, String k) {
        String v = p.get(k);
        return v == null ? "" : v.trim();
    }

    private Response json(String s) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", s);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response ok() {
        return json("{\"ok\":true}");
    }

    private Response msg(String m) {
        try {
            return json(new JSONObject().put("ok", true).put("msg", m == null ? "" : m).toString());
        } catch (Exception e) {
            return ok();
        }
    }

    private Response fail(String m) {
        try {
            return json(new JSONObject().put("ok", false).put("msg", m).toString());
        } catch (Exception e) {
            return json("{\"ok\":false}");
        }
    }
}
