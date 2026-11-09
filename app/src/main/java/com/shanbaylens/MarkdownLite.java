package com.shanbaylens;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Markdown -> HTML 渲染器。
 * 只覆盖本模板实际用到的语法：# 标题、--- 虚线、| 表格、- 列表、1. 列表、
 * 行内 **粗体**、*斜体*、`代码`、[文字](链接)。
 * 不引入第三方库，保证工程零依赖炸掉。
 */
public final class MarkdownLite {

    private MarkdownLite() {
    }

    private static final class Ctx {
        final StringBuilder out = new StringBuilder(4096);
        final StringBuilder para = new StringBuilder();
        final StringBuilder li = new StringBuilder();
        final StringBuilder code = new StringBuilder();
        int list = 0; // 0 none, 1 ul, 2 ol
        boolean inCode = false;
    }

    public static String toHtml(String md) {
        if (md == null || md.trim().isEmpty()) return "";
        md = md.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = md.split("\n", -1);
        Ctx c = new Ctx();

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();

            if (c.inCode) {
                if (isFenceOpenTag(line)) {
                    c.out.append("<pre><code>").append(c.code).append("</code></pre>\n");
                    c.code.setLength(0);
                    c.inCode = false;
                } else {
                    c.code.append(esc(raw)).append('\n');
                }
                continue;
            }
            if (isFenceOpenTag(line)) {
                flushPara(c);
                closeList(c);
                c.inCode = true;
                c.code.setLength(0);
                continue;
            }

            if (line.isEmpty()) {
                flushPara(c);
                closeList(c);
                continue;
            }

            if (isHr(line)) {
                flushPara(c);
                closeList(c);
                c.out.append("<hr>\n");
                continue;
            }

            int h = headingLevel(line);
            if (h > 0) {
                flushPara(c);
                closeList(c);
                c.out.append("<h").append(h).append('>')
                        .append(inline(line.substring(h).trim()))
                        .append("</h").append(h).append(">\n");
                continue;
            }

            if (isTableStart(lines, i)) {
                flushPara(c);
                closeList(c);
                i = emitTable(c, lines, i);
                continue;
            }

            if (line.startsWith(">")) {
                flushPara(c);
                closeList(c);
                String q = line.substring(1).trim();
                c.out.append("<blockquote>").append(inline(q)).append("</blockquote>\n");
                continue;
            }

            if (isUl(line)) {
                flushPara(c);
                if (c.list != 1) {
                    closeList(c);
                    c.out.append("<ul>\n");
                    c.list = 1;
                }
                flushLi(c);
                c.li.append(inline(line.substring(2).trim()));
                continue;
            }

            if (isOl(line)) {
                flushPara(c);
                if (c.list != 2) {
                    closeList(c);
                    c.out.append("<ol>\n");
                    c.list = 2;
                }
                flushLi(c);
                c.li.append(inline(oLContent(line)));
                continue;
            }

            // 缩进行：算作上一条列表项的续行（例句下面那行中文翻译）
            if (c.list != 0 && indentOf(raw) >= 2) {
                c.li.append("<br>").append(inline(line));
                continue;
            }

            closeList(c);
            if (c.para.length() > 0) c.para.append("<br>");
            c.para.append(inline(line));
        }

