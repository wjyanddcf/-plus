package com.shanbaylens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * 悬浮窗：一个小圆球 + 一块可展开的讲解面板。
 * 用 TYPE_ACCESSIBILITY_OVERLAY，因此不需要「显示在其他应用上层」权限。
 */
public final class OverlayController {

    /** 供 MainActivity / OCR 服务跨组件拿当前实例（同进程） */
    public static volatile OverlayController CURRENT;

    private static final int C_BG = 0xFF191C21;
    private static final int C_TEXT = 0xFFE6E8EB;
    private static final int C_DIM = 0xFF9AA0A6;
    private static final int C_ACCENT = 0xFF8AB4F8;
    private static final int C_WARN = 0xFFFF8A80;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WordCache cache;

    private int sw;
    private int sh;

    private View bubble;
    private WindowManager.LayoutParams lpBubble;

    private LinearLayout panel;
    private WindowManager.LayoutParams lpPanel;
    private TextView wordTitle;
    private TextView statusText;
    private TextView bubbleLabel;
    private WebView web;
    private LinearLayout chipsBox;
    private HorizontalScrollView chipsScroll;
    private LinearLayout editRow;
    private EditText editWord;

    private boolean panelShown;
    private boolean webReady;
    private String pendingHtml;
    private boolean editing;

    private String currentWord = "";
    private String fullText = "";
    private boolean loading;
    private int gen;
    private long lastRenderAt;
    private LlmClient client;

    public OverlayController(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.cache = new WordCache(ctx);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        this.sw = dm.widthPixels;
        this.sh = dm.heightPixels;
    }

    // ---------------- 生命周期 ----------------

