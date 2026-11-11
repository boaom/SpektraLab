package app.spektralab.mobile;

import android.app.Activity;
import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SpektraLab for Android: drives spektralab-host (bundled as libspektralab_host.so)
 *  over the HOST-PROTOCOL.md stdin/stdout frame protocol. */
public class MainActivity extends Activity {
    static final String TAG = "SpektraLab";
    Engine engine;
    Spinner filmSpin, paperSpin, qualitySpin;
    TextView status;
    ImageView canvas;
    Bitmap result;
    File pickedPng;

    // ---- film / paper catalog, read from the staged engine resources ----
    static class Catalog {
        List<String> films = new ArrayList<>();          // stock ids
        Map<String,String> filmNames = new LinkedHashMap<>();
        List<String> papers = new ArrayList<>();
        Map<String,String> paperNames = new LinkedHashMap<>();
        Map<String,String> defaultPaper = new LinkedHashMap<>(); // film -> paper
    }

    Catalog catalog;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(getResources().getDisplayMetrics().density * 12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("SpektraLab — 光谱胶片暗房");
        title.setTextSize(22); title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, pad);
        root.addView(title);

        filmSpin = spinner(root, "胶片");
        paperSpin = spinner(root, "相纸");
        qualitySpin = spinner(root, "质量", new String[]{"预览 2560px（快）", "全渲染（≤11MP，慢）"});
        filmSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { autoPaper(); }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        Button pick = button(root, "选择照片");
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            i.setType("image/*"); i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(android.content.Intent.createChooser(i, "选一张照片"), 1);
        } });

        Button render = button(root, "渲染");
        render.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { runRender(); } });

        Button save = button(root, "保存到相册");
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { runSave(); } });

        status = new TextView(this);
        status.setTextColor(Color.WHITE); status.setTextSize(13);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        canvas = new ImageView(this);
        canvas.setBackgroundColor(Color.DKGRAY);
        canvas.setAdjustViewBounds(true);
        root.addView(canvas, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        try {
            engine = new Engine(this);
        } catch (Exception e) {
            status("引擎初始化失败: " + e);
            Log.e(TAG, "engine init failed", e);
            return;
        }
        try {
            catalog = engine.loadCatalog();
        } catch (Exception e) {
            status("目录加载失败: " + e);
            catalog = new Catalog();
            catalog.films.add("kodak_portra_400"); catalog.filmNames.put("kodak_portra_400", "Kodak Portra 400");
            catalog.papers.add("kodak_portra_endura"); catalog.paperNames.put("kodak_portra_endura", "Kodak Portra Endura");
        }
        fill(catalog.films, catalog.filmNames, filmSpin);
        fill(catalog.papers, catalog.paperNames, paperSpin);
        qualitySpin.setSelection(0);
        status("就绪 · 28 种胶片 × " + catalog.papers.size() + " 种相纸 · " + engine.deviceName());
    }

    Spinner spinner(LinearLayout root, String label) { return spinner(root, label, null); }

    Spinner spinner(LinearLayout root, String label, String[] items) {
        TextView t = new TextView(this); t.setText(label); t.setTextColor(Color.WHITE);
        root.addView(t);
        Spinner s = new Spinner(this);
        if (items != null) s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        root.addView(s);
        return s;
    }

    Button button(LinearLayout root, String label) {
        Button b = new Button(this); b.setText(label);
        root.addView(b);
        return b;
    }

    void fill(List<String> ids, Map<String,String> names, Spinner s) {
        String[] shown = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) shown[i] = names.getOrDefault(ids.get(i), ids.get(i));
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, shown);
        s.setAdapter(a);
    }

    void autoPaper() {
        int pos = filmSpin.getSelectedItemPosition();
        if (pos < 0 || catalog == null) return;
        String paper = catalog.defaultPaper.get(catalog.films.get(pos));
        if (paper == null) return;
        int pi = catalog.papers.indexOf(paper);
        if (pi >= 0) paperSpin.setSelection(pi);
    }

    String sel(Spinner s, List<String> ids, Map<String,String> names) {
        int p = s.getSelectedItemPosition();
        return p >= 0 && p < ids.size() ? ids.get(p) : null;
    }

    void status(final String t) { runOnUiThread(new Runnable() { public void run() { status.setText(t); } }); }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 1 || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), data.getData());
            Bitmap bmp = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                public void onHeaderDecoded(ImageDecoder dec, ImageDecoder.ImageInfo info, ImageDecoder.Source src2) {
                    // keep the picked image under ~10MP: Adreno's 128MiB SSBO range
                    // caps full-effect full-render at ~11MP
                    dec.setTargetSampleSize(Math.max(1, Math.round((float)Math.sqrt((double)(info.getSize().getWidth() * info.getSize().getHeight()) / 10_000_000.0))));
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                } });
            pickedPng = new File(getCacheDir(), "pick.png");
            FileOutputStream fo = new FileOutputStream(pickedPng);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            status("已载入 " + bmp.getWidth() + "×" + bmp.getHeight() + "，选胶片后点渲染");
        } catch (Exception e) {
            status("载入失败: " + e);
        }
    }

    void runRender() {
        if (engine == null || pickedPng == null) { status("先选择照片"); return; }
        final String film = sel(filmSpin, catalog.films, catalog.filmNames);
        final String paper = sel(paperSpin, catalog.papers, catalog.paperNames);
        if (film == null || paper == null) { status("先选胶片/相纸"); return; }
        final boolean full = qualitySpin.getSelectedItemPosition() == 1;
        status("渲染中（" + film + " → " + paper + "，" + (full ? "全渲染" : "预览") + "）…");
        new Thread(new Runnable() { public void run() {
            try {
                long t0 = android.os.SystemClock.uptimeMillis();
                Map<String,Object> open = engine.open(pickedPng.getAbsolutePath(),
                        map("film_stock", film, "print_stock", paper));
                Map<String,Object> img = engine.render(full ? "full" : "preview", null);
                Map<String,Object> image = (Map<String,Object>) img.get("image");
                int iw = ((Number)image.get("width")).intValue(), ih = ((Number)image.get("height")).intValue();
                byte[] px = engine.lastPayload();
                Bitmap bm = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
                bm.copyPixelsFromBuffer(ByteBuffer.wrap(px));
                result = bm;
                long ms = android.os.SystemClock.uptimeMillis() - t0;
                status("完成 " + iw + "×" + ih + " · " + ms + " ms · 点下方保存");
                runOnUiThread(new Runnable() { public void run() { canvas.setImageBitmap(result); } });
            } catch (Exception e) {
                Log.e(TAG, "render failed", e);
                status("渲染失败: " + e.getMessage());
            }
        } }).start();
    }

    void runSave() {
        if (result == null || engine == null || engine.session() == null) { status("还没有渲染结果"); return; }
        new Thread(new Runnable() { public void run() {
            try {
                String name = "spektralab_" + System.currentTimeMillis() + ".jpg";
                File out = new File(getCacheDir(), name);
                Map<String,Object> r = engine.export(out.getAbsolutePath(), "jpeg", 95);
                // move into MediaStore
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SpektraLab");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                FileInputStream fi = new FileInputStream(out);
                OutputStream fo = getContentResolver().openOutputStream(uri);
                byte[] buf = new byte[65536]; int n;
                while ((n = fi.read(buf)) > 0) fo.write(buf, 0, n);
                fi.close(); fo.close();
                status("已保存 Pictures/SpektraLab/" + name + "（" + r.get("width") + "×" + r.get("height") + "）");
                Toast.makeText(MainActivity.this, "已保存到相册 SpektraLab 目录", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "save failed", e);
                status("保存失败: " + e.getMessage());
            }
        } }).start();
    }

    static Map<String,Object> map(Object... kv) {
        LinkedHashMap<String,Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String)kv[i], kv[i + 1]);
        return m;
    }

    @Override protected void onDestroy() {
        if (engine != null) engine.close();
        super.onDestroy();
    }

    // ================= engine host process =================
    static class Engine {
        final Activity act;
        Process proc;
        InputStream in; OutputStream out; InputStream err;
        ByteArrayOutputStream errTail = new ByteArrayOutputStream();
        String session, deviceName = "?";

        Engine(Activity act) throws Exception {
            this.act = act;
            File res = stageResources();
            start(res);
        }

        File stageResources() throws IOException {
            File dir = new File(act.getFilesDir(), "engine");
            File marker = new File(dir, ".staged");
            if (marker.exists()) return dir;
            Log.i(TAG, "staging engine assets...");
            copyAssets("engine", dir);
            marker.createNewFile();
            Log.i(TAG, "staged " + new File(dir, "vulkan").list().length + " kernels");
            return dir;
        }

        void copyAssets(String assetPath, File target) throws IOException {
            // AssetManager.list() THROWS on file paths (not dirs) on some
            // implementations -- treat a failed/empty listing as a leaf file.
            String[] list = null;
            try { list = act.getAssets().list(assetPath); } catch (IOException e) { list = null; }
            if (list == null || list.length == 0) {
                InputStream is = act.getAssets().open(assetPath);
                FileOutputStream os = new FileOutputStream(target);
                byte[] buf = new byte[65536]; int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                is.close(); os.close();
                return;
            }
            target.mkdirs();
            for (String child : list) copyAssets(assetPath + "/" + child, new File(target, child));
        }

        synchronized void start(File res) throws IOException {
            String bin = new File(act.getApplicationInfo().nativeLibraryDir, "libspektralab_host.so").getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(bin, "--resources", res.getAbsolutePath());
            pb.redirectErrorStream(false);
            proc = pb.start();
            in = proc.getInputStream(); out = proc.getOutputStream(); err = proc.getErrorStream();
            Map<String,Object> hello = request("hello", map());
            Map<String,Object> backend = (Map<String,Object>) hello.get("backend");
            deviceName = String.valueOf(backend != null ? backend.get("device_name") : "?");
            Log.i(TAG, "hello ok, backend=" + deviceName);
            // drain stderr in background so it never blocks the host
            Thread t = new Thread(new Runnable() { public void run() {
                byte[] buf = new byte[4096]; int n;
                try { while ((n = err.read(buf)) > 0) { errTail.write(buf, 0, n);
                        if (errTail.size() > 16384) errTail.reset(); } } catch (IOException ignored) {}
            } });
            t.setDaemon(true); t.start();
        }

        synchronized void ensureAlive() throws IOException {
            if (proc == null || !proc.isAlive()) {
                session = null;
                start(new File(act.getFilesDir(), "engine"));
            }
        }

        Catalog loadCatalog() throws IOException {
            Catalog c = new Catalog();
            try {
                File dir = new File(act.getFilesDir(), "engine");
                Map<String,Object> luts = json(new File(dir, "print_luts.json"));
                // print_luts.json: { <paper_stock>: { paired_film, ... }, ... }
                for (Map.Entry<String,Object> e : luts.entrySet()) {
                    String stock = e.getKey();
                    Map<String,Object> info = (Map<String,Object>) e.getValue();
                    c.papers.add(stock);
                    c.paperNames.put(stock, pretty(stock));
                    String paired = info.get("paired_film") != null ? String.valueOf(info.get("paired_film")) : null;
                    if (paired != null) c.defaultPaper.put(paired, stock);
                }
                // films: profiles/*.json whose stock is not a paper stock
                File[] profiles = new File(dir, "profiles").listFiles(new java.io.FilenameFilter() {
                    public boolean accept(File d, String n) { return n.endsWith(".json"); } });
                if (profiles != null) for (File f : profiles) {
                    Map<String,Object> d = json(f);
                    Map<String,Object> info = (Map<String,Object>) d.get("info");
                    String stock = info.get("stock") != null ? String.valueOf(info.get("stock")) : f.getName().replace(".json", "");
                    if (c.papers.contains(stock)) continue;   // paper profile
                    c.films.add(stock);
                    c.filmNames.put(stock, info.get("name") != null ? String.valueOf(info.get("name")) : pretty(stock));
                }
            } catch (Exception e) {
                // fallback: hard defaults
                c.films.add("kodak_portra_400"); c.filmNames.put("kodak_portra_400", "Kodak Portra 400");
                c.papers.add("kodak_portra_endura"); c.paperNames.put("kodak_portra_endura", "Kodak Portra Endura");
            }
            return c;
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

        Map<String,Object> json(File f) throws IOException {
            FileInputStream fi = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            fi.close();
            return (Map<String,Object>) miniJson(bo.toString("UTF-8"));
        }

        // minimal recursive JSON parser (objects with string keys, arrays, string/number/bool/null)
        static Object miniJson(String s) { int[] i = {0}; return val(s, i); }
        static Object val(String s, int[] i) {
            skip(s, i);
            char c = s.charAt(i[0]);
            if (c == '{') { LinkedHashMap<String,Object> m = new LinkedHashMap<>(); i[0]++;
                skip(s, i); if (s.charAt(i[0]) == '}') { i[0]++; return m; }
                while (true) {
                    skip(s, i); String k = str(s, i); skip(s, i); i[0]++; // ':'
                    m.put(k, val(s, i)); skip(s, i);
                    if (s.charAt(i[0]) == ',') { i[0]++; continue; }
                    i[0]++; return m;
                } }
            if (c == '[') { ArrayList<Object> a = new ArrayList<>(); i[0]++;
                skip(s, i); if (s.charAt(i[0]) == ']') { i[0]++; return a; }
                while (true) { a.add(val(s, i)); skip(s, i);
                    if (s.charAt(i[0]) == ',') { i[0]++; continue; }
                    i[0]++; return a; } }
            if (c == '"') return str(s, i);
            int st = i[0];
            while (i[0] < s.length() && ",]}".indexOf(s.charAt(i[0])) < 0) i[0]++;
            String t = s.substring(st, i[0]).trim();
            if ("true".equals(t)) return Boolean.TRUE;
            if ("false".equals(t)) return Boolean.FALSE;
            if ("null".equals(t)) return null;
            try { return Double.parseDouble(t); } catch (Exception e) { return t; }
        }
        static void skip(String s, int[] i) { while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++; }
        static String str(String s, int[] i) {
            StringBuilder b = new StringBuilder(); i[0]++; // opening quote
            while (s.charAt(i[0]) != '"') {
                char ch = s.charAt(i[0]);
                if (ch == '\\') { i[0]++; ch = s.charAt(i[0]);
                    if (ch == 'n') b.append('\n'); else if (ch == 't') b.append('\t');
                    else if (ch == 'u') { b.append((char) Integer.parseInt(s.substring(i[0] + 1, i[0] + 5), 16)); i[0] += 4; }
                    else b.append(ch); }
                else b.append(ch);
                i[0]++;
            }
            i[0]++; return b.toString();
        }
        static String esc(String s) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) { char c = s.charAt(i);
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                else b.append(c); }
            return b.toString();
        }
        static String toJson(Map<String,Object> m) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String,Object> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append('"').append(esc(e.getKey())).append("\":").append(toJson(e.getValue()));
            }
            return b.append('}').toString();
        }
        static String toJson(Object v) {
            if (v == null) return "null";
            if (v instanceof Boolean || v instanceof Number) return v.toString();
            if (v instanceof Map) return toJson((Map<String,Object>) v);
            if (v instanceof List) { StringBuilder b = new StringBuilder("["); boolean f = true;
                for (Object o : (List<Object>) v) { if (!f) b.append(','); f = false; b.append(toJson(o)); } return b.append(']').toString(); }
            return '"' + esc(String.valueOf(v)) + '"';
        }

        synchronized Map<String,Object> request(String method, Map<String,Object> params) throws IOException {
            ensureAlive();
            int reqId = (int) (System.nanoTime() & 0x7fffffff);
            String header = "{\"id\":" + reqId + ",\"method\":\"" + esc(method) + "\",\"params\":" + toJson(params) + "}";
            byte[] hb = header.getBytes(StandardCharsets.UTF_8);
            ByteBuffer ph = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            ph.putInt(hb.length).putInt(0);
            out.write(ph.array()); out.write(hb); out.flush();
            Map<String,Object> resp = readFrameHeader();
            Object rid = resp.get("id");
            if (rid == null || ((Number) rid).intValue() != reqId)
                throw new IOException("protocol desync, got " + resp);
            if (!Boolean.TRUE.equals(resp.get("ok"))) {
                Map<String,Object> err = (Map<String,Object>) resp.get("error");
                throw new IOException(method + ": " + (err != null ? err.get("code") + ": " + err.get("message") : resp));
            }
            return (Map<String,Object>) resp.get("result");
        }

        Map<String,Object> readFrameHeader() throws IOException {
            byte[] p = readFully(in, 8);
            ByteBuffer bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
            int hl = bb.getInt(), plen = bb.getInt();
            byte[] hb = readFully(in, hl);
            byte[] payload = plen > 0 ? readFully(in, plen) : null;
            lastPayload = payload;
            return (Map<String,Object>) miniJson(new String(hb, StandardCharsets.UTF_8));
        }

        byte[] lastPayload;
        byte[] lastPayload() { return lastPayload; }

        static byte[] readFully(InputStream is, int n) throws IOException {
            byte[] b = new byte[n]; int off = 0;
            while (off < n) { int r = is.read(b, off, n - off); if (r < 0) throw new IOException("host closed (stderr: " + ")"); off += r; }
            return b;
        }

        String session() { return session; }
        String deviceName() { return deviceName; }
        String stderrTail() { return new String(errTail.toByteArray(), StandardCharsets.UTF_8); }

        Map<String,Object> open(String path, Map<String,Object> params) throws IOException {
            LinkedHashMap<String,Object> req = new LinkedHashMap<>();
            req.put("path", path);
            if (params != null) req.put("params", params);
            Map<String,Object> r = request("open", req);
            session = (String) r.get("session");
            return r;
        }

        Map<String,Object> render(String tier, Map<String,Object> extra) throws IOException {
            LinkedHashMap<String,Object> req = new LinkedHashMap<>();
            req.put("session", session);
            req.put("tier", tier);
            req.put("format", "rgba8");
            req.put("display", "srgb");
            if (extra != null) req.putAll(extra);
            return request("render", req);
        }

        Map<String,Object> export(String path, String format, int quality) throws IOException {
            LinkedHashMap<String,Object> req = new LinkedHashMap<>();
            req.put("session", session);
            req.put("path", path);
            req.put("format", format);
            req.put("quality", quality);
            req.put("color_space", "sRGB");
            req.put("overwrite", true);
            return request("export_image", req);
        }

        void close() { if (proc != null) proc.destroy(); }
    }
}
