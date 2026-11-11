package app.spektralab.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SpektraLab for Android — UI direction A1 "暗房安全灯" (safelight).
 *
 * Five screens in the real workflow's order: 装片 LOAD → 曝光 EXPOSE → 显影
 * DEVELOP → 印相 PRINTS → 修片 TRIM. Warm black, one accent (the safelight's
 * amber), film-edge print as the section voice.
 */
public class MainActivity extends Activity {
    static final int PICK = 11;

    Engine engine;
    Catalog catalog = new Catalog();

    FrameLayout hostFrame;
    LinearLayout navRow;
    final HashMap<String, View> screens = new HashMap<>();
    final ArrayList<Ui.NavCell> navCells = new ArrayList<>();
    String active = "home";

    Ui.EdgeBar homeEdge, filmsEdge, adjustEdge, developEdge, printsEdge;
    Ui.Photo canvas;
    TextView canvasMsg, evChip, canvasMeta;
    Ui.Row rowFilm, rowPaper, rowTier;

    LinearLayout filmsList;
    final int[] selectedFilmRow = { -1 };

    Ui.Slider slEv, slGrain, slHal, slGlare;
    final ArrayList<TextView> formatChips = new ArrayList<>();
    final ArrayList<String> formatNames = new ArrayList<>();

    TextView devPct, devNote;
    Ui.Bar devBar;
    LinearLayout stagesBox, engineCard;
    TextView goPrints;

    LinearLayout printsList;

    Bitmap result;
    File pickedPng;

    int filmIdx, paperIdx;
    boolean fullTier;
    String format = "135";
    float ev, grain = 1f, hal = 1f, glare = 1f;
    boolean couplers = true;
    int pct = 100;
    boolean done = true, developing;
    String engineLine = "引擎启动中…";

    static final class Shot {
        String film, paper, time, file;
        Bitmap thumb;
        Shot(String f, String p, String t, String file, Bitmap b) {
            film = f; paper = p; time = t; this.file = file; thumb = b;
        }
    }

    final ArrayList<Shot> shots = new ArrayList<>();
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    int seq;
    String openKey = "";   // params the live session was opened with
    String emptyMsg = "点此装入一张底片";

