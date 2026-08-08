package com.legitcoconut.blueremind;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Per-device pictures, copied into the app's private storage so they survive the
 * user deleting the original from the gallery.
 */
final class Icons {

    /** Longest stored side. Enough for the grid card, and a 12MP original would be absurd. */
    private static final int SIZE = 384;

    private Icons() {
    }

    static File file(Context c, String address) {
        return new File(new File(c.getFilesDir(), "icons"), address.replace(":", "") + ".png");
    }

    static Bitmap load(Context c, String address) {
        File f = file(c, address);
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    static void delete(Context c, String address) {
        //noinspection ResultOfMethodCallIgnored
        file(c, address).delete();
    }

    /** Downscales to fit SIZE without cropping and writes a PNG. False on any decode failure. */
    static boolean save(Context c, String address, Uri source) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(source)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return false;
            }

            // Second pass: the content stream is not reliably rewindable, so reopen it.
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / SIZE);
            Bitmap raw;
            try (InputStream in = c.getContentResolver().openInputStream(source)) {
                raw = BitmapFactory.decodeStream(in, null, opts);
            }
            if (raw == null) {
                return false;
            }

            Bitmap scaled = fit(raw);
            File f = file(c, address);
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(f)) {
                // PNG, so a product shot with a transparent background keeps it.
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return true;
        } catch (IOException | SecurityException | OutOfMemoryError e) {
            return false;
        }
    }

    /** Shrinks the longest side to SIZE and keeps the aspect ratio. Never crops, never upscales. */
    private static Bitmap fit(Bitmap src) {
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest <= SIZE) {
            return src;
        }
        float scale = (float) SIZE / longest;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(src.getWidth() * scale)),
                Math.max(1, Math.round(src.getHeight() * scale)), true);
    }
}
