package app.spektralab.mobile;

import android.app.Activity;
import android.util.Log;

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

/**
 * The host sidecar: libspektralab_host.so (packaged as a jniLib so it can be
 * exec'd from nativeLibraryDir) driven over the frame protocol.
 */
final class Engine {
    static final String TAG = "SpektraLab";
    final Activity act;
    Process proc;
    InputStream in;
    OutputStream out;
    InputStream err;
    final ByteArrayOutputStream errTail = new ByteArrayOutputStream();
    String session;
    String deviceName = "?";
    String devicePhone = "";
    byte[] lastPayload;

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
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            is.close();
            os.close();
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
        in = proc.getInputStream();
        out = proc.getOutputStream();
        err = proc.getErrorStream();
        Map<String, Object> hello = request("hello", map());
        Map<String, Object> backend = asMap(hello.get("backend"));
        deviceName = String.valueOf(backend != null ? backend.get("device_name") : "?");
        Log.i(TAG, "hello ok, backend=" + deviceName);
        Thread t = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[4096];
                int n;
                try {
                    while ((n = err.read(buf)) > 0) {
                        errTail.write(buf, 0, n);
                        if (errTail.size() > 16384) errTail.reset();
                    }
                } catch (IOException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    synchronized void ensureAlive() throws IOException {
        if (proc == null || !proc.isAlive()) {
            session = null;
            start(new File(act.getFilesDir(), "engine"));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    Map<String, Object> open(String path, Map<String, Object> params) throws IOException {
        LinkedHashMap<String, Object> req = new LinkedHashMap<>();
        req.put("path", path);
        if (params != null) req.put("params", params);
        Map<String, Object> r = request("open", req);
        session = (String) r.get("session");
        return r;
    }

    Map<String, Object> render(String tier, Map<String, Object> extra) throws IOException {
        LinkedHashMap<String, Object> req = new LinkedHashMap<>();
        req.put("session", session);
        req.put("tier", tier);
        req.put("format", "rgba8");
        req.put("display", "srgb");
        if (extra != null) req.putAll(extra);
        return request("render", req);
    }

    Map<String, Object> export(String path, String format, int quality) throws IOException {
        LinkedHashMap<String, Object> req = new LinkedHashMap<>();
        req.put("session", session);
        req.put("path", path);
        req.put("format", format);
        req.put("quality", quality);
        req.put("color_space", "sRGB");
        req.put("overwrite", true);
        return request("export_image", req);
    }

    void close() {
        if (proc != null) proc.destroy();
    }

    byte[] lastPayload() { return lastPayload; }

    String stderrTail() { return new String(errTail.toByteArray(), StandardCharsets.UTF_8); }

    synchronized Map<String, Object> request(String method, Map<String, Object> params) throws IOException {
        ensureAlive();
        int reqId = (int) (System.nanoTime() & 0x7fffffff);
        String header = "{\"id\":" + reqId + ",\"method\":\"" + esc(method) + "\",\"params\":" + toJson(params) + "}";
        byte[] hb = header.getBytes(StandardCharsets.UTF_8);
        ByteBuffer ph = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        ph.putInt(hb.length).putInt(0);
        out.write(ph.array());
        out.write(hb);
        out.flush();
        Map<String, Object> resp = readFrameHeader();
        Object rid = resp.get("id");
        if (rid == null || ((Number) rid).intValue() != reqId)
            throw new IOException("protocol desync, got " + resp);
        if (!Boolean.TRUE.equals(resp.get("ok"))) {
            Map<String, Object> e = asMap(resp.get("error"));
            throw new IOException(method + ": " + (e != null ? e.get("code") + ": " + e.get("message") : String.valueOf(resp)));
        }
        return asMap(resp.get("result"));
    }

    Map<String, Object> readFrameHeader() throws IOException {
        byte[] p = readFully(in, 8);
        ByteBuffer bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        int hl = bb.getInt(), plen = bb.getInt();
        byte[] hb = readFully(in, hl);
        byte[] payload = plen > 0 ? readFully(in, plen) : null;
        lastPayload = payload;
        return asMap(miniJson(new String(hb, StandardCharsets.UTF_8)));
    }

    static byte[] readFully(InputStream is, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = is.read(b, off, n - off);
            if (r < 0) throw new IOException("host closed");
            off += r;
        }
        return b;
    }

    static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---- minimal recursive JSON (objects/arrays/strings/numbers/bool/null) ----
    static Object miniJson(String s) { int[] i = {0}; return val(s, i); }

    static Object val(String s, int[] i) {
        skip(s, i);
        char c = s.charAt(i[0]);
        if (c == '{') {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            i[0]++;
            skip(s, i);
            if (s.charAt(i[0]) == '}') { i[0]++; return m; }
            while (true) {
                skip(s, i);
                String k = str(s, i);
                skip(s, i);
                i[0]++; // ':'
                m.put(k, val(s, i));
                skip(s, i);
                if (s.charAt(i[0]) == ',') { i[0]++; continue; }
                i[0]++;
                return m;
            }
        }
        if (c == '[') {
            ArrayList<Object> a = new ArrayList<>();
            i[0]++;
            skip(s, i);
            if (s.charAt(i[0]) == ']') { i[0]++; return a; }
            while (true) {
                a.add(val(s, i));
                skip(s, i);
                if (s.charAt(i[0]) == ',') { i[0]++; continue; }
                i[0]++;
                return a;
            }
        }
        if (c == '"') return str(s, i);
        int st = i[0];
        while (i[0] < s.length() && ",]}".indexOf(s.charAt(i[0])) < 0) i[0]++;
        String t = s.substring(st, i[0]).trim();
        if ("true".equals(t)) return Boolean.TRUE;
        if ("false".equals(t)) return Boolean.FALSE;
        if ("null".equals(t)) return null;
        try { return Double.parseDouble(t); } catch (Exception e) { return t; }
    }

    static void skip(String s, int[] i) {
        while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++;
    }

    static String str(String s, int[] i) {
        StringBuilder b = new StringBuilder();
        i[0]++;
        while (s.charAt(i[0]) != '"') {
            char ch = s.charAt(i[0]);
            if (ch == '\\') {
                i[0]++;
                ch = s.charAt(i[0]);
                if (ch == 'n') b.append('\n');
                else if (ch == 't') b.append('\t');
                else if (ch == 'u') { b.append((char) Integer.parseInt(s.substring(i[0] + 1, i[0] + 5), 16)); i[0] += 4; }
                else b.append(ch);
            } else b.append(ch);
            i[0]++;
        }
        i[0]++;
        return b.toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        if (v instanceof Map) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append('"').append(esc(e.getKey())).append("\":").append(toJson(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (v instanceof List) {
            StringBuilder b = new StringBuilder("[");
            boolean f = true;
            for (Object o : (List<Object>) v) {
                if (!f) b.append(',');
                f = false;
                b.append(toJson(o));
            }
            return b.append(']').toString();
        }
        return '"' + esc(String.valueOf(v)) + '"';
    }
}