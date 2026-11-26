package com.fleet.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import java.util.ArrayList;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;
    private boolean cameraMode;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int VOICE_REQUEST = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);

        // Bridge: real Android print + real file download to Downloads folder
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                String u = url.toLowerCase();
                // Phone / WhatsApp / SMS / Email must open in the phone's own apps, NOT as a link inside WebView
                if (u.startsWith("tel:") || u.startsWith("sms:") || u.startsWith("mailto:")
                        || u.startsWith("whatsapp:") || u.contains("wa.me") || u.contains("api.whatsapp.com")
                        || u.startsWith("intent:")) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "تعذر فتح التطبيق", Toast.LENGTH_SHORT).show();
                    }
                    return true; // handled outside WebView
                }
                return false; // normal pages stay inside the app
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                cameraMode = false;
                // Invoice photo with capture="environment": open the phone camera DIRECTLY
                if (params.isCaptureEnabled()) {
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, "invoice_" + System.currentTimeMillis() + ".jpg");
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/صيانة القواطر");
                        }
                        cameraPhotoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        cameraMode = true;
                        startActivityForResult(cam, FILE_CHOOSER_REQUEST);
                        return true;
                    } catch (Exception e) {
                        cameraPhotoUri = null;
                        cameraMode = false;
                        // fall through to the file chooser below
                    }
                }
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // Support JSON backup + images (invoice photos), not images only
                String[] types = params.getAcceptTypes();
                String type = "*/*";
                if (types != null && types.length > 0 && types[0] != null && !types[0].isEmpty()) {
                    type = types[0];
                    if (type.contains("json")) type = "application/json";
                }
                intent.setType(type);
                if (type.equals("*/*")) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "image/*", "text/*"});
                }
                startActivityForResult(Intent.createChooser(intent, "اختر الملف"), FILE_CHOOSER_REQUEST);
                return true;
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر فتح التطبيق", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
                    intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "تكلم الان... اسم القاطرة");
                    startActivityForResult(intent, VOICE_REQUEST);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "البحث الصوتي غير متوفر في هذا الجهاز", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void printLandscape() {
            runOnUiThread(() -> {
                try {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    String jobName = "تقرير-صيانة-القواطر-افقي";
                    PrintAttributes attrs = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build();
                    pm.print(jobName, webView.createPrintDocumentAdapter(jobName), attrs);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر فتح الطباعة", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(() -> {
                try {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    String jobName = "تقرير-صيانة-القواطر";
                    PrintAttributes attrs = new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build(); pm.print(jobName, webView.createPrintDocumentAdapter(jobName), attrs);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر فتح الطباعة", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void downloadFile(String fileName, String base64Content) {
            runOnUiThread(() -> {
                try {
                    byte[] data = Base64.decode(base64Content, Base64.DEFAULT);
                    Uri savedUri = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                        values.put(MediaStore.Downloads.MIME_TYPE, fileName.endsWith(".json") ? "application/json" : "text/html");
                        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/صيانة القواطر");
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) {
                            OutputStream os = getContentResolver().openOutputStream(uri);
                            if (os != null) { os.write(data); os.close(); }
                            savedUri = uri;
                        }
                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "صيانة القواطر");
                        if (!dir.exists()) dir.mkdirs();
                        File f = new File(dir, fileName);
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(data); fos.close();
                        savedUri = Uri.fromFile(f);
                    }
                    Toast.makeText(MainActivity.this, "تم الحفظ في مجلد صيانة القواطر: " + fileName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر حفظ الملف", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty()) {
                    String text = results.get(0).replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
                    webView.evaluateJavascript("if(window.onVoiceResult) onVoiceResult('" + text + "')", null);
                }
            } else {
                Toast.makeText(this, "لم اسمعك - حاول مرة اخرى", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (cameraMode && resultCode == Activity.RESULT_OK && cameraPhotoUri != null) {
                    results = new Uri[]{cameraPhotoUri};
                } else if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
                cameraMode = false;
                cameraPhotoUri = null;
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Back button: close the top layer (lock / context menu / report page /
        // drawer / modal / log form / truck form) FIRST. Only exit when nothing is open.
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript("closeTopLayer()", value -> {
            if (!"true".equals(value)) {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    MainActivity.super.onBackPressed();
                }
            }
        });
    }

    private void doDefaultBack() {
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (webView != null) webView.restoreState(savedInstanceState);
    }
}
