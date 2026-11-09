package com.shanbaylens;

/** 浮窗里 WebView 的渲染外壳 + 样式。 */
public final class Html {

    private Html() {
    }

    private static final String CSS =
            "body{margin:0;padding:12px 14px 30px;background:#191c21;color:#e6e8eb;"
                    + "font:15px/1.72 -apple-system,'PingFang SC','Noto Sans CJK SC','Microsoft YaHei',sans-serif;"
                    + "-webkit-text-size-adjust:100%;word-wrap:break-word;overflow-wrap:break-word}"
                    + "h1{font-size:20px;color:#8ab4f8;margin:8px 0 10px;line-height:1.45}"
                    + "h2{font-size:17px;color:#8ab4f8;margin:20px 0 8px;padding-top:12px;"
                    + "border-top:1px solid #2b3038;line-height:1.45}"
                    + "h3{font-size:15.5px;color:#fdd663;margin:15px 0 6px}"
                    + "h4,h5,h6{font-size:14.5px;color:#fdd663;margin:12px 0 6px}"
                    + "p{margin:8px 0}"
                    + "hr{border:0;border-top:1px dashed #3a4048;margin:15px 0}"
                    + "ul,ol{margin:8px 0;padding-left:22px}"
                    + "li{margin:3px 0}"
                    + "table{width:100%;border-collapse:collapse;margin:10px 0;font-size:13px}"
                    + "th,td{border:1px solid #333a44;padding:6px 7px;vertical-align:top;text-align:left}"
                    + "th{background:#222831;color:#cfe3ff;font-weight:600}"
                    + "tr:nth-child(even) td{background:#1d2229}"
                    + "code{background:#262c35;padding:1px 5px;border-radius:4px;"
                    + "font-family:monospace;font-size:13px;color:#a5d6ff}"
                    + "pre{background:#20252d;padding:10px;border-radius:8px;overflow:auto}"
                    + "pre code{background:none;padding:0}"
                    + "blockquote{margin:8px 0;padding:6px 10px;border-left:3px solid #4a5568;color:#b8c0cc}"
                    + "a{color:#8ab4f8}"
                    + ".dim{color:#8b93a1}"
                    + ".err{color:#ff8a80}"
                    + ".spinner{display:inline-block;width:10px;height:10px;border:2px solid #8ab4f8;"
                    + "border-right-color:transparent;border-radius:50%;animation:spin .8s linear infinite;"
                    + "vertical-align:middle;margin-right:8px}"
                    + "@keyframes spin{to{transform:rotate(360deg)}}";

    public static final String SHELL = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">"
            + "<style>" + CSS + "</style></head><body><div id=\"c\"></div>"
            + "<script>"
            + "window.__set=function(h){var c=document.getElementById('c');c.innerHTML=h;};"
            + "window.__top=function(){window.scrollTo(0,0);};"
            + "</script></body></html>";

    public static String placeholder(String msg) {
        return "<p class=\"dim\"><span class=\"spinner\"></span>" + esc(msg) + "</p>";
    }

    public static String error(String msg) {
        return "<p class=\"err\">" + esc(msg) + "</p>"
                + "<p class=\"dim\">检查：网络是否可用 / API Key 是否有效 / 模型名与 Base URL 是否匹配。</p>";
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
