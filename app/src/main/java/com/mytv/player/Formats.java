package com.mytv.player;

import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** File types the player understands (VLC engine plays nearly everything). */
public final class Formats {
    private Formats() {
    }

    private static Set<String> set(String s) {
        return new HashSet<>(Arrays.asList(s.split(" ")));
    }

    public static final Set<String> VIDEO = set("3g2 3gp 3gp2 3gpp amv asf avi av1 bik bdmv divx drc dv dvr-ms evo f4v flv "
            + "gvi gxf h264 h265 hevc ifo iso ivf m1v m2t m2ts m2v m4v mk3d mkv mov mp2v mp4 mp4v mpe mpeg mpeg1 mpeg2 "
            + "mpeg4 mpg mpv mpv2 mts mtv mxf nsv nuv ogm ogv ogx rec rm rmvb rpl thp tod tp trp ts tts vob vro webm wm "
            + "wmv wtv xesc xvid y4m");
    public static final Set<String> AUDIO = set("aac ac3 adt adts aif aifc aiff alac amr ape au awb caf dff dsf dts eac3 "
            + "flac it m4a m4b m4p mid midi mka mlp mod mp1 mp2 mp3 mpa mpc oga ogg opus ra s3m snd spx tak thd tta "
            + "voc vqf w64 wav weba wma wv xa xm");
    public static final Set<String> SUBS = set("srt ass ssa vtt sub idx smi sami txt mpl usf ttml dfxp sbv lrc jss psb "
            + "rt stl pjs aqt");
    public static final Set<String> PLAYLISTS = set("m3u m3u8 pls xspf asx cue");

    public static String ext(String nameOrUrl) {
        if (nameOrUrl == null) return "";
        String s = nameOrUrl;
        int q = s.indexOf('?');
        if (q >= 0 && s.contains("://")) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0 && s.contains("://")) s = s.substring(0, h);
        int slash = s.lastIndexOf('/');
        int dot = s.lastIndexOf('.');
        if (dot < 0 || dot < slash) return "";
        return s.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static boolean isVideo(String n) {
        return VIDEO.contains(ext(n));
    }

    public static boolean isAudio(String n) {
        return AUDIO.contains(ext(n));
    }

    public static boolean isSub(String n) {
        return SUBS.contains(ext(n));
    }

    public static boolean isMedia(String n) {
        String e = ext(n);
        return VIDEO.contains(e) || AUDIO.contains(e) || PLAYLISTS.contains(e);
    }

    public static boolean isTorrent(String url) {
        if (url == null) return false;
        String l = url.trim().toLowerCase(Locale.US);
        return l.startsWith("magnet:") || "torrent".equals(ext(l));
    }

    /** True for anything the engine can try to open: URLs of any scheme, magnets, local paths. */
    public static boolean looksPlayable(String u) {
        if (u == null) return false;
        u = u.trim();
        return u.startsWith("/") || u.matches("(?s)^[a-zA-Z][a-zA-Z0-9+.\\-]*:.+");
    }

    public static String mime(String name) {
        String e = ext(name);
        switch (e) {
            case "mp4": case "m4v": return "video/mp4";
            case "mkv": case "mk3d": return "video/x-matroska";
            case "webm": return "video/webm";
            case "avi": return "video/x-msvideo";
            case "ts": case "m2ts": case "mts": return "video/mp2t";
            case "mp3": return "audio/mpeg";
            case "flac": return "audio/flac";
            case "srt": return "application/x-subrip";
            default: return "application/octet-stream";
        }
    }

    public static String titleFromUrl(String u) {
        if (u == null || u.isEmpty()) return "Video";
        try {
            if (u.startsWith("magnet:")) {
                Uri m = Uri.parse(u.replaceFirst("^magnet:\\??", "magnet://x/?"));
                String dn = m.getQueryParameter("dn");
                if (dn != null && !dn.isEmpty()) return dn;
                return "Torrent";
            }
            String p = u.startsWith("/") ? u : Uri.parse(u).getLastPathSegment();
            if (p != null) {
                int s = p.lastIndexOf('/');
                if (s >= 0) p = p.substring(s + 1);
                if (!p.isEmpty()) return p;
            }
        } catch (Exception ignored) {
        }
        return u;
    }
}
