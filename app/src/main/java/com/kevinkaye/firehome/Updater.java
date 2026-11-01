package com.kevinkaye.firehome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks GitHub for a newer Fire Home build, downloads it quietly in the background,
 * and opens Android's installer with one tap once it's ready.
 */
final class Updater {

    interface Listener {
        /** state: none | checking | downloading | ready | error */
        void onUpdate(String state, String detail);
    }

    private static final String BASE = "https://github.com/kwkaye84-coder/fire-home/releases/download/latest/";

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();

    private int latestCode;
    private String latestName = "";
    private int downloadedCode;
    private boolean installWhenReady;

    Updater(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    int installedCode() {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    String installedName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private File apk() { return new File(ctx.getExternalFilesDir(null), "update.apk"); }

    /** Check, and if there's a newer build, download it straight away. */
    void check() {
        io.execute(() -> {
            listener.onUpdate("checking", "");
            try (Response r = http.newCall(new Request.Builder().url(BASE + "version.json").build()).execute()) {
                if (!r.isSuccessful()) { listener.onUpdate("none", "Couldn't check"); return; }
                JSONObject o = new JSONObject(r.body().string());
                latestCode = o.optInt("versionCode", 0);
                latestName = o.optString("versionName", "");
            } catch (Exception e) {
                listener.onUpdate("none", "Couldn't check");
                return;
            }
            if (latestCode <= installedCode()) { listener.onUpdate("none", "Up to date"); return; }
            download();
        });
    }

    /** One tap: open the installer now if the download's done, otherwise as soon as it is. */
    void install() {
        io.execute(() -> {
            if (latestCode > installedCode() && downloadedCode == latestCode && apk().exists()) {
                openInstaller();
                return;
            }
            installWhenReady = true;
            if (latestCode <= installedCode()) {
                // Not checked yet in this session: check (which downloads), then install.
                main.post(this::check);
            } else {
                download();
            }
        });
    }

    /** Runs on the io thread. */
    private void download() {
        if (downloadedCode == latestCode && apk().exists()) { ready(); return; }
        listener.onUpdate("downloading", "0");
        File out = apk();
        try (Response r = http.newCall(new Request.Builder().url(BASE + "FireHome.apk").build()).execute()) {
            if (!r.isSuccessful()) throw new Exception("download " + r.code());
            long total = r.body().contentLength();
            long done = 0;
            int lastPct = -1;
            try (InputStream in = r.body().byteStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct >= lastPct + 10) { lastPct = pct; listener.onUpdate("downloading", String.valueOf(pct)); }
                    }
                }
            }
            downloadedCode = latestCode;
            ready();
        } catch (Exception e) {
            installWhenReady = false;
            listener.onUpdate("error", "Update download failed");
        }
    }

    private void ready() {
        listener.onUpdate("ready", latestName);
        if (installWhenReady) {
            installWhenReady = false;
            openInstaller();
        }
    }

    private void openInstaller() {
        main.post(() -> {
            try {
                Uri uri = UpdateProvider.uriFor(ctx, apk());
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(i);
            } catch (Exception e) {
                listener.onUpdate("error", "Couldn't open the installer");
            }
        });
    }
}
