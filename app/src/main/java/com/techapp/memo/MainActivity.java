package com.techapp.memo;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.graphics.Color;
import android.widget.Toast;
import android.content.ContentValues;
import android.content.Context;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;
    private static final int REQUEST_PICK_CSV = 1001;

    // ===== Android Bridge：提供给 JS 调用的接口 =====
    public class AndroidBridge {

        private Context mContext;

        public AndroidBridge(Context context) {
            this.mContext = context;
        }

        /**
         * JS 调用：saveCSV(csvContent, fileName)
         * 将 CSV 内容保存到手机下载目录
         */
        @JavascriptInterface
        public String saveCSV(String csvContent, String fileName) {
            try {
                String savedPath = "";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 使用 MediaStore
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri itemUri = mContext.getContentResolver().insert(collection, values);

                    if (itemUri != null) {
                        try (OutputStream os = mContext.getContentResolver().openOutputStream(itemUri);
                             Writer writer = new OutputStreamWriter(os, "UTF-8")) {
                            writer.write(csvContent);
                        }
                        values.clear();
                        values.put(MediaStore.Downloads.IS_PENDING, 0);
                        mContext.getContentResolver().update(itemUri, values, null, null);
                        savedPath = "下载/" + fileName;
                    }
                } else {
                    // Android 9 及以下：直接写到下载目录
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    File outFile = new File(downloadsDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(outFile);
                         Writer writer = new OutputStreamWriter(fos, "UTF-8")) {
                        writer.write(csvContent);
                    }
                    savedPath = outFile.getAbsolutePath();
                }

                final String msg = "已导出到: " + savedPath;
                runOnUiThread(() -> Toast.makeText(mContext, msg, Toast.LENGTH_LONG).show());
                return "导出成功 📊 已保存到下载目录";

            } catch (Exception e) {
                final String errMsg = "导出失败: " + e.getMessage();
                runOnUiThread(() -> Toast.makeText(mContext, errMsg, Toast.LENGTH_SHORT).show());
                return "导出失败，请检查存储权限";
            }
        }

        /**
         * JS 调用：pickCSVFile()
         * 打开系统文件选择器，让用户选择 CSV 文件
         * 选择完成后通过 JS 回调 window._importCallback(csvContent)
         */
        @JavascriptInterface
        public void pickCSVFile() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            String[] mimeTypes = {"text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(intent, "选择 CSV 文件"), REQUEST_PICK_CSV);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(mContext, "无法打开文件选择器", Toast.LENGTH_SHORT).show());
            }
        }

        /**
         * JS 调用：openUrl(url)
         * 用系统浏览器打开指定 URL（用于下载更新包）
         */
        @JavascriptInterface
        public void openUrl(String url) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(mContext, "无法打开链接", Toast.LENGTH_SHORT).show());
            }
        }

        /**
         * JS 调用：checkUpdate(apiUrl)
         * 后台请求 GitHub Releases API，检查是否有新版本
         * 结果通过 JS 回调 window._updateCallback(version, notes, downloadUrl)
         */
        @JavascriptInterface
        public void checkUpdate(final String apiUrl) {
            new Thread(() -> {
                try {
                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    conn.setRequestProperty("User-Agent", "TechMemo/4.0");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();

                        String json = sb.toString();
                        // 简单解析 JSON（不依赖第三方库）
                        String tagName = extractJsonString(json, "tag_name");
                        String body = extractJsonString(json, "body");
                        String downloadUrl = "";

                        // 找 assets 数组第一个 browser_download_url
                        int assetsIdx = json.indexOf("\"browser_download_url\"");
                        if (assetsIdx >= 0) {
                            int start = json.indexOf("\"", assetsIdx + 22) + 1;
                            int end = json.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                downloadUrl = json.substring(start, end);
                            }
                        }

                        final String version = tagName != null ? tagName.replace("v", "") : "";
                        final String notes = body != null ? body.replace("\\r\\n", "\n").replace("\\n", "\n") : "无更新说明";
                        final String dlUrl = downloadUrl;

                        runOnUiThread(() -> {
                            String jsCall = String.format(
                                "if(window._updateCallback){window._updateCallback('%s','%s','%s');}",
                                version.replace("'", "\\'"),
                                notes.replace("'", "\\'").replace("\n", "\\n"),
                                dlUrl.replace("'", "\\'")
                            );
                            webView.evaluateJavascript(jsCall, null);
                        });
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    // 网络检测失败，静默处理
                }
            }).start();
        }

        // 简单的 JSON 字符串值提取（避免引入 JSON 库）
        private String extractJsonString(String json, String key) {
            String search = "\"" + key + "\"";
            int idx = json.indexOf(search);
            if (idx < 0) return null;
            int colon = json.indexOf(":", idx + search.length());
            if (colon < 0) return null;
            // 跳过空白
            int start = colon + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
            if (start >= json.length()) return null;
            if (json.charAt(start) == '"') {
                // 字符串值
                int end = start + 1;
                while (end < json.length()) {
                    if (json.charAt(end) == '"' && json.charAt(end-1) != '\\') break;
                    end++;
                }
                return json.substring(start + 1, end);
            }
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 沉浸式状态栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor("#050d1a"));
            window.setNavigationBarColor(Color.parseColor("#050d1a"));
        }

        // 全屏
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        // WebView 配置
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(false);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.parseColor("#050d1a"));

        // 注册 JS 接口
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        // 加载本地 HTML
        webView.loadUrl("file:///android_asset/index.html");
    }

    /**
     * 处理文件选择器返回结果
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_CSV && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            new Thread(() -> {
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) return;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    reader.close();
                    is.close();

                    final String csvContent = sb.toString();
                    runOnUiThread(() -> {
                        String escaped = csvContent
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\r", "")
                            .replace("\n", "\\n");
                        String jsCall = "if(window._importCallback){window._importCallback('" + escaped + "');}";
                        webView.evaluateJavascript(jsCall, null);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "读取文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