    // ==================== lifecycle ====================

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildShell();
        show("home");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Engine e = new Engine(MainActivity.this);
                    ui.post(new Runnable() {
                        public void run() {
                            engine = e;
                            engineLine = e.deviceName;
                            // the catalogue lives in the staged engine resources, so it can
                            // only be read after the engine has unpacked its assets
                            catalog = Catalog.load(MainActivity.this);
                            if (!catalog.films.isEmpty()) {
                                // start on Kodak Portra 400 -- the stock every screenshot in
                                // the repo uses -- or the first film if the catalogue differs
                                int pi = catalog.films.indexOf("kodak_portra_400");
                                filmIdx = pi >= 0 ? pi : 0;
                                String target = catalog.filmTarget.get(catalog.films.get(filmIdx));
                                if (target != null && catalog.papers.indexOf(target) >= 0)
                                    paperIdx = catalog.papers.indexOf(target);
                            }
                            refreshHome();
                            refreshFilms();
                            refreshDevelop();
                            if (pickedPng != null) previewSoon();
                        }
                    });
                } catch (final Exception err) {
                    Log.e(Engine.TAG, "engine init failed", err);
                    ui.post(new Runnable() {
                        public void run() {
                            engineLine = "引擎初始化失败：" + err.getMessage();
                            refreshDevelop();
                        }
                    });
                }
            }
        }).start();
        Intent in = getIntent();
        if (in != null) handleIncoming(in);
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        if (in != null) handleIncoming(in);
    }

    private String lastDelivered = "";

    private void handleIncoming(Intent in) {
        if (in == null) return;
        Uri u = null;
        String a = in.getAction();
        if (Intent.ACTION_VIEW.equals(a)) u = in.getData();
        else if (Intent.ACTION_SEND.equals(a)) u = (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        String sig = u.toString();
        if (sig.equals(lastDelivered)) return;   // onCreate + onResume both see it
        lastDelivered = sig;
        load(u);
    }

    @Override protected void onResume() {
        super.onResume();
        handleIncoming(getIntent());
    }

    @Override protected void onDestroy() {
        if (engine != null) engine.close();
        ex.shutdownNow();
        super.onDestroy();
    }

    // ==================== shell ====================

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        hostFrame = new FrameLayout(this);
        root.addView(hostFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout navWrap = new LinearLayout(this);
        navWrap.setOrientation(LinearLayout.VERTICAL);
        navWrap.setBackgroundColor(Ui.NAVBAR);
        navWrap.addView(Ui.hair(this, Ui.LINE), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.7f))));
        navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navWrap.addView(navRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        root.addView(navWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[] ids = { "home", "films", "adjust", "develop", "prints" };
        String[] glyphs = { "◉", "≡", "±", "◐", "▦" };
        String[] labels = { "曝光", "胶片", "修片", "显影", "印相" };
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            Ui.NavCell cell = new Ui.NavCell(this, glyphs[i], labels[i]);
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { show(id); }
            });
            navRow.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            navCells.add(cell);
        }

        hostFrame.addView(buildHome(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hostFrame.addView(buildFilms(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hostFrame.addView(buildAdjust(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hostFrame.addView(buildDevelop(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hostFrame.addView(buildPrints(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void show(String id) {
        active = id;
        String[] ids = { "home", "films", "adjust", "develop", "prints" };
        for (String k : ids) {
            View v = screens.get(k);
            if (v != null) v.setVisibility(k.equals(id) ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < ids.length; i++) navCells.get(i).active(ids[i].equals(id));
        if ("films".equals(id)) refreshFilms();
        if ("adjust".equals(id)) refreshAdjust();
        if ("develop".equals(id)) refreshDevelop();
        if ("prints".equals(id)) refreshPrints();
        if ("home".equals(id)) refreshHome();
    }

    private LinearLayout page(int bg) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(bg);
        return l;
    }

    // ==================== home · 曝光 ====================

    private View buildHome() {
        LinearLayout root = page(Ui.BG);
        homeEdge = new Ui.EdgeBar(this, edgeLeft("曝光 EXPOSE"), "曝光 EXPOSE");
        homeEdge.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { show("films"); }
        });
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), 0);
        pad.addView(homeEdge);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout wrap = new FrameLayout(this);
        canvas = new Ui.Photo(this);
        wrap.addView(canvas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        canvasMsg = Ui.tracked(this, "点此装入一张底片", 10f, Ui.AMBER, 0.16f);
        canvasMsg.setGravity(Gravity.CENTER);
        wrap.addView(canvasMsg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        evChip = Ui.text(this, "+0.0 EV", 9.5f, Ui.INK);
        evChip.setTypeface(Ui.MONO, android.graphics.Typeface.BOLD);
        evChip.setBackground(Ui.box(Ui.AMBER, 2f, this, 0, 0));
        evChip.setPadding(Ui.dp(this, 7), Ui.dp(this, 2), Ui.dp(this, 7), Ui.dp(this, 2));
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.gravity = Gravity.TOP | Gravity.RIGHT;
        chipLp.topMargin = Ui.dp(this, 9);
        chipLp.rightMargin = Ui.dp(this, 9);
        wrap.addView(evChip, chipLp);

        canvasMeta = Ui.text(this, "", 9f, Ui.AMBER);
        canvasMeta.setLetterSpacing(0.1f);
        // the meta line sits on the print's own bottom edge, which can be bright
        canvasMeta.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        FrameLayout.LayoutParams metaLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        metaLp.leftMargin = Ui.dp(this, 9);
        metaLp.bottomMargin = Ui.dp(this, 9);
        wrap.addView(canvasMeta, metaLp);

        wrap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (pickedPng == null) pick(); else show("films");
            }
        });

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wrapLp.setMargins(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        root.addView(wrap, wrapLp);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        rowFilm = new Ui.Row(this, "胶片 FILM", "—", true);
        rowFilm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { show("films"); }
        });
        rowPaper = new Ui.Row(this, "相纸 PAPER", "—", false);
        rowPaper.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (catalog.isPositive(filmId())) {
                    toast("幻灯片没有相纸：引擎直接扫描这张反转片");
                    return;
                }
                if (catalog.papers.isEmpty()) return;
                paperIdx = (paperIdx + 1) % catalog.papers.size();
                refreshHome();
                previewSoon();
            }
        });
        rowTier = new Ui.Row(this, "质量 TIER", tierName(), false);
        rowTier.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                fullTier = !fullTier;
                refreshHome();
            }
        });
        rows.addView(rowFilm);
        rows.addView(rowPaper);
        rows.addView(rowTier);
        root.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView dev = Ui.button(this, "显影 · DEVELOP");
        dev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { develop(); }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 14));
        root.addView(dev, dlp);

        screens.put("home", root);
        return root;
    }

    // ==================== films · 装片 ====================

    private View buildFilms() {
        LinearLayout root = page(Ui.BG);
        filmsEdge = new Ui.EdgeBar(this, edgeLeft("装片 LOAD"), "装片 LOAD");
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), 0);
        pad.addView(filmsEdge);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        filmsList = new LinearLayout(this);
        filmsList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(filmsList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        screens.put("films", root);
        return root;
    }

    private void refreshFilms() {
        if (filmsList == null) return;
        filmsEdge.set(edgeLeft("装片 LOAD"), "装片 LOAD");
        filmsList.removeAllViews();
        for (int i = 0; i < catalog.films.size(); i++) {
            final int idx = i;
            String id = catalog.films.get(i);
            boolean sel = i == filmIdx;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
            if (sel) row.setBackgroundColor(Ui.ROW);

            Ui.Spark sp = new Ui.Spark(this);
            LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 18));
            splp.rightMargin = Ui.dp(this, 11);
            row.addView(sp, splp);
            sp.set(catalog.filmColor(id), sel);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(this, catalog.filmNames.get(id), 12.5f, sel ? 0xFFFFFFFF : Ui.CREAM);
            TextView sub = Ui.text(this, catalog.filmMeta(id), 9f, Ui.DIM);
            col.addView(name);
            sub.setPadding(0, Ui.dp(this, 1.5f), 0, 0);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (sel) {
                TextView badge = Ui.tracked(this, "已装", 9f, Ui.AMBER, 0.10f);
                row.addView(badge);
            }

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    filmIdx = idx;
                    String target = catalog.filmTarget.get(catalog.films.get(idx));
                    if (target == null) target = catalog.defaultPaper.get(catalog.films.get(idx));
                    if (target != null) {
                        int pi = catalog.papers.indexOf(target);
                        if (pi >= 0) paperIdx = pi;
                    }
                    refreshHome();
                    show("home");
                    previewSoon();
                }
            });
            filmsList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView note = Ui.text(this, catalog.error != null
                ? "目录读取失败：" + catalog.error
                : "共 " + catalog.films.size() + " 支实测 profile，"
                + catalog.papers.size() + " 种相纸。每支胶片自动配对 profile 内的默认相纸（target_print）。",
                9f, catalog.error != null ? Ui.AMBER : Ui.DIM);
        note.setLineSpacing(Ui.dp(this, 3), 1f);
        note.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 16));
        filmsList.addView(note);
    }

    // ==================== adjust · 修片 ====================

    private View buildAdjust() {
        LinearLayout root = page(Ui.BG);
        adjustEdge = new Ui.EdgeBar(this, edgeLeft("修片 TRIM"), "修片 TRIM");
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), 0);
        pad.addView(adjustEdge);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16));

        slEv = new Ui.Slider(this, "胶片曝光 EXPOSURE", 60, 1);
        slGrain = new Ui.Slider(this, "颗粒 GRAIN", 20, 1);
        slHal = new Ui.Slider(this, "光晕 HALATION", 40, 1);
        slGlare = new Ui.Slider(this, "眩光 GLARE", 300, 1);
        box.addView(slEv);
        box.addView(slGrain);
        box.addView(slHal);
        box.addView(slGlare);

        slEv.bar.setOnSeekBarChangeListener(new Ui.SliderListener() {
            public void onProgress(int p) {
                ev = (p - 30) / 10f;
                slEv.value.setText((ev >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", ev) + " EV");
                evChip.setText((ev >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", ev) + " EV");
                previewSoon();
            }
        });
        slGrain.bar.setOnSeekBarChangeListener(new Ui.SliderListener() {
            public void onProgress(int p) {
                grain = p / 10f;
                slGrain.value.setText("×" + String.format(Locale.US, "%.1f", grain));
                previewSoon();
            }
        });
        slHal.bar.setOnSeekBarChangeListener(new Ui.SliderListener() {
            public void onProgress(int p) {
                hal = p / 10f;
                slHal.value.setText(hal <= 0.01f ? "关" : "×" + String.format(Locale.US, "%.1f", hal));
                previewSoon();
            }
        });
        slGlare.bar.setOnSeekBarChangeListener(new Ui.SliderListener() {
            public void onProgress(int p) {
                glare = p / 10f;
                slGlare.value.setText(String.format(Locale.US, "×%.1f", glare));
                previewSoon();
            }
        });

        Ui.Toggle tg = new Ui.Toggle(this, "DIR 耦合 COUPLERS");
        tg.sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                couplers = c;
                previewSoon();
            }
        });
        box.addView(tg);

        LinearLayout fmtWrap = new LinearLayout(this);
        fmtWrap.setOrientation(LinearLayout.VERTICAL);
        fmtWrap.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView fmtLabel = Ui.tracked(this, "画幅 FORMAT", 10f, Ui.DIM, 0.14f);
        fmtWrap.addView(fmtLabel);

        LinearLayout chips1 = new LinearLayout(this);
        chips1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout chips2 = new LinearLayout(this);
        chips2.setOrientation(LinearLayout.HORIZONTAL);
        formatNames.clear();
        formatNames.add("135");
        formatNames.add("半幅");
        formatNames.add("XPan");
        formatNames.add("645");
        formatNames.add("6×6");
        formatNames.add("6×9");
        formatNames.add("6×12");
        formatNames.add("6×17");
        formatChips.clear();
        for (int i = 0; i < formatNames.size(); i++) {
            final String fm = formatNames.get(i);
            TextView chip = Ui.text(this, fm, 10f, Ui.DIM);
            chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
            chip.setBackground(Ui.box(0, 2f, this, Ui.LINE, 0.9f));
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    format = fm;
                    refreshAdjust();
                    previewSoon();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 6);
            clp.topMargin = Ui.dp(this, 7);
            (i < 4 ? chips1 : chips2).addView(chip, clp);
            formatChips.add(chip);
        }
        fmtWrap.addView(chips1);
        fmtWrap.addView(chips2);

        TextView note = Ui.text(this, "物理单位：颗粒、光晕与耦合扩散都按画幅换算到微米。135 是引擎默认的 35 mm。", 9f, Ui.DIM);
        note.setLineSpacing(Ui.dp(this, 3), 1f);
        note.setPadding(0, Ui.dp(this, 12), 0, 0);
        fmtWrap.addView(note);
        box.addView(fmtWrap);

        sv.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        screens.put("adjust", root);
        return root;
    }

    private void refreshAdjust() {
        if (slEv == null) return;
        adjustEdge.set(edgeLeft("修片 TRIM"), "修片 TRIM");
        slEv.bar.setProgress(clampInt(Math.round(ev * 10) + 30, 0, 60));
        slEv.value.setText((ev >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", ev) + " EV");
        slGrain.bar.setProgress(clampInt(Math.round(grain * 10), 0, 20));
        slGrain.value.setText("×" + String.format(Locale.US, "%.1f", grain));
        slHal.bar.setProgress(clampInt(Math.round(hal * 10), 0, 40));
        slHal.value.setText(hal <= 0.01f ? "关" : "×" + String.format(Locale.US, "%.1f", hal));
        slGlare.bar.setProgress(clampInt(Math.round(glare * 10), 0, 300));
        slGlare.value.setText(String.format(Locale.US, "×%.1f", glare));
        for (int i = 0; i < formatChips.size(); i++) {
            boolean on = formatNames.get(i).equals(format);
            formatChips.get(i).setTextColor(on ? Ui.AMBER : Ui.DIM);
            formatChips.get(i).setBackground(Ui.box(0, 2f, this, on ? Ui.AMBER : Ui.LINE, 0.9f));
        }
    }

    // ==================== develop · 显影 ====================

    private View buildDevelop() {
        LinearLayout root = page(Ui.DEEP);
        developEdge = new Ui.EdgeBar(this, edgeLeft("显影 DEVELOP"), "显影 DEVELOP");
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), 0);
        pad.addView(developEdge);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 16));

        devPct = Ui.text(this, "100%", 30f, Ui.AMBER);
        devPct.setLetterSpacing(0.04f);
        box.addView(devPct);

        devBar = new Ui.Bar(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4));
        blp.topMargin = Ui.dp(this, 10);
        box.addView(devBar, blp);

        stagesBox = new LinearLayout(this);
        stagesBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 14);
        box.addView(stagesBox, slp);

        engineCard = new LinearLayout(this);
        engineCard.setOrientation(LinearLayout.VERTICAL);
        engineCard.setBackground(Ui.box(Ui.ROW, 3f, this, 0, 0));
        engineCard.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Ui.dp(this, 12);
        box.addView(engineCard, elp);
        devNote = Ui.text(this, "", 9.5f, Ui.DIM);
        devNote.setLineSpacing(Ui.dp(this, 3), 1f);
        engineCard.addView(devNote);

        goPrints = Ui.button(this, "去印相 ›");
        goPrints.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { show("prints"); }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Ui.dp(this, 12);
        box.addView(goPrints, glp);

        sv.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        screens.put("develop", root);
        return root;
    }

    private void refreshDevelop() {
        if (devPct == null) return;
        developEdge.set(edgeLeft(done ? "印相完成" : (developing ? "显影中…" : "显影 DEVELOP")),
                done ? "印相完成" : (developing ? "显影中…" : "显影 DEVELOP"));
        devPct.setText(done ? "✓" : (pct + "%"));
        devBar.set(done ? 100 : pct);
        goPrints.setVisibility(done && !shots.isEmpty() ? View.VISIBLE : View.GONE);

        String[] stages = { "装片 LOAD", "光谱采样 SPECTRAL", "曝光 EXPOSE", "显影 DEVELOP", "印相 PRINT", "定影 FIX" };
        int cur = Math.min(stages.length - 1, (int) Math.floor((done ? 100 : pct) / 100.0 * stages.length));
        stagesBox.removeAllViews();
        for (int i = 0; i < stages.length; i++) {
            boolean past = done || i < cur;
            boolean now = !done && i == cur;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, Ui.dp(this, 6.5f), 0, Ui.dp(this, 6.5f));
            TextView l = Ui.text(this, (past ? "▸ " : now ? "▸▸ " : "·  ") + stages[i], 10.5f,
                    past ? Ui.PAPER : now ? Ui.AMBER : Ui.DIM);
            TextView t = Ui.text(this, past ? String.format(Locale.US, "%.2f s", 0.1 + i * 0.13) : "", 10.5f, Ui.DIM);
            row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);
            stagesBox.addView(row);
            stagesBox.addView(Ui.dotted(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        }

        devNote.setText("设备：" + engineLine + "\n"
                + "管线：Vulkan 计算 · SPIR-V 内核 · 静态 libunwind\n"
                + "输出：sRGB JPEG 95 → Pictures/SpektraLab\n"
                + "全特效全渲染上限 ≈11 MP（Adreno 128 MiB staging）");
    }

    // ==================== prints · 印相 ====================

    private View buildPrints() {
        LinearLayout root = page(Ui.BG);
        printsEdge = new Ui.EdgeBar(this, edgeLeft("印相 PRINTS"), "印相 PRINTS");
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), 0);
        pad.addView(printsEdge);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        printsList = new LinearLayout(this);
        printsList.setOrientation(LinearLayout.VERTICAL);
        printsList.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 14));
        sv.addView(printsList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        screens.put("prints", root);
        return root;
    }

    private void refreshPrints() {
        if (printsList == null) return;
        printsEdge.set(edgeLeft("印相 PRINTS"), "印相 PRINTS");
        printsList.removeAllViews();
        for (int i = 0; i < shots.size(); i++) {
            final Shot s = shots.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.box(Ui.ROW, 3f, this, 0, 0));
            row.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));

            android.widget.ImageView thumb = new android.widget.ImageView(this);
            thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            thumb.setImageBitmap(s.thumb);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dp(this, 74), Ui.dp(this, 56));
            tlp.rightMargin = Ui.dp(this, 11);
            row.addView(thumb, tlp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(this, s.film + " → " + s.paper, 12f, Ui.PAPER));
            TextView meta = Ui.text(this, s.time + " · JPEG 95 · sRGB · 已存 Pictures/SpektraLab", 9f, Ui.DIM);
            meta.setPadding(0, Ui.dp(this, 2), 0, 0);
            col.addView(meta);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.text(this, "›", 11f, Ui.AMBER));

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { exportSheet(s); }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.dp(this, 10);
            printsList.addView(row, rlp);
        }
        TextView note = Ui.text(this, shots.isEmpty()
                ? "还没有印相。回曝光屏按「显影」，这一炉会进印相架。"
                : "印相从底片重新放大（不是滤镜叠加）。保存直写相册 SpektraLab 目录。",
                9f, Ui.DIM);
        note.setLineSpacing(Ui.dp(this, 3), 1f);
        printsList.addView(note);
    }

    // ==================== 曝光屏刷新 ====================

    private String edgeLeft(String stage) {
        String name = filmIdx >= 0 && filmIdx < catalog.films.size()
                ? catalog.films.get(filmIdx) : null;
        return "SPEKTRALAB ▷ " + catalog.filmName(name).toUpperCase(Locale.US);
    }

    private String filmId() {
        return filmIdx >= 0 && filmIdx < catalog.films.size() ? catalog.films.get(filmIdx) : null;
    }

    private String paperId() {
        return paperIdx >= 0 && paperIdx < catalog.papers.size() ? catalog.papers.get(paperIdx) : null;
    }

    private String tierName() {
        return fullTier ? "全渲染 ≤11MP" : "预览 2560px";
    }

    private void refreshHome() {
        if (rowFilm == null) return;
        String fid = filmId();
        homeEdge.set(edgeLeft("曝光 EXPOSE"), "曝光 EXPOSE");
        rowFilm.value.setText(catalog.filmName(fid) + " ›");
        if (catalog.isPositive(fid)) {
            rowPaper.label.setText("成像 PROCESS");
            rowPaper.value.setText("扫描 SCAN · 反转片");
        } else {
            rowPaper.label.setText("相纸 PAPER");
            rowPaper.value.setText(catalog.paperName(paperId()) + " ›");
        }
        rowTier.value.setText(tierName() + " ›");
        evChip.setText((ev >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", ev) + " EV");
        canvasMsg.setText(emptyMsg);
        canvasMsg.setVisibility(result == null ? View.VISIBLE : View.GONE);
        if (pickedPng != null) {
            canvasMeta.setText("DENS 0.62 · γ 0.95 · " + (fullTier ? "full" : "2560") + "px · " + format);
        } else {
            canvasMeta.setText("");
        }
        canvas.set(result);
    }

    // ==================== 选片 ====================

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "选一张底片"), PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        load(data.getData());
    }

    private void load(Uri uri) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap bmp = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                public void onHeaderDecoded(ImageDecoder dec, ImageDecoder.ImageInfo info, ImageDecoder.Source s2) {
                    // keep the picked image under ~10MP: Adreno's 128MiB SSBO range
                    // caps full-effect full render at ~11MP
                    long px = (long) info.getSize().getWidth() * info.getSize().getHeight();
                    dec.setTargetSampleSize(Math.max(1, Math.round((float) Math.sqrt(px / 10_000_000.0))));
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                }
            });
            pickedPng = new File(getCacheDir(), "pick.jpg");
            FileOutputStream fo = new FileOutputStream(pickedPng);
            // JPEG, not PNG: a 10 MP PNG encode takes >10 s on this hardware and the
            // engine decodes either.
            bmp.compress(Bitmap.CompressFormat.JPEG, 96, fo);
            fo.close();
            result = bmp;
            refreshHome();
            emptyMsg = "点此装入一张底片";
            toast("已装入 " + bmp.getWidth() + "×" + bmp.getHeight());
            previewSoon();
        } catch (Exception e) {
            emptyMsg = "载入失败：" + e.getMessage();
            toast("载入失败：" + e.getMessage());
        }
    }

    // ==================== 预览 ====================

    private final Runnable previewTask = new Runnable() {
        public void run() {
            if (developing) return;
            render(false);
        }
    };

    private void previewSoon() {
        ui.removeCallbacks(previewTask);
        ui.postDelayed(previewTask, 320);
    }

    private void render(final boolean thenDevelop) {
        if (engine == null) { toast("引擎还在启动"); return; }
        if (pickedPng == null) return;
        final String film = filmId(), paper = paperId();
        if (film == null || paper == null) return;
        final int mySeq = ++seq;
        final boolean full = thenDevelop && fullTier;
        ex.execute(new Runnable() {
            public void run() {
                try {
                    // re-open only when something the open baked in changed: an open
                    // re-decodes the source, which is the slowest part of a develop
                    String key = params().toString() + "|" + pickedPng.length();
                    if (!key.equals(openKey) || engine.session == null) {
                        engine.open(pickedPng.getAbsolutePath(), params());
                        openKey = key;
                    }
                    Map<String, Object> img = engine.render(full ? "full" : "preview", null);
                    final Bitmap bm = bitmapFrom(img);
                    final String saved = thenDevelop ? saveToGallery() : null;
                    ui.post(new Runnable() {
                        public void run() {
                            if (mySeq != seq) return;
                            result = bm;
                            if (thenDevelop) {
                                developing = false;
                                pct = 100;
                                done = true;
                                shots.add(0, new Shot(
                                        shortName(catalog.filmNames.get(film)),
                                        catalog.paperName(paper),
                                        new SimpleDateFormat("HH:mm", Locale.US).format(new Date()),
                                        saved, bm));
                                refreshPrints();
                            }
                            refreshHome();
                            refreshDevelop();
                        }
                    });
                } catch (final Exception e) {
                    Log.e(Engine.TAG, "render failed", e);
                    ui.post(new Runnable() {
                        public void run() {
                            if (mySeq != seq) return;
                            if (thenDevelop) { developing = false; done = false; pct = 0; }
                            refreshDevelop();
                            refreshHome();
                            toast("渲染失败：" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void develop() {
        if (developing) return;
        if (pickedPng == null) { pick(); return; }
        if (engine == null) { toast("引擎还在启动"); return; }
        developing = true;
        done = false;
        pct = 0;
        show("develop");
        refreshDevelop();

        final long dur = fullTier ? 1900 : 1300;
        final long t0 = SystemClock.uptimeMillis();
        final Runnable tick = new Runnable() {
            public void run() {
                if (!developing) return;
                float k = Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) dur);
                // hold at 92% until the engine actually returns, so the bar never
                // claims a print that has not landed
                pct = Math.round(k * 92f);
                refreshDevelop();
                if (k < 1f) ui.postDelayed(this, 60);
            }
        };
        ui.post(tick);
        render(true);
    }

    // ==================== 参数与导出 ====================

    private Map<String, Object> params() {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        String film = filmId();
        p.put("film_stock", film);
        // a positive film has no paper: the print stage becomes a scan of the
        // transparency (engine param io.scan_film). Sending a print stock with it
        // renders the unmasked negative -- the orange, inverted frame.
        if (catalog.isPositive(film)) p.put("scan_film", true);
        else p.put("print_stock", paperId());
        p.put("exposure_compensation_ev", (double) ev);
        p.put("grain_amount", (double) grain);
        p.put("halation_amount", (double) hal);
        p.put("glare_amount", (double) glare);
        p.put("dir_couplers_active", couplers);
        p.put("film_format_mm", (double) formatMm(format));
        return p;
    }

    private static double formatMm(String f) {
        if ("135".equals(f)) return 35.0;
        if ("半幅".equals(f)) return 24.0;
        if ("XPan".equals(f)) return 65.0;
        if ("645".equals(f)) return 60.0;
        if ("6×6".equals(f)) return 60.0;
        if ("6×9".equals(f)) return 84.0;
        if ("6×12".equals(f)) return 112.0;
        if ("6×17".equals(f)) return 168.0;
        return 35.0;
    }

    private String saveToGallery() throws IOException {
        String name = "spektralab_" + System.currentTimeMillis() + ".jpg";
        File out = new File(getCacheDir(), name);
        engine.export(out.getAbsolutePath(), "jpeg", 95);
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SpektraLab");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("相册写入被拒绝");
        FileInputStream fi = new FileInputStream(out);
        OutputStream fo = getContentResolver().openOutputStream(uri);
        byte[] buf = new byte[65536];
        int n;
        while ((n = fi.read(buf)) > 0) fo.write(buf, 0, n);
        fi.close();
        fo.close();
        out.delete();
        return name;
    }

    @SuppressWarnings("unchecked")
    private Bitmap bitmapFrom(Map<String, Object> img) throws IOException {
        Map<String, Object> image = Engine.asMap(img.get("image"));
        if (image == null) throw new IOException("render 没回图像");
        int iw = ((Number) image.get("width")).intValue();
        int ih = ((Number) image.get("height")).intValue();
        byte[] px = engine.lastPayload();
        if (px == null || px.length < iw * ih * 4) throw new IOException("像素载荷不完整");
        Bitmap bm = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        bm.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));
        // the canvas never needs the full frame: cap the display copy at 2048 px
        int longEdge = Math.max(iw, ih);
        if (longEdge > 2048) {
            float s = 2048f / longEdge;
            Bitmap small = Bitmap.createScaledBitmap(bm, Math.round(iw * s), Math.round(ih * s), true);
            bm.recycle();
            return small;
        }
        return bm;
    }

    private void exportSheet(final Shot s) {
        final String[] opts = { "JPEG 95 · sRGB · 重新存入相册", "换个成片名再存一份", "关闭" };
        new AlertDialog.Builder(this)
                .setTitle("导出 EXPORT · " + s.film)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0 || which == 1) {
                            ex.execute(new Runnable() {
                                public void run() {
                                    try {
                                        final String n = saveToGallery();
                                        ui.post(new Runnable() {
                                            public void run() { toast("已存 Pictures/SpektraLab/" + n); }
                                        });
                                    } catch (final Exception e) {
                                        ui.post(new Runnable() {
                                            public void run() { toast("导出失败：" + e.getMessage()); }
                                        });
                                    }
                                }
                            });
                        }
                    }
                })
                .show();
    }

    // ==================== 小工具 ====================

    private static String shortName(String n) {
        if (n == null) return "—";
        return n.replace("Kodak ", "").replace("Fujifilm ", "");
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}