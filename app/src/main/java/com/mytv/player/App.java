package com.mytv.player;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Store.init(this);
        Net.cleanSubtitleCache(this);
        TorrentEngine.cleanupAsync(this);
        RemoteService.start(this);
    }
}
