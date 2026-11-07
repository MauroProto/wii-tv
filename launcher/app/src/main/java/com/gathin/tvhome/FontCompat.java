package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class FontCompat {
    private FontCompat() {}

    static Typeface load(Resources res, int resId) {
        if (Build.VERSION.SDK_INT >= 26) {
            return res.getFont(resId);
        }
        try {
            File out = new File(System.getProperty("java.io.tmpdir"), "font_" + resId + ".ttf");
            if (!out.exists() || out.length() == 0) {
                try (InputStream in = res.openRawResource(resId);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
            return Typeface.createFromFile(out);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }
}