    public void attach() {
        CURRENT = this;
        if (bubble != null) return;
        try {
            bubble = buildBubble();
            lpBubble = new WindowManager.LayoutParams(
                    dp(54), dp(54),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lpBubble.gravity = Gravity.TOP | Gravity.START;
            lpBubble.x = sw - dp(68);
            lpBubble.y = (int) (sh * 0.32f);
            wm.addView(bubble, lpBubble);
        } catch (Exception e) {
            toast("悬浮窗创建失败：" + e.getMessage());
        }
    }

    public void detach() {
        if (CURRENT == this) CURRENT = null;
        if (client != null) {
            client.cancel();
            client = null;
        }
        try {
            if (panelShown && panel != null) wm.removeViewImmediate(panel);
        } catch (Exception ignored) {
        }
        panelShown = false;
        try {
            if (bubble != null) wm.removeViewImmediate(bubble);
        } catch (Exception ignored) {
        }
        bubble = null;
        if (web != null) {
            try {
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        panel = null;
        webReady = false;
    }

    // ---------------- 对外入口 ----------------

    /** 无障碍读到新单词时调用 */
    public void showWord(final String word, final List<String> candidates) {
        if (panelShown) setCandidates(candidates);
        if (word == null || word.trim().isEmpty()) {
            if (panelShown) setStatus("没识别到单词，点「换词」手动输入", true);
            return;
        }
        final String w = word.trim();
        final boolean changed = !w.equalsIgnoreCase(currentWord);
        if (changed && Prefs.clipClear(ctx)) {
            MemoryClip.clearIfOwned(ctx);
        }
        if (!changed && (loading || !fullText.isEmpty())) return;

        currentWord = w;
        if (wordTitle != null) wordTitle.setText(w);
        if (bubbleLabel != null) bubbleLabel.setText(w.substring(0, 1).toUpperCase(Locale.ROOT));

        if (!Prefs.autoPop(ctx) && !panelShown) return;

        if (Prefs.autoExplain(ctx)) {
            explain(w, false);
        } else {
            openPanel();
            setStatus("当前单词：" + w + "，点「重讲」生成", false);
        }
    }

    /** 强制重新生成（忽略缓存） */
    public void explainNow(String word) {
        if (TextUtils.isEmpty(word)) return;
        explain(word.trim(), true);
    }

    public void openPanel() {
        if (panelShown) return;
        ensurePanel();
        try {
            wm.addView(panel, lpPanel);
            panelShown = true;
            if (fullText.isEmpty() && !loading) {
                setStatus("点「重讲」生成讲解", false);
            }
        } catch (Exception e) {
            toast("面板打开失败：" + e.getMessage());
        }
    }

    public void closePanel() {
        if (!panelShown) return;
        hideEditor();
        try {
            wm.removeView(panel);
        } catch (Exception ignored) {
        }
        panelShown = false;
    }

    public boolean isPanelShown() {
        return panelShown;
    }

    // ---------------- 生成 ----------------

    private void explain(final String word, final boolean force) {
        final String w = word == null ? "" : word.trim();
        if (w.isEmpty()) return;

        final boolean changed = !w.equalsIgnoreCase(currentWord);
        if (changed && Prefs.clipClear(ctx)) {
            MemoryClip.clearIfOwned(ctx);
        }

        currentWord = w;
        if (wordTitle != null) wordTitle.setText(w);
        openPanel();

        if (client != null) client.cancel();
        final int myGen = ++gen;

        loading = true;
        fullText = "";
        lastRenderAt = 0;
        setStatus("…", false);

        if (!force) {
            String cached = cache.get(w);
            if (cached != null) {
                loading = false;
                fullText = cached;
                renderMarkdown(cached);
                String extra = syncClipboard(w, cached) ? " · 记忆已复制" : "";
                setStatus("缓存命中 · 点「重讲」可刷新" + extra, false);
                return;
            }
        }

        applyHtml(Html.placeholder("正在生成 " + w + " 的讲解…"));
        setStatus("连接模型…", false);
        final long t0 = SystemClock.elapsedRealtime();

        client = new LlmClient();
        client.stream(Prefs.baseUrl(ctx), Prefs.apiKey(ctx), Prefs.model(ctx),
                Prefs.systemPrompt(ctx), Prefs.userPrompt(w),
                Prefs.maxTokens(ctx), Prefs.temperature(ctx),
                new LlmClient.Cb() {
                    @Override
                    public void onDelta(String s) {
                        if (myGen != gen) return;
                        fullText = s;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastRenderAt > 240) {
                            lastRenderAt = now;
                            renderMarkdown(s);
                        }
                        setStatus("生成中 · " + s.length() + " 字", false);
                    }

                    @Override
                    public void onDone(String s) {
                        if (myGen != gen) return;
                        loading = false;
                        fullText = s;
                        if (s == null || s.trim().isEmpty()) {
                            setStatus("模型返回为空，换一个模型试试", true);
                            return;
                        }
                        cache.put(w, s);
                        renderMarkdown(s);

                        String extra = "";
                        if (syncClipboard(w, s)) {
                            extra = " · 记忆已复制";
                        }
                        double sec = (SystemClock.elapsedRealtime() - t0) / 1000.0;
                        setStatus(String.format(Locale.ROOT, "完成 · %.1fs · %s", sec, Prefs.model(ctx))
                                + extra, false);
                    }

                    @Override
                    public void onError(String m) {
                        if (myGen != gen) return;
                        loading = false;
                        setStatus(m, true);
                        applyHtml(Html.error(m));
                    }
                });
    }

    // ---------------- 渲染 ----------------

    /** 把「一句话记忆」里的短语 + 例句同步到剪贴板 */
    private boolean syncClipboard(String w, String md) {
        if (!Prefs.clipMemory(ctx)) return false;
        String clip = MemoryClip.build(w, md);
        return clip != null && MemoryClip.copy(ctx, clip);
    }

    private void renderMarkdown(String md) {
        applyHtml(MarkdownLite.toHtml(md));
    }

    private void applyHtml(String html) {
        if (web == null) return;
        if (!webReady) {
            pendingHtml = html;
            return;
        }
        try {
            web.evaluateJavascript("window.__set(" + JSONObject.quote(html) + ");", null);
        } catch (Exception ignored) {
        }
    }

    private void scrollTop() {
        if (web == null || !webReady) return;
        try {
            web.evaluateJavascript("window.__top();", null);
        } catch (Exception ignored) {
        }
    }

    private void setStatus(String s, boolean error) {
        if (statusText == null) return;
        statusText.setText(s);
        statusText.setTextColor(error ? C_WARN : C_DIM);
    }

    private void setCandidates(List<String> candidates) {
        if (chipsBox == null || chipsScroll == null) return;
        chipsBox.removeAllViews();
        if (candidates == null || candidates.size() < 2) {
            chipsScroll.setVisibility(View.GONE);
            return;
        }
        for (final String c : candidates) {
            TextView chip = new TextView(ctx);
            chip.setText(c);
            chip.setTextSize(12.5f);
            chip.setTextColor(C_TEXT);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setBackground(roundBg(0xFF262C35, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                currentWord = c;
                wordTitle.setText(c);
                chipsScroll.setVisibility(View.GONE);
                explain(c, true);
            });
            chipsBox.addView(chip);
        }
        chipsScroll.setVisibility(View.VISIBLE);
    }

    // ---------------- 视图构建 ----------------

    private View buildBubble() {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setGravity(Gravity.CENTER);

        bubbleLabel = new TextView(ctx);
        bubbleLabel.setText("词");
        bubbleLabel.setTextSize(13);
        bubbleLabel.setTextColor(0xFF0B0F14);
        bubbleLabel.setGravity(Gravity.CENTER);
        bubbleLabel.setBackground(roundBg(C_ACCENT, 27));

        wrap.addView(bubbleLabel, new LinearLayout.LayoutParams(dp(54), dp(54)));
        wrap.setAlpha(0.94f);
        wrap.setOnTouchListener(new DragTouch());
        return wrap;
    }

    private void ensurePanel() {
        if (panel != null) return;

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundBg(C_BG, 18));
        panel.setPadding(dp(2), dp(2), dp(2), dp(2));

        // ---- 标题栏 ----
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(8), dp(6), dp(4));

        wordTitle = new TextView(ctx);
        wordTitle.setText("");
        wordTitle.setTextSize(18);
        wordTitle.setTextColor(C_TEXT);
        wordTitle.setSingleLine(true);
        wordTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(wordTitle, titleLp);

        head.addView(actionBtn("重讲", C_ACCENT, v -> {
            if (!currentWord.isEmpty()) explain(currentWord, true);
        }));
        head.addView(actionBtn("换词", C_DIM, v -> toggleEditor()));
        head.addView(actionBtn("复制", C_DIM, v -> copyAll()));
        head.addView(actionBtn("×", C_DIM, v -> closePanel()));
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 换词输入行 ----
        editRow = new LinearLayout(ctx);
        editRow.setOrientation(LinearLayout.HORIZONTAL);
        editRow.setGravity(Gravity.CENTER_VERTICAL);
        editRow.setPadding(dp(10), dp(2), dp(6), dp(4));
        editRow.setVisibility(View.GONE);

        editWord = new EditText(ctx);
        editWord.setHint("输入单词，如 abandon");
        editWord.setTextColor(C_TEXT);
        editWord.setHintTextColor(0xFF6B7280);
        editWord.setTextSize(14);
        editWord.setSingleLine(true);
        editWord.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editWord.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        editRow.addView(editWord, editLp);
        editRow.addView(actionBtn("讲解", C_ACCENT, v -> submitEditor()));
        editRow.addView(actionBtn("取消", C_DIM, v -> hideEditor()));
        panel.addView(editRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 候选词 ----
        chipsScroll = new HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setVisibility(View.GONE);
        chipsBox = new LinearLayout(ctx);
        chipsBox.setOrientation(LinearLayout.HORIZONTAL);
        chipsBox.setPadding(dp(12), dp(2), dp(12), dp(6));
        chipsScroll.addView(chipsBox);
        panel.addView(chipsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 内容 ----
        web = new WebView(ctx);
        web.setBackgroundColor(C_BG);
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(false);
        ws.setLoadWithOverviewMode(false);
        ws.setUseWideViewPort(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                webReady = true;
                if (pendingHtml != null) {
                    String h = pendingHtml;
                    pendingHtml = null;
                    applyHtml(h);
                }
            }
        });
        web.loadDataWithBaseURL(null, Html.SHELL, "text/html", "utf-8", null);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        webLp.topMargin = dp(2);
        panel.addView(web, webLp);

        // ---- 状态栏 ----
        statusText = new TextView(ctx);
        statusText.setTextSize(11.5f);
        statusText.setTextColor(C_DIM);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setPadding(dp(14), dp(4), dp(14), dp(8));
        statusText.setText("就绪");
        panel.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int w = sw - dp(16);
        int h = (int) (sh * 0.62f);
        lpPanel = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lpPanel.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lpPanel.y = dp(6);
    }

