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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private static final int REQUEST_PICK_FILE = 1001;

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
         * 打开系统文件选择器，让用户选择 CSV 或 Excel 文件
         * 选择完成后通过 JS 回调 window._importCallback(csvContent)
         */
        @JavascriptInterface
        public void pickCSVFile() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            String[] mimeTypes = {
                "text/csv",
                "text/comma-separated-values",
                "text/plain",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"
            };
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(intent, "选择 CSV 或 Excel 文件"), REQUEST_PICK_FILE);
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
                        String tagName = extractJsonString(json, "tag_name");
                        String body = extractJsonString(json, "body");
                        String downloadUrl = "";

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
            int start = colon + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
            if (start >= json.length()) return null;
            if (json.charAt(start) == '"') {
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

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    /**
     * 处理文件选择器返回结果
     * 自动识别 CSV / XLSX 格式
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;

            // 获取文件名，判断格式
            String fileName = getFileName(uri);
            boolean isXlsx = fileName != null && (
                fileName.toLowerCase().endsWith(".xlsx") ||
                fileName.toLowerCase().endsWith(".xls")
            );

            new Thread(() -> {
                try {
                    String csvContent;
                    if (isXlsx) {
                        // 解析 Excel 文件
                        csvContent = readXlsxAsCSV(uri);
                    } else {
                        // 读取 CSV / TXT 文件
                        csvContent = readTextFile(uri);
                    }

                    final String finalContent = csvContent;
                    runOnUiThread(() -> {
                        if (finalContent == null || finalContent.isEmpty()) {
                            Toast.makeText(this, "文件内容为空或格式不支持", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String escaped = finalContent
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

    /**
     * 读取普通文本文件（CSV/TXT）
     */
    private String readTextFile(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) return null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        is.close();
        return sb.toString();
    }

    /**
     * 读取 .xlsx 文件并转换为 CSV 格式
     * .xlsx 本质是 ZIP，包含：
     *   - xl/sharedStrings.xml  (字符串共享表)
     *   - xl/worksheets/sheet1.xml (工作表数据)
     */
    private String readXlsxAsCSV(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) return null;

        List<String> sharedStrings = new ArrayList<>();
        String sheetXml = null;

        // 遍历 ZIP 条目
        ZipInputStream zis = new ZipInputStream(is);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if (name.equals("xl/sharedStrings.xml")) {
                sharedStrings = parseSharedStrings(zis);
            } else if (name.equals("xl/worksheets/sheet1.xml")) {
                sheetXml = readZipEntry(zis);
            }
            zis.closeEntry();
        }
        zis.close();
        is.close();

        if (sheetXml == null) return null;

        // 解析 sheet1.xml，转换为 CSV
        return parseSheetToCsv(sheetXml, sharedStrings);
    }

    /**
     * 解析 sharedStrings.xml，提取所有字符串
     */
    private List<String> parseSharedStrings(InputStream is) throws Exception {
        String xml = readStream(is);
        List<String> result = new ArrayList<>();

        // 提取所有 <si> 块中的文本
        Pattern siPattern = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL);
        Pattern tPattern = Pattern.compile("<t(?:\\s[^>]*)?>([^<]*)</t>");
        Matcher siMatcher = siPattern.matcher(xml);
        while (siMatcher.find()) {
            String siBlock = siMatcher.group(1);
            StringBuilder text = new StringBuilder();
            Matcher tMatcher = tPattern.matcher(siBlock);
            while (tMatcher.find()) {
                text.append(unescapeXml(tMatcher.group(1)));
            }
            result.add(text.toString());
        }
        return result;
    }

    /**
     * 解析 sheet1.xml，将单元格数据转为 CSV
     */
    private String parseSheetToCsv(String xml, List<String> sharedStrings) {
        StringBuilder csv = new StringBuilder();

        // 提取所有行 <row ...> ... </row>
        Pattern rowPattern = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL);
        // 提取单元格 <c r="A1" t="s"> <v>0</v> </c>
        Pattern cellPattern = Pattern.compile("<c\\s[^>]*r=\"([A-Z]+)(\\d+)\"([^>]*)>(.*?)</c>", Pattern.DOTALL);
        Pattern vPattern = Pattern.compile("<v>([^<]*)</v>");
        Pattern isPattern = Pattern.compile("<is>.*?<t[^>]*>([^<]*)</t>.*?</is>", Pattern.DOTALL);

        Matcher rowMatcher = rowPattern.matcher(xml);
        int lastRow = 0;

        while (rowMatcher.find()) {
            String rowContent = rowMatcher.group(1);

            // 解析本行所有单元格，按列索引排列
            Map<Integer, String> cellMap = new HashMap<>();
            int maxCol = 0;

            Matcher cellMatcher = cellPattern.matcher(rowContent);
            while (cellMatcher.find()) {
                String colStr = cellMatcher.group(1);  // 列字母，如 A, B, AA
                String cellAttrs = cellMatcher.group(3); // 其他属性
                String cellInner = cellMatcher.group(4);  // 单元格内部

                int colIdx = columnLetterToIndex(colStr); // 0-based
                if (colIdx > maxCol) maxCol = colIdx;

                String cellValue = "";

                // 检查是否是内联字符串 <is>
                Matcher isMatcher = isPattern.matcher(cellInner);
                if (isMatcher.find()) {
                    cellValue = unescapeXml(isMatcher.group(1));
                } else {
                    Matcher vMatcher = vPattern.matcher(cellInner);
                    if (vMatcher.find()) {
                        String rawValue = vMatcher.group(1);
                        // 判断类型：t="s" 表示共享字符串索引
                        if (cellAttrs.contains("t=\"s\"") || rowContent.contains("t=\"s\"")) {
                            // 用共享字符串索引查找
                            try {
                                int idx = Integer.parseInt(rawValue.trim());
                                if (idx >= 0 && idx < sharedStrings.size()) {
                                    cellValue = sharedStrings.get(idx);
                                } else {
                                    cellValue = rawValue;
                                }
                            } catch (NumberFormatException e) {
                                cellValue = rawValue;
                            }
                        } else {
                            cellValue = unescapeXml(rawValue);
                        }
                    }
                }
                cellMap.put(colIdx, cellValue);
            }

            if (cellMap.isEmpty()) continue;

            // 按列顺序拼接
            StringBuilder rowCsv = new StringBuilder();
            for (int i = 0; i <= maxCol; i++) {
                if (i > 0) rowCsv.append(",");
                String val = cellMap.getOrDefault(i, "");
                // CSV 转义
                if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
                    rowCsv.append("\"").append(val.replace("\"", "\"\"")).append("\"");
                } else {
                    rowCsv.append(val);
                }
            }
            csv.append(rowCsv).append("\n");
        }

        return csv.toString();
    }

    /**
     * 列字母转数字索引（A=0, B=1, Z=25, AA=26 ...）
     */
    private int columnLetterToIndex(String col) {
        int result = 0;
        for (char c : col.toCharArray()) {
            result = result * 26 + (c - 'A' + 1);
        }
        return result - 1;
    }

    /**
     * 读取 ZipInputStream 当前条目为字符串（不关闭流）
     */
    private String readZipEntry(ZipInputStream zis) throws Exception {
        return readStream(zis);
    }

    /**
     * 将 InputStream 读取为字符串（不关闭流）
     */
    private String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /**
     * XML 实体反转义
     */
    private String unescapeXml(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#13;", "\r")
                .replace("&#10;", "\n");
    }

    /**
     * 获取 Uri 对应的文件名
     */
    private String getFileName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = cursor.getString(idx);
                }
            } catch (Exception e) { /* ignore */ }
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name;
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
