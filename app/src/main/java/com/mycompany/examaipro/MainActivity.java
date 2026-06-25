package com.mycompany.examaipro;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView myWebView;
    
    // Callbacks for file picking (Import)
    private ValueCallback<Uri[]> fileUploadCallback;
    private final static int FILECHOOSER_RESULTCODE = 1;
    private final static int CAMERA_PERMISSION_REQUEST_CODE = 101;

    // Camera storage path & pending parameters
    private String cameraPhotoPath;
    private WebChromeClient.FileChooserParams pendingParams;

    // Callbacks for file saving (Export)
    private final static int FILESAVE_RESULTCODE = 2;
    private String fileContentToSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);    
        
        // 1. Hide the title bar ("examaipro")
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        myWebView = new WebView(this);
        WebSettings webSettings = myWebView.getSettings();

        // 2. Core WebView Settings
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true); 
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // 3. Fix Layout & Font Scale Logic
        webSettings.setTextZoom(100); 
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);

        // 4. Intercept Links to open in regular Browser
        myWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true; 
                }
                return false;
            }
        });

        // 5. Smart File Picker & Camera Chooser Configuration
        myWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                boolean acceptsImages = false;
                if (fileChooserParams.getAcceptTypes() != null) {
                    for (String type : fileChooserParams.getAcceptTypes()) {
                        if (type.contains("image") || type.contains("*/*")) {
                            acceptsImages = true;
                            break;
                        }
                    }
                }

                // SCENARIO A: JSON/File Import -> Uses pristine intent.
                // This completely fixes the sorting issue and uses your default folder preference.
                if (!acceptsImages) {
                    try {
                        startActivityForResult(fileChooserParams.createIntent(), FILECHOOSER_RESULTCODE);
                    } catch (Exception e) {
                        fileUploadCallback = null;
                        return false;
                    }
                    return true;
                }

                // SCENARIO B: Image Import -> Check Camera Permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pendingParams = fileChooserParams;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
                        return true;
                    }
                }

                openChooserWithCamera(fileChooserParams);
                return true;
            }
        });

        // 6. Provide the AndroidBridge to React
        myWebView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        setContentView(myWebView);
        myWebView.loadUrl("file:///android_asset/www/index.html");
    }

    // Creates a temporary file space so the camera can successfully save the image data
    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile("JPEG_" + timeStamp + "_", ".jpg", storageDir);
        cameraPhotoPath = "file:" + image.getAbsolutePath();
        return image;
    }

    private void openChooserWithCamera(WebChromeClient.FileChooserParams fileChooserParams) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
                takePictureIntent.putExtra("PhotoPath", cameraPhotoPath);
            } catch (IOException ex) {
                cameraPhotoPath = null;
            }

            if (photoFile != null) {
                Uri photoURI = Uri.fromFile(photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
            } else {
                takePictureIntent = null;
            }
        }

        Intent contentSelectionIntent = fileChooserParams.createIntent();
        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "Choose an action");
        
        if (takePictureIntent != null) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{takePictureIntent});
        } else {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[0]);
        }

        try {
            startActivityForResult(chooserIntent, FILECHOOSER_RESULTCODE);
        } catch (Exception e) {
            fileUploadCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingParams != null) {
                    openChooserWithCamera(pendingParams);
                }
            } else {
                Toast.makeText(this, "Camera permission denied. Opening files only.", Toast.LENGTH_SHORT).show();
                if (pendingParams != null) {
                    try {
                        startActivityForResult(pendingParams.createIntent(), FILECHOOSER_RESULTCODE);
                    } catch (Exception e) {
                        if (fileUploadCallback != null) fileUploadCallback.onReceiveValue(null);
                    }
                }
            }
            pendingParams = null;
        }
    }

    // --- Background interface to communicate with React ---
    public class WebAppInterface {
        @JavascriptInterface
        public void saveFile(final String filename, final String content) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    fileContentToSave = content;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json"); 
                    intent.putExtra(Intent.EXTRA_TITLE, filename); 
                    startActivityForResult(intent, FILESAVE_RESULTCODE);
                }
            });
        }
    }

    // --- Listens to answers from native system views ---
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (fileUploadCallback == null) return;
            
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (data == null || data.getDataString() == null) {
                    if (cameraPhotoPath != null) {
                        results = new Uri[]{Uri.parse(cameraPhotoPath)};
                    }
                } else {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
            }
            
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
            cameraPhotoPath = null; 
            
        } else if (requestCode == FILESAVE_RESULTCODE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    OutputStream outputStream = getContentResolver().openOutputStream(uri);
                    if (outputStream != null) {
                        outputStream.write(fileContentToSave.getBytes());
                        outputStream.close();
                        Toast.makeText(this, "JSON File Saved successfully!", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Error saving file.", Toast.LENGTH_SHORT).show();
                }
            }
            fileContentToSave = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK) && myWebView.canGoBack()) {
            myWebView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
