package com.shanbaylens;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 OpenAI 兼容 chat/completions 客户端，支持 SSE 流式输出。
 * 不依赖 OkHttp/Retrofit，只用 HttpURLConnection + org.json。
 */
public final class LlmClient {

    public interface Cb {
        void onDelta(String fullSoFar);

        void onDone(String full);

        void onError(String message);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private volatile boolean cancelled;
    private long lastEmit;

    public void cancel() {
        cancelled = true;
    }

    public void stream(final String baseUrl, final String apiKey, final String model,
                       final String system, final String user,
                       final int maxTokens, final double temperature, final Cb cb) {
        cancelled = false;
        lastEmit = 0;
        POOL.execute(() -> {
            try {
                run(baseUrl, apiKey, model, system, user, maxTokens, temperature, cb);
            } catch (Throwable t) {
                final String m = friendly(t);
                MAIN.post(() -> {
                    if (!cancelled) cb.onError(m);
                });
            }
        });
    }

    private void run(String baseUrl, String apiKey, String model,
                     String system, String user,
                     int maxTokens, double temperature, Cb cb) throws Exception {

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("还没填 API Key（设置里粘一个就能用）");
        }

        JSONObject req = new JSONObject();
        req.put("model", model);
        req.put("stream", true);
        req.put("temperature", temperature);
        req.put("max_tokens", maxTokens);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        req.put("messages", msgs);

        HttpURLConnection con = (HttpURLConnection) new URL(endpoint(baseUrl)).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(20000);
        con.setReadTimeout(180000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setRequestProperty("Accept", "text/event-stream");
        con.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        con.setRequestProperty("User-Agent", "ShanbayLens/1.0 (Android)");

        byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
        con.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = con.getOutputStream()) {
            os.write(body);
        }

        int code = con.getResponseCode();
        if (code < 200 || code > 299) {
            String errBody = readAll(con.getErrorStream());
            throw new IllegalStateException("HTTP " + code + hint(code) + " " + clip(errBody, 240));
        }

        String ct = con.getContentType();
        StringBuilder full = new StringBuilder();
        if (ct != null && ct.toLowerCase().contains("event-stream")) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (cancelled) return;
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith(":")) continue;
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) break;
                    String delta = extractDelta(payload);
                    if (delta == null || delta.isEmpty()) continue;
                    full.append(delta);
                    emit(cb, full);
                }
            }
        } else {
            String all = readAll(con.getInputStream());
            String content = extractMessage(all);
            if (content == null) {
                throw new IllegalStateException("无法解析模型响应：" + clip(all, 240));
            }
            full.append(content);
            emit(cb, full);
        }

        if (cancelled) return;
        final String done = clean(full.toString());
        MAIN.post(() -> cb.onDone(done));
    }

    private void emit(Cb cb, StringBuilder full) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastEmit < 90 && full.length() > 30) return;
        lastEmit = now;
        final String snap = full.toString();
        MAIN.post(() -> {
            if (!cancelled) cb.onDelta(snap);
        });
    }

    // ---------------- 解析 ----------------

    private static String extractDelta(String payload) {
        try {
            JSONObject o = new JSONObject(payload);
            if (o.has("error") && !o.isNull("error")) {
                throw new IllegalStateException("接口返回错误：" + clip(o.get("error").toString(), 200));
            }
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return null;
            JSONObject c0 = ch.getJSONObject(0);
            JSONObject delta = c0.optJSONObject("delta");
            if (delta != null) {
                String s = delta.optString("content", "");
                return s.isEmpty() ? null : s;
            }
            JSONObject msg = c0.optJSONObject("message");
            if (msg != null) {
                String s = msg.optString("content", "");
                return s.isEmpty() ? null : s;
            }
            return null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            return null; // 半包/心跳，忽略
        }
    }

    private static String extractMessage(String all) {
        try {
            JSONObject o = new JSONObject(all);
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return null;
            JSONObject c0 = ch.getJSONObject(0);
            JSONObject msg = c0.optJSONObject("message");
            if (msg != null) return msg.optString("content", null);
            return c0.optString("text", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉模型偶尔加的 ``` 包裹和开场白 */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            t = t.trim();
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3).trim();
        }
        return t;
    }

    private static String endpoint(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.isEmpty()) throw new IllegalArgumentException("Base URL 为空");
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    private static String hint(int code) {
        switch (code) {
            case 400: return "（请求格式被拒，检查模型名）";
            case 401: return "（API Key 无效或已过期）";
            case 402: return "（余额不足）";
            case 403: return "（无权限访问该模型）";
            case 404: return "（Base URL 或模型名不对）";
            case 429: return "（额度用完或请求太频繁）";
            case 500:
            case 502:
            case 503: return "（服务端暂时不可用）";
            default: return "";
        }
    }

    private static String friendly(Throwable t) {
        if (t instanceof SocketTimeoutException) {
            return "请求超时：模型太久没响应，换个模型或缩短模板试试";
        }
        if (t instanceof UnknownHostException) {
            return "连不上网络：检查是否断网 / 该域名是否需要代理";
        }
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
