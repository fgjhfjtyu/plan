package org.qetmobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE = 2;
    private static final int REQ_LIB = 3;
    private static final int REQ_LIBDIR = 4;
    private int qchTotal = 0;
    private String libIndexCache = null;

    private WebView web;
    private byte[] pendingSave;
    private Uri startUri;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);

        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverStartUri();
            }
        });

        Intent in = getIntent();
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction())) {
            startUri = in.getData();
        }
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            startUri = intent.getData();
            deliverStartUri();
        }
    }

    private void deliverStartUri() {
        if (pageReady && startUri != null) {
            Uri u = startUri;
            startUri = null;
            readAndSend(u);
        }
    }

    private String displayName(Uri uri) {
        String name = "project.qet";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return name;
    }

    private void readAndSend(final Uri uri) {
        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                final String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                final String name = displayName(uri);
                runOnUiThread(() -> web.evaluateJavascript(
                        "window.onNativeFile(" + JSONObject.quote(name) + ",'" + b64 + "')", null));
            } catch (Exception e) {
                final String msg = String.valueOf(e.getMessage());
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Open failed: " + msg, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_LIB) { handleLibPick(data); return; }
        if (requestCode == REQ_LIBDIR) { handleLibTree(data); return; }
        if (data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN) {
            readAndSend(uri);
        } else if (requestCode == REQ_SAVE && pendingSave != null) {
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                os.write(pendingSave);
                os.flush();
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            pendingSave = null;
        }
    }


    /* ================= element library import ================= */
    private File libDir() {
        File d = new File(getFilesDir(), "lib");
        d.mkdirs();
        return d;
    }

    private String safePath(String name) {
        String n = name.replace('\\', '/');
        int i = n.indexOf("elements/");
        if (i >= 0) n = n.substring(i + 9);
        StringBuilder sb = new StringBuilder();
        for (String part : n.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }

    private boolean writeElmt(String name, byte[] data) {
        String p = safePath(name);
        if (p.isEmpty() || !p.toLowerCase().endsWith(".elmt")) return false;
        File f = new File(libDir(), p);
        File par = f.getParentFile();
        if (par != null) par.mkdirs();
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static byte[] readStream(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }

    /** Qt's qCompress format: 4-byte big-endian size + zlib stream. */
    private static byte[] qUncompress(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        if (raw[0] == '<') return raw; // already plain XML
        if (raw.length < 5) return null;
        int len = ((raw[0] & 0xff) << 24) | ((raw[1] & 0xff) << 16) | ((raw[2] & 0xff) << 8) | (raw[3] & 0xff);
        if (len <= 0 || len > 20 * 1024 * 1024) return null;
        Inflater inf = new Inflater();
        try {
            inf.setInput(raw, 4, raw.length - 4);
            byte[] out = new byte[len];
            int got = 0;
            while (!inf.finished() && got < len) {
                int r = inf.inflate(out, got, len - got);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                got += r;
            }
            return got == len ? out : Arrays.copyOf(out, got);
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    private int importQchFile(File f) {
        int n = 0;
        SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        qchTotal = 0;
        try (Cursor t = db.rawQuery("SELECT count(*) FROM FileNameTable", null)) {
            if (t.moveToFirst()) qchTotal = t.getInt(0);
        } catch (RuntimeException ignored) {
        }
        try (Cursor c = db.rawQuery("SELECT n.Name, d.Data FROM FileNameTable n JOIN FileDataTable d ON d.Id = n.FileId WHERE lower(n.Name) LIKE '%.elmt'", null)) {
            while (c.moveToNext()) {
                byte[] data = qUncompress(c.getBlob(1));
                if (data != null && writeElmt(c.getString(0), data)) n++;
            }
        } finally {
            db.close();
        }
        return n;
    }

    private int importZip(Uri uri) throws Exception {
        int n = 0;
        try (InputStream is = getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is, 1 << 16))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String en = e.getName();
                String low = en.toLowerCase();
                if (low.endsWith(".elmt")) {
                    if (writeElmt(en, readStream(zis))) n++;
                } else if (low.endsWith(".qch")) {
                    File tmp = new File(getCacheDir(), "import.qch");
                    try (FileOutputStream fo = new FileOutputStream(tmp)) {
                        copy(zis, fo);
                    }
                    try {
                        n += importQchFile(tmp);
                    } finally {
                        tmp.delete();
                    }
                }
            }
        } catch (EOFException | ZipException ex) {
            if (n == 0) throw new Exception("فایل zip ناقص یا خراب است");
        }
        return n;
    }

    private int importQchUri(Uri uri) throws Exception {
        File tmp = new File(getCacheDir(), "import.qch");
        try (InputStream is = getContentResolver().openInputStream(uri);
             FileOutputStream fo = new FileOutputStream(tmp)) {
            copy(is, fo);
        }
        try {
            int n = importQchFile(tmp);
            if (n == 0) {
                throw new Exception("این فایل راهنما (qch) شامل " + qchTotal
                        + " فایل است ولی هیچ‌کدام قطعه (.elmt) نیست. احتمالاً فایل مستندات است؛ پوشه‌ی elements را وارد کنید.");
            }
            return n;
        } finally {
            tmp.delete();
        }
    }

    /** Detects the file type from its content, not from its extension. */
    private int importUri(Uri uri) throws Exception {
        byte[] head = new byte[64];
        int got;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            got = is.read(head);
        }
        if (got < 4) throw new Exception("فایل خالی است");
        String h = new String(head, 0, got, StandardCharsets.ISO_8859_1);
        if (h.startsWith("PK\u0003\u0004")) return importZip(uri);
        if (h.startsWith("SQLite format 3")) return importQchUri(uri);
        if (h.trim().startsWith("<") || h.startsWith("\u00ef\u00bb\u00bf<")) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                byte[] data = readStream(is);
                String txt = new String(data, StandardCharsets.UTF_8);
                if (!txt.contains("<definition")) throw new Exception("این XML تعریف قطعه نیست");
                return writeElmt(displayName(uri), data) ? 1 : 0;
            }
        }
        throw new Exception("نوع فایل شناخته نشد (فقط qch ، zip ، elmt)");
    }

    private int importTree(Uri treeUri, String docId, String rel) {
        int n = 0;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        try (Cursor c = getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return 0;
            while (c.moveToNext()) {
                String id = c.getString(0), nm = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    n += importTree(treeUri, id, rel + nm + "/");
                } else if (nm != null && nm.toLowerCase().endsWith(".elmt")) {
                    Uri du = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    try (InputStream is = getContentResolver().openInputStream(du)) {
                        if (writeElmt(rel + nm, readStream(is))) n++;
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return n;
    }

    private void finishImport(int total, String err) {
        final int t = total;
        final int c = rebuildIndex();
        final String msg = err;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.onLibImported(" + t + "," + c + "," + JSONObject.quote(msg) + ")", null));
    }

    private void handleLibTree(Intent data) {
        final Uri tree = data.getData();
        if (tree == null) return;
        Toast.makeText(this, "در حال خواندن پوشه... ممکن است چند دقیقه طول بکشد", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            int n = 0;
            String err = "";
            try {
                n = importTree(tree, DocumentsContract.getTreeDocumentId(tree), "");
                if (n == 0) err = "داخل این پوشه فایل .elmt پیدا نشد. پوشه‌ی elements را انتخاب کنید.";
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            finishImport(n, err);
        }).start();
    }

    private static final Pattern NAME_FA = Pattern.compile("<name[^>]*lang=\"fa\"[^>]*>([^<]*)</name>");
    private static final Pattern NAME_EN = Pattern.compile("<name[^>]*lang=\"en\"[^>]*>([^<]*)</name>");
    private static final Pattern NAME_ANY = Pattern.compile("<name(?:\\s[^>]*)?>([^<]*)</name>");

    private String elmtName(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n = in.read(buf);
            String s = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            for (Pattern p : new Pattern[]{NAME_FA, NAME_EN, NAME_ANY}) {
                Matcher m = p.matcher(s);
                if (m.find() && !m.group(1).trim().isEmpty()) return m.group(1).trim();
            }
        } catch (IOException ignored) {
        }
        return f.getName().replaceAll("(?i)\\.elmt$", "");
    }

    private void walkLib(File dir, String rel, JSONArray arr) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) walkLib(f, r, arr);
            else if (f.getName().toLowerCase().endsWith(".elmt")) arr.put(new JSONArray().put(r).put(elmtName(f)));
        }
    }

    private int rebuildIndex() {
        JSONArray arr = new JSONArray();
        walkLib(libDir(), "", arr);
        libIndexCache = arr.toString();
        try (FileOutputStream os = new FileOutputStream(new File(getFilesDir(), "lib_index.json"))) {
            os.write(libIndexCache.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        return arr.length();
    }

    private void deleteRec(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        f.delete();
    }

    private void handleLibPick(Intent data) {
        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        Toast.makeText(this, "در حال وارد کردن قطعات... ممکن است چند دقیقه طول بکشد", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            int total = 0;
            StringBuilder err = new StringBuilder();
            for (Uri u : uris) {
                try {
                    total += importUri(u);
                } catch (Throwable e) {
                    err.append(displayName(u)).append(": ").append(e.getMessage()).append("\n");
                }
            }
            finishImport(total, err.toString());
        }).start();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack ? window.onBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void openFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_OPEN);
            });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String b64) {
            runOnUiThread(() -> {
                pendingSave = Base64.decode(b64, Base64.DEFAULT);
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_SAVE);
            });
        }

        @JavascriptInterface
        public void libImport() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_LIB);
            });
        }

        @JavascriptInterface
        public void libImportFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(i, REQ_LIBDIR);
            });
        }

        @JavascriptInterface
        public String libIndex() {
            if (libIndexCache != null) return libIndexCache;
            File f = new File(getFilesDir(), "lib_index.json");
            if (!f.exists()) return "[]";
            try (FileInputStream in = new FileInputStream(f)) {
                libIndexCache = new String(readStream(in), StandardCharsets.UTF_8);
                return libIndexCache;
            } catch (IOException e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String libRead(String path) {
            String p = safePath(path);
            if (p.isEmpty()) return "";
            File f = new File(libDir(), p);
            if (!f.isFile()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                return new String(readStream(in), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        @JavascriptInterface
        public void libClear() {
            deleteRec(libDir());
            rebuildIndex();
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }
}
