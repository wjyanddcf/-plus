package com.shanbaylens;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

/**
 * 读屏取词：监听目标 App 的界面变化，抓到当前单词后交给浮窗。
 */
public class ShanbayA11yService extends AccessibilityService {

    private static volatile ShanbayA11yService INSTANCE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OverlayController overlay;
    private Runnable pending;
    private String lastWord = "";
    private long lastAt;

    public static ShanbayA11yService instance() {
        return INSTANCE;
    }

    public OverlayController overlay() {
        return overlay;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        if (overlay == null) overlay = new OverlayController(this);
        overlay.attach();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || overlay == null) return;
        if ("ocr".equals(Prefs.source(this))) return; // 只用截屏 OCR 时，读屏不抢词
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;

        String filters = Prefs.pkgPrefix(this);
        if (filters != null && filters.trim().length() > 0) {
            boolean match = false;
            for (String f : filters.split(",")) {
                String p = f.trim();
                if (p.isEmpty()) continue;
                if (pkg.toString().startsWith(p)) {
                    match = true;
                    break;
                }
            }
            if (!match) return;
        }

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return;
        }
        schedule();
    }

    private void schedule() {
        if (pending != null) handler.removeCallbacks(pending);
        pending = () -> {
            pending = null;
            scan();
        };
        int delay = Math.max(120, Prefs.debounce(this));
        handler.postDelayed(pending, delay);
    }

    private void scan() {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return;
        }
        if (root == null) return;

        WordFinder.Result r = null;
        try {
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            r = WordFinder.find(root, sw, sh);
        } catch (Exception e) {
            return;
        } finally {
            try {
                root.recycle();
            } catch (Exception ignored) {
            }
        }

        if (r == null || r.word == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (r.word.equalsIgnoreCase(lastWord) && now - lastAt < 8000) {
            return;
        }
        lastWord = r.word.toLowerCase(Locale.ROOT);
        lastAt = now;
        overlay.showWord(r.word, r.candidates);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        INSTANCE = null;
        if (overlay != null) {
            overlay.detach();
            overlay = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        if (overlay != null) {
            overlay.detach();
            overlay = null;
        }
        if (pending != null) handler.removeCallbacks(pending);
        super.onDestroy();
    }
}
