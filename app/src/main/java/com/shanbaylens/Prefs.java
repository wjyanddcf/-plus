package com.shanbaylens;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 全部配置都存在本机 SharedPreferences 里，API Key 不出设备。
 */
public final class Prefs {

    private static final String NAME = "shanbay_lens";

    /** 预设：显示名、Base URL、默认模型 */
    public static final String[][] PRESETS = {
            {"智谱 GLM-4-Flash（免费）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"},
            {"硅基流动 SiliconFlow（有免费模型）", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"},
            {"DeepSeek（便宜，按量付费）", "https://api.deepseek.com/v1", "deepseek-chat"},
            {"OpenRouter（挑带 :free 的模型）", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free"},
            {"自定义（任何 OpenAI 兼容接口）", "", ""}
    };

    public static final int DEFAULT_PRESET = 0;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static SharedPreferences.Editor edit(Context c) {
        return sp(c).edit();
    }

    // ---------------- 模型 ----------------

    public static int preset(Context c) {
        return sp(c).getInt("preset", DEFAULT_PRESET);
    }

    public static String baseUrl(Context c) {
        String v = sp(c).getString("baseUrl", null);
        if (v != null && v.trim().length() > 0) return v.trim();
        return PRESETS[DEFAULT_PRESET][1];
    }

    public static String apiKey(Context c) {
        return sp(c).getString("apiKey", "").trim();
    }

    public static String model(Context c) {
        String v = sp(c).getString("model", null);
        if (v != null && v.trim().length() > 0) return v.trim();
        return PRESETS[DEFAULT_PRESET][2];
    }

    public static int maxTokens(Context c) {
        return sp(c).getInt("maxTokens", 4096);
    }

    public static float temperature(Context c) {
        return sp(c).getFloat("temperature", 0.5f);
    }

    // ---------------- 取词 / 触发 ----------------

    /** 监听的包名前缀，逗号分隔；空字符串表示所有 App */
    public static String pkgPrefix(Context c) {
        return sp(c).getString("pkgPrefix", "com.shanbay");
    }

    public static boolean autoPop(Context c) {
        return sp(c).getBoolean("autoPop", true);
    }

    public static boolean autoExplain(Context c) {
        return sp(c).getBoolean("autoExplain", true);
    }

    public static int debounce(Context c) {
        return sp(c).getInt("debounce", 700);
    }

    /** a11y / both / ocr */
    public static String source(Context c) {
        return sp(c).getString("source", "both");
    }

    /** 生成时先把面板隐藏一下，避免 OCR 把自己的浮窗也识别进去 */
    public static boolean hideWhileOcr(Context c) {
        return sp(c).getBoolean("hideWhileOcr", true);
    }

    /** 生成完成后自动把「一句话记忆」里的短语/例句复制到剪贴板 */
    public static boolean clipMemory(Context c) {
        return sp(c).getBoolean("clipMemory", true);
    }

    /** 下一个单词出现时，清掉自己上一次写进剪贴板的内容 */
    public static boolean clipClear(Context c) {
        return sp(c).getBoolean("clipClear", true);
    }

    // ---------------- 模板 ----------------

    public static String template(Context c) {
        String v = sp(c).getString("template", null);
        if (v != null && v.trim().length() > 0) return v;
        return defaultTemplate(c);
    }

    public static String defaultTemplate(Context c) {
        try (InputStream in = c.getAssets().open("template_default.txt")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "请讲解这个英语单词，给出音标、词性、中文释义和例句。";
        }
    }

    public static String systemPrompt(Context c) {
        return "你是英语词汇讲解助手。严格遵守下面这份讲解模板："
                + "只输出模板要求的内容，不要寒暄，不要说“好的/下面是”，"
                + "不要用 ``` 代码块包裹整体，不要输出与单词讲解无关的话。\n\n"
                + "===== 模板开始 =====\n"
                + template(c)
                + "\n===== 模板结束 =====\n";
    }

    public static String userPrompt(String word) {
        return "请按模板讲解这个单词：" + word + "\n（只输出讲解内容本身）";
    }
}
