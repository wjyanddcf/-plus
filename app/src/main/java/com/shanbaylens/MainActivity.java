package com.shanbaylens;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 设置页：填模型、选取词方式、改模板、开无障碍。
 */
public class MainActivity extends Activity {

    private static final int REQ_PROJ = 1001;
    private static final int REQ_NOTI = 1002;

    private LinearLayout root;
    private TextView statusView;
    private TextView testOutput;

    private Spinner spPreset;
    private EditText eBase;
    private EditText eKey;
    private EditText eModel;
    private EditText eMaxTok;
    private EditText eTemp;

    private EditText ePkg;
    private EditText eDebounce;
    private RadioGroup rgSource;
    private Switch cbAutoPop;
    private Switch cbAutoExplain;
    private Switch cbClipMemory;
    private Switch cbClipClear;

    private EditText eTemplate;
    private EditText eTest;

    private int presetIndex = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0F1216);
        sv.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, dp(26), p, dp(48));

        sv.addView(root);
        setContentView(sv);

        buildUi();
        loadValues();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTI);
        }
    }

    // ---------------- UI ----------------

    private void buildUi() {
        TextView title = new TextView(this);
        title.setText("扇贝浮窗");
        title.setTextSize(24);
        title.setTextColor(0xFFE8EAED);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        add(title, 0);

        TextView sub = new TextView(this);
        sub.setText("背单词时实时读屏 → 浮窗按你的模板讲解");
        sub.setTextSize(12.5f);
        sub.setTextColor(0xFF9AA0A6);
        add(sub, 4);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(0xFF8AB4F8);
        statusView.setPadding(dp(10), dp(10), dp(10), dp(10));
        statusView.setBackground(round(0xFF1A1F26, 10));
        add(statusView, 14);

        // ==== 1. 无障碍 ====
        head("① 开启读屏");
        note("装好后必须开无障碍，App 才能看到扇贝里正在显示的单词。");
        add(button("打开「无障碍」设置", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), 8);

        // ==== 2. 模型 ====
        head("② 模型（免费额度即可）");
        note("填一个 OpenAI 兼容接口。推荐智谱 GLM-4-Flash：注册后在控制台拿 API Key，免费。");

        String[] names = new String[Prefs.PRESETS.length];
        for (int i = 0; i < Prefs.PRESETS.length; i++) names[i] = Prefs.PRESETS[i][0];
        spPreset = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
        spPreset.setAdapter(ad);
        spPreset.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                presetIndex = pos;
                if (pos < Prefs.PRESETS.length - 1) {
                    eBase.setText(Prefs.PRESETS[pos][1]);
                    eModel.setText(Prefs.PRESETS[pos][2]);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        add(spPreset, 8);

        eBase = input("Base URL，例如 https://open.bigmodel.cn/api/paas/v4", false);
        add(labeled("Base URL", eBase), 10);

        eKey = input("API Key", false);
        eKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(labeled("API Key（只存在本机）", eKey), 10);

        eModel = input("模型名，例如 glm-4-flash", false);
        add(labeled("模型名", eModel), 10);

        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        eMaxTok = input("4096", false);
        eMaxTok.setInputType(InputType.TYPE_CLASS_NUMBER);
        eTemp = input("0.5", false);
        eTemp.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        nums.addView(labeled("max_tokens", eMaxTok), weight(1));
        nums.addView(labeled("temperature", eTemp), weight(1));
        add(nums, 10);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("保存设置", v -> save(true)), weight(1));
        TextView gap = new TextView(this);
        gap.setWidth(dp(10));
        row.addView(gap);
        row.addView(button("测试连接", v -> testModel()), weight(1));
        add(row, 14);

        testOutput = new TextView(this);
        testOutput.setTextSize(12);
        testOutput.setTextColor(0xFF9AA0A6);
        testOutput.setPadding(dp(10), dp(8), dp(10), dp(8));
        add(testOutput, 6);

        // ==== 3. 取词 ====
        head("③ 取词方式");
        note("默认「无障碍」最快最准。某些界面读不到时切到「截屏 OCR」。");

        rgSource = new RadioGroup(this);
        rgSource.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbA = new RadioButton(this);
        rbA.setId(View.generateViewId());
        rbA.setText("只用无障碍读屏（最快，推荐）");
        rbA.setTextColor(0xFFE8EAED);
        rbA.setTag("a11y");
        RadioButton rbB = new RadioButton(this);
        rbB.setId(View.generateViewId());
        rbB.setText("两者都用（无障碍为主，OCR 兜底）");
        rbB.setTextColor(0xFFE8EAED);
        rbB.setTag("both");
        RadioButton rbO = new RadioButton(this);
        rbO.setId(View.generateViewId());
        rbO.setText("只用截屏 OCR");
        rbO.setTextColor(0xFFE8EAED);
        rbO.setTag("ocr");
        rgSource.addView(rbA);
        rgSource.addView(rbB);
        rgSource.addView(rbO);
        add(rgSource, 8);

        ePkg = input("包名前缀，逗号分隔；留空＝所有 App", false);
        add(labeled("只监听这些 App（默认 com.shanbay）", ePkg), 10);

        eDebounce = input("700", false);
        eDebounce.setInputType(InputType.TYPE_CLASS_NUMBER);
        add(labeled("取词防抖（毫秒，越大越省）", eDebounce), 10);

        cbAutoPop = new Switch(this);
        cbAutoPop.setText("识别到新单词就自动弹出面板");
        cbAutoPop.setTextColor(0xFFE8EAED);
        cbAutoPop.setTextSize(14);
        add(cbAutoPop, 10);

        cbAutoExplain = new Switch(this);
        cbAutoExplain.setText("自动生成讲解（关闭则点「重讲」才生成）");
        cbAutoExplain.setTextColor(0xFFE8EAED);
        cbAutoExplain.setTextSize(14);
        add(cbAutoExplain, 4);

        add(button("授权截屏 OCR", v -> requestProjection()), 12);
        add(button("停止截屏 OCR", v -> stopService(new Intent(this, OcrCaptureService.class))), 6);

        // ==== 4. 模板 ====
        head("④ 讲解模板");
        note("这就是浮窗里显示的内容格式。可以整段替换，改完点上面的「保存设置」。");

        eTemplate = input("", true);
        eTemplate.setMinLines(8);
        eTemplate.setGravity(Gravity.TOP | Gravity.START);
        eTemplate.setTypeface(Typeface.MONOSPACE);
        eTemplate.setTextSize(11.5f);
        eTemplate.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        add(eTemplate, 8);

        LinearLayout trow = new LinearLayout(this);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        trow.addView(button("恢复默认模板", v -> eTemplate.setText(Prefs.defaultTemplate(this))), weight(1));
        TextView gap2 = new TextView(this);
        gap2.setWidth(dp(10));
        trow.addView(gap2);
        trow.addView(button("查看模板全文", v -> showTemplate()), weight(1));
        add(trow, 8);

        // ==== 5. 剪贴板同步 ====
        head("⑤ 剪贴板同步");
        note("生成完成后，自动把「七、一句话记忆」里的核心搭配和例句复制出来，换到下一个词时清掉上一条。");

        cbClipMemory = new Switch(this);
        cbClipMemory.setText("自动复制「一句话记忆」的短语与例句");
        cbClipMemory.setTextColor(0xFFE8EAED);
        cbClipMemory.setTextSize(14);
        add(cbClipMemory, 8);

        cbClipClear = new Switch(this);
        cbClipClear.setText("换词时清除上一次复制的内容");
        cbClipClear.setTextColor(0xFFE8EAED);
        cbClipClear.setTextSize(14);
        add(cbClipClear, 4);

        // ==== 6. 试用 ====
        head("⑥ 试用");
        note("开启无障碍后，在这里输入一个词，直接看浮窗效果。");
        eTest = input("abandon", false);
        add(eTest, 8);
        add(button("在浮窗里讲这个词", v -> tryWord()), 8);
        add(button("清空讲解缓存", v -> {
            new WordCache(this).clear();
            toast("缓存已清空");
        }), 6);
        add(button("导出 / 备份设置（复制到剪贴板）", v -> exportSettings()), 6);

        TextView foot = new TextView(this);
        foot.setTextSize(11);
        foot.setTextColor(0xFF6B7280);
        foot.setPadding(0, dp(24), 0, 0);
        foot.setText("说明：本 App 仅在本机读取屏幕上的单词文本，词条内容发送给你自己配置的模型接口。"
                + "截图只在设备内做 OCR，不上传。请仅用于个人学习。");
        add(foot, 0);
    }

    // ==== 事件 ====

    private void save(boolean feedback) {
        String base = eBase.getText().toString().trim();
        String model = eModel.getText().toString().trim();
        int maxTok;
        float temp;
        int deb;
        try {
            maxTok = Integer.parseInt(eMaxTok.getText().toString().trim());
        } catch (Exception e) {
            maxTok = 4096;
        }
        try {
            temp = Float.parseFloat(eTemp.getText().toString().trim());
        } catch (Exception e) {
            temp = 0.5f;
        }
        try {
            deb = Integer.parseInt(eDebounce.getText().toString().trim());
        } catch (Exception e) {
            deb = 700;
        }

        Prefs.edit(this)
                .putInt("preset", presetIndex)
                .putString("baseUrl", base)
                .putString("apiKey", eKey.getText().toString().trim())
                .putString("model", model)
                .putInt("maxTokens", Math.max(256, maxTok))
                .putFloat("temperature", Math.max(0f, Math.min(2f, temp)))
                .putString("pkgPrefix", ePkg.getText().toString().trim())
                .putInt("debounce", Math.max(100, deb))
                .putBoolean("autoPop", cbAutoPop.isChecked())
                .putBoolean("autoExplain", cbAutoExplain.isChecked())
                .putBoolean("clipMemory", cbClipMemory.isChecked())
                .putBoolean("clipClear", cbClipClear.isChecked())
                .putString("source", selectedSource())
                .putString("template", eTemplate.getText().toString())
                .apply();

        if (feedback) {
            toast("已保存");
            refreshStatus();
        }
    }

    private String selectedSource() {
        View v = findViewById(rgSource.getCheckedRadioButtonId());
        if (v == null) return "both";
        Object tag = v.getTag();
        return tag == null ? "both" : tag.toString();
    }

    private String sourceLabel() {
        String s = Prefs.source(this);
        if ("ocr".equals(s)) return "只用OCR";
        if ("both".equals(s)) return "无障碍+OCR";
        return "只用无障碍";
    }

    private void loadValues() {
        presetIndex = Prefs.preset(this);
        spPreset.setSelection(Math.max(0, Math.min(presetIndex, Prefs.PRESETS.length - 1)), false);

        eBase.setText(Prefs.baseUrl(this));
        eKey.setText(Prefs.apiKey(this));
        eModel.setText(Prefs.model(this));
        eMaxTok.setText(String.valueOf(Prefs.maxTokens(this)));
        eTemp.setText(String.valueOf(Prefs.temperature(this)));
        ePkg.setText(Prefs.pkgPrefix(this));
        eDebounce.setText(String.valueOf(Prefs.debounce(this)));
        cbAutoPop.setChecked(Prefs.autoPop(this));
        cbAutoExplain.setChecked(Prefs.autoExplain(this));
        cbClipMemory.setChecked(Prefs.clipMemory(this));
        cbClipClear.setChecked(Prefs.clipClear(this));

        String src = Prefs.source(this);
        for (int i = 0; i < rgSource.getChildCount(); i++) {
            View v = rgSource.getChildAt(i);
            if (v instanceof RadioButton && src.equals(String.valueOf(v.getTag()))) {
                rgSource.check(v.getId());
                break;
            }
        }
        if (rgSource.getCheckedRadioButtonId() == -1 && rgSource.getChildCount() > 0) {
            rgSource.check(rgSource.getChildAt(0).getId());
        }

        eTemplate.setText(Prefs.template(this));
        if (eTest.getText().length() == 0) eTest.setText("");
        refreshStatus();
    }

    private void testModel() {
        save(false);
        if (Prefs.apiKey(this).isEmpty()) {
            testOutput.setText("先填 API Key。");
            return;
        }
        testOutput.setText("测试中…");
        new LlmClient().stream(Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                "你是连通性测试助手。",
                "只回复四个字：连接正常",
                32, 0.1,
                new LlmClient.Cb() {
                    @Override
                    public void onDelta(String full) {
                        testOutput.setText("测试中… " + full);
                    }

                    @Override
                    public void onDone(String full) {
                        testOutput.setText("可用 ✓  模型返回：" + full);
                    }

                    @Override
                    public void onError(String message) {
                        testOutput.setText("失败 ✗  " + message);
                    }
                });
    }

    private void requestProjection() {
        if (Prefs.source(this).equals("a11y")) {
            toast("提示：取词方式当前是「无障碍」，OCR 只在切到 OCR 时生效");
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            toast("系统不支持屏幕捕获");
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJ) {
            if (resultCode == RESULT_OK && data != null) {
                Intent i = new Intent(this, OcrCaptureService.class);
                i.putExtra("code", resultCode);
                i.putExtra("data", data);
                startForegroundService(i);
                toast("截屏 OCR 已启动，切到扇贝试试");
            } else {
                toast("已取消授权");
            }
        }
    }

    private void tryWord() {
        String w = eTest.getText().toString().trim();
        if (w.isEmpty()) {
            toast("先输入一个单词");
            return;
        }
        save(false);
        OverlayController c = OverlayController.CURRENT;
        if (c == null) {
            toast("请先在系统设置里开启本 App 的无障碍服务");
            return;
        }
        c.showWord(w, null);
        c.explainNow(w);
        toast("已发送到浮窗");
    }

    private void showTemplate() {
        View sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(Prefs.template(this));
        tv.setTextSize(12);
        tv.setTextColor(0xFFE8EAED);
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        ((ScrollView) sv).addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("当前模板")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void exportSettings() {
        StringBuilder sb = new StringBuilder();
        sb.append("baseUrl=").append(Prefs.baseUrl(this)).append('\n');
        sb.append("model=").append(Prefs.model(this)).append('\n');
        sb.append("maxTokens=").append(Prefs.maxTokens(this)).append('\n');
        sb.append("temperature=").append(Prefs.temperature(this)).append('\n');
        sb.append("pkgPrefix=").append(Prefs.pkgPrefix(this)).append('\n');
        sb.append("source=").append(Prefs.source(this)).append('\n');
        sb.append("debounce=").append(Prefs.debounce(this)).append('\n');
        sb.append("autoPop=").append(Prefs.autoPop(this)).append('\n');
        sb.append("autoExplain=").append(Prefs.autoExplain(this)).append('\n');
        sb.append("apiKey=").append(Prefs.apiKey(this).isEmpty() ? "(未填)" : "(已填，已隐藏)").append('\n');
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("settings", sb.toString()));
            toast("已复制到剪贴板");
        }
    }

    // ---------------- 小工具 ----------------

    private boolean a11yEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String target = getPackageName() + "/" + ShanbayA11yService.class.getName();
            return s.contains(target) || s.contains(getPackageName() + "/.ShanbayA11yService");
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshStatus() {
        boolean a11y = a11yEnabled();
        boolean running = ShanbayA11yService.instance() != null;
        String key = Prefs.apiKey(this).isEmpty() ? "未填" : "已填";
        statusView.setText(String.format(Locale.ROOT,
                "无障碍：%s　·　浮窗：%s\n模型：%s　·　Key：%s　·　取词：%s",
                a11y ? (running ? "已连接" : "已开启") : "未开启",
                OverlayController.CURRENT != null ? "运行中" : "未运行",
                Prefs.model(this), key, sourceLabel()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save(false);
    }

    private TextView head(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(16);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFFE8EAED);
        add(v, 26);
        return v;
    }

    private TextView note(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(11.5f);
        v.setTextColor(0xFF8B93A1);
        v.setPadding(0, dp(4), 0, 0);
        add(v, 0);
        return v;
    }

    private EditText input(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(13);
        e.setTextColor(0xFFE8EAED);
        e.setHintTextColor(0xFF6B7280);
        e.setBackground(round(0xFF1A1F26, 8));
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (!multiline) e.setSingleLine(true);
        return e;
    }

    private View labeled(String label, View field) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11);
        l.setTextColor(0xFF8B93A1);
        l.setPadding(dp(2), 0, 0, dp(3));
        box.addView(l);
        box.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private void add(View v, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        root.addView(v, lp);
    }

    private android.graphics.drawable.GradientDrawable round(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
