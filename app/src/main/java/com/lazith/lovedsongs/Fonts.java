package com.lazith.lovedsongs;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.SparseArray;

/** Figtree, in the exact weights the design uses. */
final class Fonts {
    private static final SparseArray<Typeface> cache = new SparseArray<Typeface>();

    private Fonts() {}

    static Typeface w(Context c, int weight) {
        Typeface t = cache.get(weight);
        if (t != null) return t;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                t = new Typeface.Builder(c.getAssets(), "fonts/Figtree.ttf")
                        .setFontVariationSettings("'wght' " + weight).build();
            } catch (Exception ignored) {
            }
        }
        if (t == null) t = Typeface.create(Typeface.DEFAULT, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        cache.put(weight, t);
        return t;
    }
}
