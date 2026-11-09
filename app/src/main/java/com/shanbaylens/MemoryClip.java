package com.shanbaylens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * 剪贴板同步：
 *  - 生成完成后，把模板「七、一句话记忆」里的核心搭配 + 例句抽出来，压成纯文本丢进剪贴板；
 *  - 下一个单词出现时，把上一条清掉，避免粘到上一个词。
 *
 * 注意：Android 10+ 后台读不到剪贴板，所以无法确认里面还是不是我们写的，
 * 清理动作只在「本开关开启 且 本次运行内确实写过」之后才执行，把误伤面压到最小。
 * 用户若在中间复制了别的东西，也会被下一次清掉——不想这样就关掉这个开关。
 */
public final class MemoryClip {

    private static volatile boolean owned;

    private MemoryClip() {
    }

    // ---------------- 抽取 ----------------

    public static String build(String word, String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) return null;
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        int start = -1;
        int level = 0;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.isEmpty()) continue;
            if (l.contains("一句话记忆")) {
                start = i;
                level = headingLevel(l);
                break;
            }
        }
        if (start < 0) return null;

        List<String> phrases = new ArrayList<>();
        List<String> en = new ArrayList<>();
        List<String> cn = new ArrayList<>();
        int mode = 0; // 0 无, 1 核心搭配, 2 例句

        for (int i = start + 1; i < lines.length; i++) {
            String raw = lines[i];
            String l = raw.trim();
            if (l.isEmpty()) continue;

            // 1) 列表项先处理，避免 "- 例句：..." 之类误判
            if (isBullet(l)) {
                String item = trimStars(l.substring(2));
                if (item.isEmpty()) continue;
                if (mode == 1) {
                    phrases.add(item);
                } else if (mode == 2) {
                    addBilingual(item, en, cn);
                }
                continue;
            }

            // 2) 标题：同级别或更高级 = 出节
            int h = headingLevel(l);
            if (h > 0 && ((level == 0) || (h <= level))) {
                break;
            }

            // 3) 去掉 ** / # 之类装饰后再判断标记
            String n = trimStars(l.replaceAll("^[#\\s]+", ""));
            if (h > 0) {
                if (isMarker(n, "核心搭配") || isMarker(n, "核心短语") || isMarker(n, "固定搭配")) {
                    mode = 1;
                } else if (isMarker(n, "例句")) {
                    mode = 2;
                }
                continue;
            }

            if (isMarker(n, "核心搭配") || isMarker(n, "核心短语") || isMarker(n, "固定搭配")) {
                mode = 1;
                String rest = afterColon(n);
                if (rest != null) addPhrases(rest, phrases);
                continue;
            }
            if (isMarker(n, "例句")) {
                mode = 2;
                String rest = afterColon(n);
                if (rest != null) {
                    addBilingual(rest, en, cn);
                }
                continue;
            }
            if (n.startsWith("同义") || n.startsWith("易混") || n.startsWith("写作")
                    || n.startsWith("翻译必用") || n.startsWith("注意")) {
                mode = 0;
                continue;
            }

            // 4) 缩进的续行 = 上一条例句的中文翻译
            if (mode == 2 && indentOf(raw) >= 2 && !en.isEmpty()) {
                int idx = en.size() - 1;
                String old = cn.get(idx);
                cn.set(idx, old.isEmpty() ? l : old + " " + l);
            }
        }

        if (phrases.isEmpty() && en.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(512);
        sb.append("【").append(word).append("】\n");
        if (!phrases.isEmpty()) {
            sb.append("核心搭配：\n");
            for (String p : phrases) sb.append("- ").append(p).append('\n');
        }
        if (!en.isEmpty()) {
            sb.append("例句：\n");
            for (int i = 0; i < en.size(); i++) {
                sb.append("- ").append(en.get(i)).append('\n');
                String t = cn.get(i);
                if (t != null && !t.isEmpty()) sb.append("  ").append(t).append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ---------------- 剪贴板 ----------------

    /** 写入并标记为「自己写的」；返回是否成功 */
    public static boolean copy(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return false;
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("扇贝浮窗", text));
            owned = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只在剪贴板里还是我们上一条内容时才清空 */
    public static void clearIfOwned(Context c) {
        if (!owned) return;
        owned = false;
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    cm.clearPrimaryClip();
                } catch (Throwable ignored) {
                    // 部分 ROM 在后台不允许 clear，用下面的空内容兜底
                }
            }
            try {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            } catch (Throwable ignored) {
                // 后台写剪贴板被系统拦住，忽略：下一条复制时也会覆盖掉
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean ownsClipboard() {
        return owned;
    }

    // ---------------- 小工具 ----------------

    private static boolean isBullet(String l) {
        return l.startsWith("- ") || l.startsWith("• ") || l.startsWith("* ")
                || l.startsWith("＋ ") || l.startsWith("+ ");
    }

    private static boolean isMarker(String n, String marker) {
        return n.startsWith(marker);
    }

    private static String trimStars(String s) {
        String t = s == null ? "" : s.trim();
        int i = 0;
        while (i < t.length() && (t.charAt(i) == '*' || t.charAt(i) == '#' || t.charAt(i) == ' ')) i++;
        t = t.substring(i);
        int j = t.length();
        while (j > 0 && (t.charAt(j - 1) == '*' || t.charAt(j - 1) == ' ')) j--;
        return t.substring(0, j).trim();
    }

    private static void addPhrases(String rest, List<String> out) {
        for (String p : rest.split("[；;、]")) {
            String t = trimStars(p);
            if (!t.isEmpty()) out.add(t);
        }
    }

    private static void addBilingual(String item, List<String> en, List<String> cn) {
        int cjk = firstCjk(item);
        if (cjk > 1) {
            String a = item.substring(0, cjk).trim();
            String b = item.substring(cjk).trim();
            if (!a.isEmpty() && !b.isEmpty() && hasLetter(a)) {
                en.add(a);
                cn.add(b);
                return;
            }
        }
        en.add(item);
        cn.add("");
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) return true;
        }
        return false;
    }

    private static int firstCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) return i;
        }
        return -1;
    }

    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') n++;
        if (n >= 1 && n <= 6 && n < line.length() && line.charAt(n) == ' ') return n;
        return 0;
    }

    private static int indentOf(String raw) {
        int n = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == ' ') n++;
            else if (ch == '\t') n += 2;
            else break;
        }
        return n;
    }

    private static String afterColon(String line) {
        int k = Math.max(line.lastIndexOf('：'), line.lastIndexOf(':'));
        if (k < 0) return null;
        String rest = trimStars(line.substring(k + 1));
        if (rest.isEmpty()) return null;
        if (rest.startsWith("（") || rest.startsWith("(")) return null;
        return rest;
    }
}
