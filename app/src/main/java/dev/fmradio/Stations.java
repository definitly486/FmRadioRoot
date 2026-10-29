package dev.fmradio;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class Stations {
    private Stations() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("fm", Context.MODE_PRIVATE);
    }

    static List<Integer> read(SharedPreferences sp, String key) {
        List<Integer> res = new ArrayList<>();
        String s = sp.getString(key, "");
        if (s == null || s.isEmpty()) return res;
        for (String p : s.split(",")) {
            try {
                res.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return res;
    }

    static void write(SharedPreferences sp, String key, Collection<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        sp.edit().putString(key, sb.toString()).apply();
    }

    /** 98000 -> "98.0" */
    static String format(int khz) {
        return (khz / 1000) + "." + ((khz % 1000) / 100);
    }
}
