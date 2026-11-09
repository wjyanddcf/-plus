package com.shanbaylens;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 截屏 OCR 取词（无障碍读不到时的备选通道）。
 * 只截屏幕上半部分做识别，图片不出设备，用完即丢。
 */
public class OcrCaptureService extends Service {

    private static final String CHANNEL = "ocr";
    private static final int NOTI_ID = 43;
    private static final long MIN_INTERVAL_MS = 1400;

    private static final Pattern WORD = Pattern.compile("^[A-Za-z][A-Za-z'’\\- ]{1,39}$");
    private static final Pattern HAS_CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern VOWEL = Pattern.compile("[aeiouy]", Pattern.CASE_INSENSITIVE);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private TextRecognizer recognizer;

    private volatile boolean busy;
    private long lastAt;
    private int screenW;
    private int screenH;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra("code", Activity.RESULT_OK);
        Intent data = intent.getParcelableExtra("data");
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!foreground()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (projection != null) {
            return START_STICKY; // 已经在跑了
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            toast("截屏授权失败，请重新点「授权截屏」");
            stopSelf();
            return START_NOT_STICKY;
        }

        thread = new HandlerThread("ocr-capture");
        thread.start();
        bg = new Handler(thread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, bg);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        startCapture();
        return START_STICKY;
    }

    private boolean foreground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "截屏取词", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("扇贝浮窗 · 截屏取词")
                    .setContentText("正在识别屏幕上的单词（图片不出本机）")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTI_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void startCapture() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            stopSelf();
            return;
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        int density = dm.densityDpi;

        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onFrame, bg);

        try {
            display = projection.createVirtualDisplay("shanbay-ocr",
                    screenW, screenH, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, bg);
        } catch (Exception e) {
            toast("无法开始截屏：" + e.getMessage());
            stopSelf();
        }
    }

    private void onFrame(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (img == null) return;

        long now = SystemClock.elapsedRealtime();
        if (busy || now - lastAt < MIN_INTERVAL_MS) {
            img.close();
            return;
        }
        lastAt = now;
        busy = true;

        Bitmap full = null;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            buf.rewind();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int rowPixels = Math.max(1, rowStride / Math.max(1, pixelStride));

            full = Bitmap.createBitmap(rowPixels, screenH, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buf);
            if (rowPixels != screenW) {
                Bitmap cut = Bitmap.createBitmap(full, 0, 0, screenW, screenH);
                full.recycle();
                full = cut;
            }
        } catch (Throwable t) {
            if (full != null) full.recycle();
            busy = false;
            img.close();
            return;
        }
        img.close();

        // 只识别上半屏：单词卡片通常在上方，也能避开自己的浮窗
        int roiH = Math.max(1, (int) (screenH * 0.48));
        Bitmap roi;
        try {
            roi = Bitmap.createBitmap(full, 0, 0, screenW, roiH);
        } catch (Exception e) {
            full.recycle();
            busy = false;
            return;
        }
        full.recycle();

        try {
            recognizer.process(InputImage.fromBitmap(roi, 0))
                    .addOnSuccessListener(t -> handle(t, roiH))
                    .addOnFailureListener(e -> {
                    })
                    .addOnCompleteListener(t -> {
                        roi.recycle();
                        busy = false;
                    });
        } catch (Exception e) {
            roi.recycle();
            busy = false;
        }
    }

    private void handle(Text text, int roiH) {
        if (text == null) return;
        if ("a11y".equals(Prefs.source(this))) return; // 只用无障碍时，OCR 结果不生效
        String best = null;
        int bestScore = -999;
        List<String> cands = new ArrayList<>();

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String s = clean(line.getText());
                if (!isWord(s)) continue;
                Rect r = line.getBoundingBox();
                int sc = 0;
                if (r != null) {
                    if (r.top < roiH * 0.42f) sc += 30;
                    else sc -= 35;
                    if (r.width() > screenW * 0.85f) sc -= 25;
                }
                int words = s.split(" ").length;
                if (words == 1) sc += 45;
                else if (words >= 3) sc -= 50;
                if (s.length() <= 14) sc += 15;

                if (sc > bestScore) {
                    bestScore = sc;
                    best = s;
                }
                if (sc >= 0 && cands.size() < 5 && !cands.contains(s)) cands.add(s);
            }
        }

        if (best == null || bestScore < 20) {
            return;
        }
        final String word = best;
        final List<String> list = cands;
        ui.post(() -> {
            OverlayController c = OverlayController.CURRENT;
            if (c != null) c.showWord(word, list);
        });
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("^[\"'“”‘’\\[（(·•\\s]+", "");
        t = t.replaceAll("[\"'“”‘’\\]）)。，,;；:：\\s]+$", "");
        t = t.replaceAll("\\s+", " ");
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

    private void toast(final String s) {
        ui.post(() -> {
            try {
                android.widget.Toast.makeText(getApplicationContext(), s,
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void onDestroy() {
        try {
            if (display != null) display.release();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        try {
            if (recognizer != null) recognizer.close();
        } catch (Exception ignored) {
        }
        if (thread != null) thread.quitSafely();
        display = null;
        reader = null;
        projection = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
