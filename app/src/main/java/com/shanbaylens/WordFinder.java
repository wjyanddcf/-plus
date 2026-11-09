package com.shanbaylens;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从无障碍节点树里猜“当前正在学的那个单词”。
 *
 * 扇贝的控件 id 经常变，所以这里不写死 id，而是用一整套启发式打分：
 *  - id 里带 word/title/head 的加分，带 sentence/meaning/phonetic 的减分
 *  - 单词越短、越靠屏幕上方、越像单个英文词，分越高
 *  - 可点击的（按钮）减分，EditText 加分（拼写模式）
 * 同时返回候选词列表，浮窗上用 chip 让用户一键纠正。
 */
public final class WordFinder {

    public static final class Result {
        public String word;
        public final List<String> candidates = new ArrayList<>();
    }

    private static final Pattern WORD = Pattern.compile("^[A-Za-z][A-Za-z'’\\- ]{1,39}$");
    private static final Pattern HAS_CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern VOWEL = Pattern.compile("[aeiouy]", Pattern.CASE_INSENSITIVE);

    private static final String[] GOOD_IDS = {"word", "headline", "title", "spell", "vocab", "term"};
    private static final String[] BAD_IDS = {"sentence", "example", "trans", "meaning",
            "definition", "def_", "phonetic", "audio", "sound", "progress", "hint", "option"};

    private WordFinder() {
    }

    public static Result find(AccessibilityNodeInfo root, int screenW, int screenH) {
        Result res = new Result();
        if (root == null) return res;

        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        int[] budget = {700};
        walk(root, 0, scores, display, budget, screenW, screenH);

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(scores.entrySet());
        Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());

        for (Map.Entry<String, Integer> e : entries) {
            if (res.candidates.size() >= 6) break;
            if (e.getValue() < 0) continue;
            res.candidates.add(display.get(e.getKey()));
        }

        if (!entries.isEmpty() && entries.get(0).getValue() >= 30) {
            res.word = display.get(entries.get(0).getKey());
        }
        return res;
    }

    private static void walk(AccessibilityNodeInfo node, int depth,
                             Map<String, Integer> scores, Map<String, String> display,
                             int[] budget, int sw, int sh) {
        if (node == null || depth > 40 || budget[0] <= 0) return;
        budget[0]--;

        CharSequence text = node.getText();
        if (text == null || text.length() == 0) {
            text = node.getContentDescription();
        }
        if (text != null && text.length() > 0) {
            String s = clean(text.toString());
            if (isWord(s)) {
                int sc = score(node, s, sw, sh);
                String key = s.toLowerCase(Locale.ROOT);
                Integer old = scores.get(key);
                if (old == null || sc > old) {
                    scores.put(key, sc);
                    display.put(key, s);
                }
            }
        }

        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) walk(child, depth + 1, scores, display, budget, sw, sh);
        }
    }

    private static String clean(String s) {
        String t = s.trim();
        t = t.replaceAll("^[\"'“”‘’\\[（(·•\\s]+", "");
        t = t.replaceAll("[\"'“”‘’\\]）)。，,;；:：\\s]+$", "");
        t = t.replaceAll("\\s+", " ");
        // 有的界面会把单词和音标放在同一个节点里："abandon /əˈbændən/"
        int slash = t.indexOf(" /");
        if (slash > 1) t = t.substring(0, slash).trim();
        return t;
    }

    private static boolean isWord(String s) {
        if (s.length() < 2 || s.length() > 40) return false;
        if (HAS_CJK.matcher(s).find()) return false;
        if (!WORD.matcher(s).matches()) return false;
        if (s.split(" ").length > 4) return false;
        return VOWEL.matcher(s).find();
    }

    private static int score(AccessibilityNodeInfo node, String s, int sw, int sh) {
        int sc = 0;

        String id = null;
        try {
            id = node.getViewIdResourceName();
        } catch (Exception ignored) {
        }
        String lid = id == null ? "" : id.toLowerCase(Locale.ROOT);

        for (String g : GOOD_IDS) if (lid.contains(g)) { sc += 110; break; }
        for (String b : BAD_IDS) if (lid.contains(b)) { sc -= 150; break; }

        int words = s.split(" ").length;
        if (words == 1) sc += 50;
        else if (words >= 3) sc -= 60;
        else sc -= 10;

        if (s.length() <= 14) sc += 20;
        if (Character.isUpperCase(s.charAt(0)) && words == 1) sc += 5;

        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception ignored) {
        }
        if (r.height() > 0) {
            if (r.top < sh * 0.45f) sc += 25;
            else if (r.top > sh * 0.62f) sc -= 45;
            if (r.width() > sw * 0.92f) sc -= 30;
        }

        if (node.isClickable()) sc -= 90;
        if (node.isEditable()) sc += 40;
        return sc;
    }
}
