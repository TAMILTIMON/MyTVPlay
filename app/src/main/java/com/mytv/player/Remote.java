package com.mytv.player;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bridge between the phone web page (background threads) and the player on the TV (main thread). */
public final class Remote {
    private Remote() {
    }

    public static volatile PlayerActivity player;
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ArrayList<Store.Item> handoff;

    /** Runs code on the TV's main thread and waits for the answer. */
    public static <T> T call(final Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final Object[] box = new Object[2];
        final CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    box[0] = c.call();
                } catch (Throwable t) {
                    box[1] = t;
                }
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) throw new Exception("The TV did not answer in time");
        if (box[1] != null) throw new Exception(String.valueOf(((Throwable) box[1]).getMessage()));
        @SuppressWarnings("unchecked") T r = (T) box[0];
        return r;
    }

    public static synchronized ArrayList<Store.Item> takeHandoff() {
        ArrayList<Store.Item> l = handoff;
        handoff = null;
        return l;
    }

    /** Opens the player with this list (or replaces what is playing). */
    public static void play(Context ctx, List<Store.Item> items, int index) {
        synchronized (Remote.class) {
            handoff = new ArrayList<>(items);
        }
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_HANDOFF, true);
        i.putExtra(PlayerActivity.EXTRA_INDEX, index);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(i);
    }

    public static void openHome(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(i);
    }

    public static int keyCode(String k) {
        switch (k) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "playpause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            default: return -1;
        }
    }

    /**
     * Presses a key inside this app (works on menus and dialogs too).
     * Must be called from a background thread.
     */
    public static String pressKey(final int code) {
        if (code < 0) return "Unknown key";
        try {
            new Instrumentation().sendKeyDownUpSync(code);
            return null;
        } catch (Throwable t) {
            // App is not in front: try the player directly.
            final PlayerActivity p = player;
            if (p != null) {
                try {
                    call(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            return p.handleKey(code, 0);
                        }
                    });
                    return null;
                } catch (Exception ignored) {
                }
            }
            return "Open My TV Player on the TV first";
        }
    }
}