        if (c.inCode) {
            c.out.append("<pre><code>").append(c.code).append("</code></pre>\n");
            c.code.setLength(0);
            c.inCode = false;
        }
        flushPara(c);
        closeList(c);
        return c.out.toString();
    }

    // ---------------- 状态处理 ----------------

    private static void flushPara(Ctx c) {
        if (c.para.length() == 0) return;
        c.out.append("<p>").append(c.para).append("</p>\n");
        c.para.setLength(0);
    }

    private static void flushLi(Ctx c) {
        if (c.li.length() == 0) return;
        c.out.append("<li>").append(c.li).append("</li>\n");
        c.li.setLength(0);
    }

    private static void closeList(Ctx c) {
        flushLi(c);
        if (c.list == 1) c.out.append("</ul>\n");
        else if (c.list == 2) c.out.append("</ol>\n");
        c.list = 0;
    }

    // ---------------- 语法判定 ----------------

    private static boolean isFenceOpenTag(String line) {
        return line.startsWith("```");
    }

    private static int indentOf(String raw) {
        int n = 0;
        while (n < raw.length() && (raw.charAt(n) == ' ' || raw.charAt(n) == '\t')) {
            n += (raw.charAt(n) == '\t') ? 2 : 1;
        }
        return n;
    }

    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') n++;
        if (n >= 1 && n <= 6 && n < line.length() && line.charAt(n) == ' ') return n;
        return 0;
    }

    private static boolean isHr(String line) {
        String s = line.replace(" ", "");
        if (s.length() < 3) return false;
        char c = s.charAt(0);
        if (c != '-' && c != '*' && c != '_') return false;
        for (int i = 1; i < s.length(); i++) if (s.charAt(i) != c) return false;
        return true;
    }

    private static boolean isUl(String line) {
        return line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")
                || line.startsWith("• ");
    }

    private static boolean isOl(String line) {
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        if (i == 0 || i > 3) return false;
        if (i >= line.length()) return false;
        char d = line.charAt(i);
        if (d != '.' && d != ')') return false;
        return i + 1 < line.length() && line.charAt(i + 1) == ' ';
    }

    private static String oLContent(String line) {
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        return line.substring(Math.min(i + 2, line.length())).trim();
    }

    private static boolean isTableStart(String[] lines, int i) {
        if (i + 1 >= lines.length) return false;
        if (!lines[i].contains("|")) return false;
        String sep = lines[i + 1].trim();
        if (sep.isEmpty() || !sep.matches("^[|\\s:\\-]+$")) return false;
        int dashes = 0;
        for (int k = 0; k < sep.length(); k++) if (sep.charAt(k) == '-') dashes++;
        return dashes >= 3;
    }

    private static int emitTable(Ctx c, String[] lines, int i) {
        List<String> head = splitRow(lines[i]);
        int j = i + 2;

        c.out.append("<table>\n<thead><tr>");
        for (String h : head) {
            c.out.append("<th>").append(inline(h)).append("</th>");
        }
        c.out.append("</tr></thead>\n<tbody>\n");

        while (j < lines.length) {
            String l = lines[j].trim();
            if (l.isEmpty() || !l.contains("|")) break;
            List<String> cells = splitRow(l);
            c.out.append("<tr>");
            for (String cell : cells) {
                c.out.append("<td>").append(inline(cell)).append("</td>");
            }
            c.out.append("</tr>\n");
            j++;
        }
        c.out.append("</tbody></table>\n");
        return j - 1;
    }

    private static List<String> splitRow(String line) {
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("\\|", -1);
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) out.add(p.trim());
        return out;
    }

    // ---------------- 行内 ----------------

    private static String inline(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 24);
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);

            if (ch == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i) {
                    out.append("<code>").append(esc(s.substring(i + 1, j))).append("</code>");
                    i = j + 1;
                    continue;
                }
            }

            if (ch == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int j = s.indexOf("**", i + 2);
                if (j > i) {
                    out.append("<strong>").append(inline(s.substring(i + 2, j))).append("</strong>");
                    i = j + 2;
                    continue;
                }
            }

            if (ch == '*' && i + 1 < s.length() && s.charAt(i + 1) != ' ') {
                int j = s.indexOf('*', i + 1);
                if (j > i + 1) {
                    out.append("<em>").append(esc(s.substring(i + 1, j))).append("</em>");
                    i = j + 1;
                    continue;
                }
            }

            if (ch == '[') {
                int close = s.indexOf(']', i);
                if (close > i && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int par = s.indexOf(')', close + 2);
                    if (par > close) {
                        out.append(esc(s.substring(i + 1, close)));
                        i = par + 1;
                        continue;
                    }
                }
            }

            out.append(escCh(ch));
            i++;
        }
        return out.toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) b.append(escCh(s.charAt(i)));
        return b.toString();
    }

    private static String escCh(char c) {
        switch (c) {
            case '&': return "&amp;";
            case '<': return "&lt;";
            case '>': return "&gt;";
            case '"': return "&quot;";
            default: return String.valueOf(c);
        }
    }
}
