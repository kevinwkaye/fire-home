package com.kevinkaye.firehome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

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

/** Checks GitHub for a newer Fire Home build and installs it with one tap. */
final class Updater {

    interface Listener {
        /** state: none | ready | downloading | error */
        void onUpdate(String state, String detail);
    }

    private static final String BASE = "https://github.com/kwkaye84-coder/fire-home/releases/download/latest/";

    private final Context ctx;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
    private int latest;

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

    void check() {
        io.execute(() -> {
            try (Response r = http.newCall(new Request.Builder().url(BASE + "version.json").build()).execute()) {
                if (!r.isSuccessful()) { listener.onUpdate("none", "Couldn't check (" + r.code() + ")"); return; }
                JSONObject o = new JSONObject(r.body().string());
                latest = o.optInt("versionCode", 0);
                if (latest > installedCode()) listener.onUpdate("ready", o.optString("versionName", ""));
                else listener.onUpdate("none", "Up to date");
            } catch (Exception e) {
                listener.onUpdate("none", "Couldn't check");
            }
        });
    }

    void install() {
        io.execute(() -> {
            listener.onUpdate("downloading", "");
            File out = new File(ctx.getExternalFilesDir(null), "update.apk");
            try (Response r = http.newCall(new Request.Builder().url(BASE + "FireHome.apk").build()).execute()) {
                if (!r.isSuccessful()) throw new Exception("download " + r.code());
                try (InputStream in = r.body().byteStream(); FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                Uri uri = androidx_free_uri(out);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(i);
            } catch (Exception e) {
                listener.onUpdate("error", "Update failed: " + e.getMessage());
            }
        });
    }

    private Uri androidx_free_uri(File f) {
        return UpdateProvider.uriFor(ctx, f);
    }
}
