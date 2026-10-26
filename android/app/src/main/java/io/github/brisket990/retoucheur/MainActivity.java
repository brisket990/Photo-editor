package io.github.brisket990.retoucheur;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Affiche la page du Retoucheur EXIF dans une WebView et lui fournit ce qu'un navigateur
 * ne peut pas avoir : la photo d'origine avec sa vraie position GPS (ACCESS_MEDIA_LOCATION
 * + MediaStore.setRequireOriginal) et l'écriture directe dans la galerie.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_PICK = 1;
    private static final int REQ_WRITE = 2;
    private static final int REQ_PERM = 3;

    private WebView web;
    private boolean pageReady = false;
    private Uri pendingShared = null;

    private volatile byte[] current = null;   // octets de la photo ouverte, servis à la page
    private Uri currentUri = null;            // URI MediaStore de la photo ouverte (pour la remplacer)
    private byte[] pendingWrite = null;       // octets en attente de l'accord « remplacer l'original »
    private String pendingWriteName = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath() == null ? "/" : u.getPath();
                try {
                    if (path.equals("/") || path.equals("/index.html")) {
                        return response("text/html", getAssets().open("index.html"));
                    }
                    if (path.equals("/photo/current")) {
                        byte[] b = current;
                        if (b == null) return notFound();
                        return response("image/jpeg", new ByteArrayInputStream(b));
                    }
                } catch (IOException e) {
                    return notFound();
                }
                return notFound();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingShared != null) {
                    Uri u = pendingShared;
                    pendingShared = null;
                    openPhoto(u);
                }
            }
        });

        handleShare(getIntent());
        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    /** Photo partagée depuis la galerie (« Partager » → Retoucheur EXIF). */
    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        if (!hasReadPermissions()) {
            pendingShared = u;
            requestReadPermissions();
            return;
        }
        if (pageReady) openPhoto(u); else pendingShared = u;
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    /* ---------------- pont JavaScript ---------------- */

    private class Bridge {
        @JavascriptInterface
        public void pick() {
            runOnUiThread(MainActivity.this::startPick);
        }

        /** mode = "copy" (nouvelle photo dans DCIM/Camera) ou "replace" (écrase l'original). */
        @JavascriptInterface
        public void save(String base64, String filename, String mode) {
            final byte[] data;
            try {
                data = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                callJs("nativeSaved", false, "Données reçues illisibles.", mode);
                return;
            }
            runOnUiThread(() -> {
                if ("replace".equals(mode)) startReplace(data, filename);
                else saveCopy(data, filename);
            });
        }

        @JavascriptInterface
        public boolean canReplace() {
            return currentUri != null && "media".equals(currentUri.getAuthority());
        }
    }

    /* ---------------- permissions ---------------- */

    private String[] readPermissions() {
        List<String> l = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.READ_MEDIA_IMAGES);
        else l.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        l.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        return l.toArray(new String[0]);
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasReadPermissions() {
        for (String p : readPermissions()) if (!has(p)) return false;
        return true;
    }

    private boolean askedOnce = false;
    private boolean pickAfterPermission = false;

    private void requestReadPermissions() {
        askedOnce = true;
        requestPermissions(readPermissions(), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (pickAfterPermission) {
            pickAfterPermission = false;
            launchPicker();
        } else if (pendingShared != null && pageReady) {
            Uri u = pendingShared;
            pendingShared = null;
            openPhoto(u);
        }
    }

    /* ---------------- choix et lecture de la photo ---------------- */

    private void startPick() {
        if (!hasReadPermissions() && !askedOnce) {
            pickAfterPermission = true;
            requestReadPermissions();
            return;
        }
        launchPicker();
    }

    private void launchPicker() {
        // ACTION_PICK sur MediaStore ouvre la galerie et renvoie une URI MediaStore,
        // la seule qui permet de lire la photo d'origine non masquée.
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/jpeg");
            g.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(g, REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) openPhoto(data.getData());
        } else if (requestCode == REQ_WRITE) {
            if (resultCode == RESULT_OK && pendingWrite != null) {
                byte[] d = pendingWrite;
                pendingWrite = null;
                new Thread(() -> writeReplace(d)).start();
            } else {
                pendingWrite = null;
                callJs("nativeSaved", false, "Remplacement annulé, l'original n'a pas été modifié.", "replace");
            }
        }
    }

    private void openPhoto(Uri picked) {
        new Thread(() -> {
            ContentResolver cr = getContentResolver();
            Uri media = toMediaStoreUri(picked);
            byte[] bytes = null;
            boolean original = false;
            if (media != null && has(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
                try (InputStream in = cr.openInputStream(MediaStore.setRequireOriginal(media))) {
                    bytes = readAll(in);
                    original = true;
                } catch (Exception ignored) {
                }
            }
            if (bytes == null) {
                try (InputStream in = cr.openInputStream(picked)) {
                    bytes = readAll(in);
                } catch (Exception e) {
                    callJs("nativeError", "Impossible de lire cette photo : " + e.getMessage());
                    return;
                }
            }
            current = bytes;
            currentUri = media;
            String name = displayName(media != null ? media : picked);
            callJs("nativePhotoReady", name, original);
        }).start();
    }

    /** Ramène les URI du sélecteur Android ou de Documents vers l'URI MediaStore de la photo. */
    private Uri toMediaStoreUri(Uri u) {
        if (u == null) return null;
        Uri images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try {
            if ("media".equals(u.getAuthority())) {
                List<String> seg = u.getPathSegments();
                if (seg.contains("picker") || seg.contains("picker_get_content")) {
                    return ContentUris.withAppendedId(images, Long.parseLong(u.getLastPathSegment()));
                }
                return u;
            }
            if ("com.android.providers.media.documents".equals(u.getAuthority())) {
                String id = DocumentsContract.getDocumentId(u); // "image:1234"
                int c = id.indexOf(':');
                if (c > 0) return ContentUris.withAppendedId(images, Long.parseLong(id.substring(c + 1)));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        return "photo.jpg";
    }

    /* ---------------- enregistrement ---------------- */

    private void saveCopy(byte[] data, String filename) {
        new Thread(() -> {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri out = null;
            try {
                out = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (out == null) throw new IOException("la galerie a refusé le nouveau fichier");
                try (OutputStream os = cr.openOutputStream(out, "w")) {
                    if (os == null) throw new IOException("écriture impossible");
                    os.write(data);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(out, done, null, null);
                String finalName = displayName(out);
                callJs("nativeSaved", true, "Copie enregistrée dans la galerie (DCIM/Camera) : " + finalName, "copy");
            } catch (Exception e) {
                if (out != null) {
                    try { cr.delete(out, null, null); } catch (Exception ignored) { }
                }
                callJs("nativeSaved", false, "Échec de l'enregistrement : " + e.getMessage(), "copy");
            }
        }).start();
    }

    private void startReplace(byte[] data, String filename) {
        if (currentUri == null || !"media".equals(currentUri.getAuthority())) {
            callJs("nativeSaved", false, "Cette photo ne vient pas de la galerie : enregistre plutôt une copie.", "replace");
            return;
        }
        pendingWrite = data;
        pendingWriteName = filename;
        try {
            PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), Collections.singletonList(currentUri));
            startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
        } catch (Exception e) {
            pendingWrite = null;
            callJs("nativeSaved", false, "Android a refusé la demande de modification : " + e.getMessage(), "replace");
        }
    }

    private void writeReplace(byte[] data) {
        try (OutputStream os = getContentResolver().openOutputStream(currentUri, "wt")) {
            if (os == null) throw new IOException("écriture impossible");
            os.write(data);
            os.flush();
            current = data;
            callJs("nativeSaved", true, "Photo d'origine mise à jour dans la galerie.", "replace");
        } catch (Exception e) {
            callJs("nativeSaved", false, "Échec du remplacement : " + e.getMessage(), "replace");
        }
    }

    /* ---------------- utilitaires ---------------- */

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) throw new IOException("flux vide");
        ByteArrayOutputStream bo = new ByteArrayOutputStream(4 << 20);
        byte[] buf = new byte[64 << 10];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static WebResourceResponse response(String mime, InputStream in) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse(mime, "utf-8", 200, "OK", h, in);
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private void callJs(String fn, Object... args) {
        StringBuilder sb = new StringBuilder("window.").append(fn).append("&&window.").append(fn).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            Object a = args[i];
            if (a instanceof String) sb.append(JSONObject.quote((String) a));
            else sb.append(String.valueOf(a));
        }
        sb.append(");");
        final String code = sb.toString();
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }
}
