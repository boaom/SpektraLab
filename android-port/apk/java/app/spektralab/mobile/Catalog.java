package app.spektralab.mobile;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Film / paper catalogue, read from the staged engine resources at launch. */
final class Catalog {
    final List<String> films = new ArrayList<>();
    final Map<String, String> filmNames = new LinkedHashMap<>();
    final Map<String, String> filmType = new LinkedHashMap<>();     // negative / slide / cine ...
    final Map<String, String> filmTarget = new LinkedHashMap<>();   // target_print
    final List<String> papers = new ArrayList<>();
    final Map<String, String> paperNames = new LinkedHashMap<>();
    final Map<String, String> defaultPaper = new LinkedHashMap<>(); // film -> paper

    /** Kodak / Fuji box colours; anything else gets a muted hash hue. */
    private static final Map<String, Integer> BOX = new LinkedHashMap<>();
    static {
        BOX.put("kodak_portra_400", 0xFFF5E7C6);
        BOX.put("kodak_portra_160", 0xFFEBD9B4);
        BOX.put("kodak_portra_800", 0xFFC9A268);
        BOX.put("kodak_ektar_100", 0xFFB8342A);
        BOX.put("kodak_gold_200", 0xFFD9A521);
        BOX.put("kodak_ultramax_400", 0xFF8C6E3C);
        BOX.put("kodak_kodachrome_64", 0xFFC9502E);
        BOX.put("kodak_ektachrome_100", 0xFF3E5E8C);
        BOX.put("kodak_vision3_250d", 0xFF37455E);
        BOX.put("fujifilm_c200", 0xFF5E7F6B);
        BOX.put("fujifilm_pro_400h", 0xFF9DBA9D);
        BOX.put("fujifilm_velvia_100", 0xFF2E5B3A);
        BOX.put("fujifilm_provia_100f", 0xFF6E2E3E);
    }

    int filmColor(String id) {
        Integer c = BOX.get(id);
        if (c != null) return c;
        int h = Math.abs(id.hashCode()) % 360;
        return 0xFF000000 | hsv(h, 0.30f, 0.72f);
    }

    private static int hsv(int h, float s, float v) {
        int i = (int) Math.floor(h / 60f) % 6;
        float f = h / 60f - (float) Math.floor(h / 60f);
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        float r, g, bl;
        switch (i) {
            case 0: r = v; g = t; bl = p; break;
            case 1: r = q; g = v; bl = p; break;
            case 2: r = p; g = v; bl = t; break;
            case 3: r = p; g = q; bl = v; break;
            case 4: r = t; g = p; bl = v; break;
            default: r = v; g = p; bl = q; break;
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(bl * 255);
    }

    String filmMeta(String id) {
        String type = filmType.get(id);
        String zh;
        if ("negative".equals(type)) zh = "彩色负片";
        else if ("slide".equals(type) || "reversal".equals(type)) zh = "彩色反转片";
        else if ("cine".equals(type)) zh = "电影负片";
        else zh = "profile";
        String target = filmTarget.get(id);
        String tp = target != null && paperNames.containsKey(target) ? paperNames.get(target) : "";
        return zh + (tp.isEmpty() ? "" : " · " + tp);
    }

    String paperName(String id) {
        String n = paperNames.get(id);
        return n != null ? n : pretty(id);
    }

    static Catalog load(Context ctx) {
        Catalog c = new Catalog();
        File dir = new File(ctx.getFilesDir(), "engine");
        try {
            Map<String, Object> luts = Engine.asMap(Engine.miniJson(read(new File(dir, "print_luts.json"))));
            if (luts != null) {
                for (Map.Entry<String, Object> e : luts.entrySet()) {
                    Map<String, Object> info = Engine.asMap(e.getValue());
                    c.papers.add(e.getKey());
                    c.paperNames.put(e.getKey(), pretty(e.getKey()));
                    String paired = info != null && info.get("paired_film") != null ? String.valueOf(info.get("paired_film")) : null;
                    if (paired != null) c.defaultPaper.put(paired, e.getKey());
                }
            }
            File[] profiles = new File(dir, "profiles").listFiles();
            if (profiles != null) {
                for (File f : profiles) {
                    if (!f.getName().endsWith(".json")) continue;
                    Map<String, Object> doc = Engine.asMap(Engine.miniJson(read(f)));
                    if (doc == null) continue;
                    Map<String, Object> info = Engine.asMap(doc.get("info"));
                    if (info == null) continue;
                    String stock = info.get("stock") != null ? String.valueOf(info.get("stock"))
                            : f.getName().replace(".json", "");
                    if (c.papers.contains(stock)) continue;
                    c.films.add(stock);
                    c.filmNames.put(stock, info.get("name") != null ? String.valueOf(info.get("name")) : pretty(stock));
                    if (info.get("type") != null) c.filmType.put(stock, String.valueOf(info.get("type")));
                    if (info.get("target_print") != null) c.filmTarget.put(stock, String.valueOf(info.get("target_print")));
                }
            }
        } catch (Exception e) {
            // keep whatever loaded; fall back to one stock so the UI has a row
        }
        if (c.films.isEmpty()) {
            c.films.add("kodak_portra_400");
            c.filmNames.put("kodak_portra_400", "Kodak Portra 400");
            c.filmType.put("kodak_portra_400", "negative");
        }
        if (c.papers.isEmpty()) {
            c.papers.add("kodak_portra_endura");
            c.paperNames.put("kodak_portra_endura", "Kodak Portra Endura");
        }
        return c;
    }

    private static String read(File f) throws IOException {
        FileInputStream fi = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0;
        while (off < b.length) {
            int r = fi.read(b, off, b.length - off);
            if (r < 0) break;
            off += r;
        }
        fi.close();
        return new String(b, 0, off, "UTF-8");
    }

    static String pretty(String id) {
        String[] parts = id.split("_");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(' ');
            if (p.matches("[0-9]+.*")) b.append(p.toUpperCase());
            else b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }
}