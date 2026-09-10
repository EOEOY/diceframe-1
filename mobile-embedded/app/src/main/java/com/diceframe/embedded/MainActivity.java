package com.diceframe.embedded;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;

/**
 * 嵌入模式 POC:把主仓库 Python 后端跑在本机 127.0.0.1:18000,
 * WebView 加载后端直接服务的完整 WebUI(不依赖任何外部服务器,除 AI API 外离线可用)。
 */
public class MainActivity extends Activity {

    private static final String SERVER_URL = "http://127.0.0.1:18000/";
    private static final String ASSET_ROOT = "diceframe_backend";
    private static final int BOOT_TIMEOUT_SECONDS = 120;

    private TextView status;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setPadding(48, 96, 48, 32);
        layout.addView(status);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        layout.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(layout);

        status.setText("正在启动…");
        new Thread(this::boot).start();
    }

    private void boot() {
        try {
            File appRoot = new File(getFilesDir(), "app_root");
            if (extractIfNeeded(appRoot)) {
                statusOnUi("后端资源解压完成,正在启动 Python 运行时…");
            } else {
                statusOnUi("复用已解压的后端资源,正在启动 Python 运行时…");
            }
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(getApplicationContext()));
            }
            Python.getInstance().getModule("poc_boot").callAttr("start", appRoot.getAbsolutePath());
            statusOnUi("正在等待后端服务就绪…");

            String error = waitForPythonState();
            if (error != null) {
                File errFile = new File(getFilesDir(), "boot_error.txt");
                writeString(errFile, error);
                String tail = error.length() > 600 ? error.substring(error.length() - 600) : error;
                String message = "后端启动失败(完整报错已存 boot_error.txt):\n" + tail;
                runOnUiThread(() -> status.setText(message));
                return;
            }

            final String token = readAccessToken(appRoot);
            runOnUiThread(() -> {
                status.setText("本机后端已就绪:" + SERVER_URL
                        + (token.isEmpty() ? "" : "\n访问密码:" + token));
                webView.loadUrl(SERVER_URL);
            });
        } catch (Throwable e) {
            final String message = "启动失败:" + e;
            runOnUiThread(() -> status.setText(message));
        }
    }

    /** 轮询 poc_boot.STATE;返回 null 表示就绪,否则返回给用户看的错误文本。 */
    private String waitForPythonState() throws InterruptedException {
        long start = System.currentTimeMillis();
        long deadline = start + BOOT_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            PyObject state = Python.getInstance().getModule("poc_boot").callAttr("get_state");
            String phase = state.get("status").toString();
            if ("ready".equals(phase)) {
                return null;
            }
            if ("failed".equals(phase)) {
                PyObject error = state.get("error");
                return error == null ? "未知错误" : error.toString();
            }
            final long elapsed = (System.currentTimeMillis() - start) / 1000;
            runOnUiThread(() -> status.setText(
                    "正在等待后端服务就绪…(" + elapsed + "s,阶段:" + phase + ")"));
            Thread.sleep(1000);
        }
        return "等待超时(" + BOOT_TIMEOUT_SECONDS + "s),后端仍未就绪";
    }

    /** 后端资源只在版本变化时解压:marker 记录 versionName,命中则跳过。 */
    private boolean extractIfNeeded(File appRoot) throws IOException {
        File marker = new File(getFilesDir(), "backend_marker.txt");
        String version = currentVersion();
        if (marker.exists() && version.equals(readString(marker))) {
            return false;
        }
        copyAssetDir(ASSET_ROOT, appRoot);
        writeString(marker, version);
        return true;
    }

    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 递归解压 APK assets 中的后端目录,保持与桌面便携版一致的目录布局。 */
    private void copyAssetDir(String assetPath, File outDir) throws IOException {
        String[] entries = getAssets().list(assetPath);
        if (entries == null || entries.length == 0) {
            return;
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建目录:" + outDir);
        }
        for (String entry : entries) {
            String childPath = assetPath + "/" + entry;
            File outFile = new File(outDir, entry);
            String[] nested = getAssets().list(childPath);
            if (nested != null && nested.length > 0) {
                copyAssetDir(childPath, outFile);
            } else {
                copyAssetFile(childPath, outFile);
            }
        }
    }

    private void copyAssetFile(String assetPath, File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录:" + parent);
        }
        try (InputStream in = getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private String readString(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next().trim() : "";
        }
    }

    private void writeString(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录:" + parent);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    /** 首次启动生成的访问密码存在 data/access_token.txt;设备上没有控制台,直接显示给用户。 */
    private String readAccessToken(File appRoot) {
        try {
            return readString(new File(appRoot, "data/access_token.txt"));
        } catch (IOException e) {
            return "";
        }
    }

    private void statusOnUi(String text) {
        runOnUiThread(() -> status.setText(text));
    }
}