    // ---------------- 换词输入 ----------------

    private void toggleEditor() {
        openPanel();
        if (editing) {
            hideEditor();
        } else {
            editing = true;
            editRow.setVisibility(View.VISIBLE);
            lpPanel.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try {
                wm.updateViewLayout(panel, lpPanel);
            } catch (Exception ignored) {
            }
            editWord.setText(currentWord);
            editWord.requestFocus();
            editWord.postDelayed(() -> {
                try {
                    InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(editWord, InputMethodManager.SHOW_IMPLICIT);
                } catch (Exception ignored) {
                }
            }, 120);
        }
    }

    private void submitEditor() {
        String w = editWord.getText().toString().trim();
        hideEditor();
        if (!w.isEmpty()) explain(w, true);
    }

    private void hideEditor() {
        if (!editing) return;
        editing = false;
        editRow.setVisibility(View.GONE);
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(editWord.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
        lpPanel.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            wm.updateViewLayout(panel, lpPanel);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 小工具 ----------------

    private void copyAll() {
        String text = fullText;
        if (text == null || text.isEmpty()) text = cache.get(currentWord);
        if (text == null || text.isEmpty()) {
            toast("还没有内容可复制");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("word", text));
                toast("已复制");
            }
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    private TextView actionBtn(String text, int color, View.OnClickListener l) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(12.5f);
        v.setTextColor(color);
        v.setPadding(dp(9), dp(6), dp(9), dp(6));
        v.setBackground(roundBg(0x1AFFFFFF, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        v.setLayoutParams(lp);
        v.setOnClickListener(l);
        return v;
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        try {
            Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    /** 小圆球拖拽：移动超过阈值算拖，否则算点击 */
    private final class DragTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lpBubble.x;
                    startY = lpBubble.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downX);
                    int dy = (int) (e.getRawY() - downY);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    lpBubble.x = clamp(startX + dx, -dp(10), sw - dp(40));
                    lpBubble.y = clamp(startY + dy, -dp(10), sh - dp(40));
                    try {
                        wm.updateViewLayout(bubble, lpBubble);
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        if (panelShown) closePanel();
                        else {
                            openPanel();
                            if (!currentWord.isEmpty() && fullText.isEmpty() && !loading) {
                                explain(currentWord, false);
                            }
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
