
package org.kivy.android;

import java.net.Socket;
import java.net.InetSocketAddress;

import android.os.SystemClock;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

import android.app.*;
import android.content.*;
import android.view.*;
import android.view.ViewGroup;
import android.view.SurfaceView;
import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
import android.os.Bundle;
import android.os.PowerManager;
import android.graphics.PixelFormat;
import android.view.SurfaceHolder;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.widget.ImageView;
import java.io.InputStream;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import android.widget.AbsoluteLayout;
import android.view.ViewGroup.LayoutParams;

import android.webkit.WebViewClient;
import android.webkit.WebView;

import org.kivy.android.PythonUtil;

import org.kivy.android.WebViewLoader;

import org.renpy.android.ResourceManager;
import org.renpy.android.AssetExtract;

public class PythonActivity extends Activity {
    // This activity is modified from a mixture of the SDLActivity and
    // PythonActivity in the SDL2 bootstrap, but removing all the SDL2
    // specifics.

    private static final String TAG = "PythonActivity";

    public static PythonActivity mActivity = null;

    /** If shared libraries (e.g. SDL or the native application) could not be loaded. */
    public static boolean mBrokenLibraries;

    protected static ViewGroup mLayout;
    public static WebView mWebView;

    protected static Thread mPythonThread;

    private ResourceManager resourceManager = null;
    private Bundle mMetaData = null;
    private PowerManager.WakeLock mWakeLock = null;

    public static void initialize() {
        // The static nature of the singleton and Android quirkyness force us to initialize everything here
        // Otherwise, when exiting the app and returning to it, these variables *keep* their pre exit values
        mWebView = null;
        mLayout = null;
        mBrokenLibraries = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "My oncreate running");
        resourceManager = new ResourceManager(this);

        super.onCreate(savedInstanceState);

        this.mActivity = this;
        this.showLoadingScreen();
        new UnpackFilesTask().execute(getFilesDir());
    }


    private class UnpackFilesTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            File app_root_file = new File(params[0]);
            Log.v(TAG, "Ready to unpack");
            unpackData("private", app_root_file);
            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            Log.v("Python", "Device: " + android.os.Build.DEVICE);
            Log.v("Python", "Model: " + android.os.Build.MODEL);

            // onCreate and onDestroy are actually called each time the app
            // is paused and resumed. This is why the variables are sometimes
            // already initialized - because the app just paused and is about
            // to restore. So only run initialization when we are not restoring.

            Log.v(TAG, "Ready to unpack");
            PythonActivity.initialize();

            // Load shared libraries
            String errorMsgBrokenLib = "";
            try {
                loadLibraries();
            } catch(UnsatisfiedLinkError e) {
                System.err.println(e.getMessage());
                mBrokenLibraries = true;
                errorMsgBrokenLib = e.getMessage();
            } catch(Exception e) {
                System.err.println(e.getMessage());
                mBrokenLibraries = true;
                errorMsgBrokenLib = e.getMessage();
            }

            if (mBrokenLibraries)
            {
                AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(this);
                dlgAlert.setMessage("An error occurred while trying to load the application libraries. Please try again and/or reinstall."
                      + System.getProperty("line.separator")
                      + System.getProperty("line.separator")
                      + "Error: " + errorMsgBrokenLib);
                dlgAlert.setTitle("Python Error");
                dlgAlert.setPositiveButton("Exit",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,int id) {
                            // if this button is clicked, close current activity
                            PythonActivity.mActivity.finish();
                        }
                    });
               dlgAlert.setCancelable(false);
               dlgAlert.create().show();

               return;
            }

            if (mWebView == null) {
                Log.v(TAG, "mWebView is null");
            } else {
                Log.v(TAG, "mWebView is not null");
            }

            // Set up the webview
            mWebView = new WebView(this);
            mWebView.setId(mWebView.generateViewId());  // used to ensure the control persists
            mWebView.getSettings().setJavaScriptEnabled(true);
            mWebView.getSettings().setDomStorageEnabled(true);
            mWebView.setBackgroundColor(Color.BLACK);

            mWebView.setLayoutParams(new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));

            mLayout = new AbsoluteLayout(this);
            mLayout.addView(mWebView);

            setContentView(mLayout);
            // keep the loading screen up until
            this.showLoadingScreen();

            String mFilesDirectory = mActivity.getFilesDir().getAbsolutePath();
            Log.v(TAG, "Setting env vars for start.c and Python to use");
            PythonActivity.nativeSetEnv("ANDROID_PRIVATE", mFilesDirectory);
            PythonActivity.nativeSetEnv("ANDROID_ARGUMENT", mFilesDirectory);
            PythonActivity.nativeSetEnv("ANDROID_APP_PATH", mFilesDirectory);
            PythonActivity.nativeSetEnv("ANDROID_ENTRYPOINT", "main.pyo");
            PythonActivity.nativeSetEnv("PYTHONHOME", mFilesDirectory);
            PythonActivity.nativeSetEnv("PYTHONPATH", mFilesDirectory + ":" + mFilesDirectory + "/lib");

            try {
                Log.v(TAG, "Access to our meta-data...");
                this.mMetaData = this.mActivity.getPackageManager().getApplicationInfo(
                        this.mActivity.getPackageName(), PackageManager.GET_META_DATA).metaData;

                PowerManager pm = (PowerManager) this.mActivity.getSystemService(Context.POWER_SERVICE);
                if ( this.mMetaData.getInt("wakelock") == 1 ) {
                    this.mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Screen On");
                }
            } catch (PackageManager.NameNotFoundException e) {
            }

            final Thread pythonThread = new Thread(new PythonMain(), "PythonThread");
            PythonActivity.mPythonThread = pythonThread;
            pythonThread.start();
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy called");
        super.onDestroy();
        
        // make sure all child threads (python_thread) are stopped
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public void loadLibraries() {
        PythonUtil.loadLibraries(getFilesDir());
    }

    public void recursiveDelete(File f) {
        if (f.isDirectory()) {
            for (File r : f.listFiles()) {
                recursiveDelete(r);
            }
        }
        f.delete();
    }

    /**
     * Show an error using a toast. (Only makes sense from non-UI
     * threads.)
     */
    public void toastError(final String msg) {

        final Activity thisActivity = this;

        runOnUiThread(new Runnable () {
            public void run() {
                Toast.makeText(thisActivity, msg, Toast.LENGTH_LONG).show();
            }
        });

        // Wait to show the error.
        synchronized (this) {
            try {
                this.wait(1000);
            } catch (InterruptedException e) {
            }
        }
    }

    public void unpackData(final String resource, File target) {

        Log.v(TAG, "UNPACKING!!! " + resource + " " + target.getName());

        // The version of data in memory and on disk.
        String data_version = resourceManager.getString(resource + "_version");
        String disk_version = null;

        Log.v(TAG, "Data version is " + data_version);

        // If no version, no unpacking is necessary.
        if (data_version == null) {
            return;
        }

        // Check the current disk version, if any.
        String filesDir = target.getAbsolutePath();
        String disk_version_fn = filesDir + "/" + resource + ".version";

        try {
            byte buf[] = new byte[64];
            InputStream is = new FileInputStream(disk_version_fn);
            int len = is.read(buf);
            disk_version = new String(buf, 0, len);
            is.close();
        } catch (Exception e) {
            disk_version = "";
        }

        // If the disk data is out of date, extract it and write the
        // version file.
        // if (! data_version.equals(disk_version)) {
        if (! data_version.equals(disk_version)) {
            Log.v(TAG, "Extracting " + resource + " assets.");

            recursiveDelete(target);
            target.mkdirs();

            AssetExtract ae = new AssetExtract(this);
            if (!ae.extractTar(resource + ".mp3", target.getAbsolutePath())) {
                toastError("Could not extract " + resource + " data.");
            }

            try {
                // Write .nomedia.
                new File(target, ".nomedia").createNewFile();

                // Write version file.
                FileOutputStream os = new FileOutputStream(disk_version_fn);
                os.write(data_version.getBytes());
                os.close();
            } catch (Exception e) {
                Log.w("python", e);
            }
        }
    }

    public static void loadUrl(String url) {
        class LoadUrl implements Runnable {
            private String mUrl;

            public LoadUrl(String url) {
                mUrl = url;
            }

            public void run() {
                mWebView.loadUrl(mUrl);
            }
        }

        Log.i(TAG, "Opening URL: " + url);
        mActivity.runOnUiThread(new LoadUrl(url));
    }

    public static ViewGroup getLayout() {
        return   mLayout;
    }

    long lastBackClick = SystemClock.elapsedRealtime();
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Check if the key event was the Back button and if there's history
        if ((keyCode == KeyEvent.KEYCODE_BACK) && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        // If it wasn't the Back key or there's no web page history, bubble up to the default
        // system behavior (the back key will hence exit the activity)
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        Log.v(TAG, "onPause()");
        super.onPause();

        if (mActivity.mBrokenLibraries) {
           return;
        }

        if (mWebView != null) {
            mWebView.loadUrl("javascript:onAndroidPause()", null);
            mWebView.onPause();
        }
    }

    @Override
    protected void onResume() {
        Log.v(TAG, "onResume()");
        super.onResume();

        if (mBrokenLibraries) {
           return;
        }

        if (mWebView != null) {
            mWebView.loadUrl("javascript:onAndroidResume()", null);
            mWebView.onResume();
        }
    }

    //----------------------------------------------------------------------------
    // Listener interface for onNewIntent
    //

    public interface NewIntentListener {
        void onNewIntent(Intent intent);
    }

    private List<NewIntentListener> newIntentListeners = null;

    public void registerNewIntentListener(NewIntentListener listener) {
        if ( this.newIntentListeners == null )
            this.newIntentListeners = Collections.synchronizedList(new ArrayList<NewIntentListener>());
        this.newIntentListeners.add(listener);
    }

    public void unregisterNewIntentListener(NewIntentListener listener) {
        if ( this.newIntentListeners == null )
            return;
        this.newIntentListeners.remove(listener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ( this.newIntentListeners == null )
            return;
        this.onResume();
        synchronized ( this.newIntentListeners ) {
            Iterator<NewIntentListener> iterator = this.newIntentListeners.iterator();
            while ( iterator.hasNext() ) {
                (iterator.next()).onNewIntent(intent);
            }
        }
    }

    //----------------------------------------------------------------------------
    // Listener interface for onActivityResult
    //

    public interface ActivityResultListener {
        void onActivityResult(int requestCode, int resultCode, Intent data);
    }

    private List<ActivityResultListener> activityResultListeners = null;

    public void registerActivityResultListener(ActivityResultListener listener) {
        if ( this.activityResultListeners == null )
            this.activityResultListeners = Collections.synchronizedList(new ArrayList<ActivityResultListener>());
        this.activityResultListeners.add(listener);
    }

    public void unregisterActivityResultListener(ActivityResultListener listener) {
        if ( this.activityResultListeners == null )
            return;
        this.activityResultListeners.remove(listener);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if ( this.activityResultListeners == null )
            return;
        this.onResume();
        synchronized ( this.activityResultListeners ) {
            Iterator<ActivityResultListener> iterator = this.activityResultListeners.iterator();
            while ( iterator.hasNext() )
                (iterator.next()).onActivityResult(requestCode, resultCode, intent);
        }
    }

    // loading screen implementation
    public static ImageView mImageView = null;
    public void removeLoadingScreen() {
      runOnUiThread(new Runnable() {
        public void run() {
          if (PythonActivity.mImageView != null && 
                  PythonActivity.mImageView.getParent() != null) {
            ((ViewGroup)PythonActivity.mImageView.getParent()).removeView(
            PythonActivity.mImageView);
            PythonActivity.mImageView = null;
          }
        }
      });
    }


    protected void showLoadingScreen() {
      // load the bitmap
      // 1. if the image is valid and we don't have layout yet, assign this bitmap
      // as main view.
      // 2. if we have a layout, just set it in the layout.
      // 3. If we have an mImageView already, then do nothing because it will have
      // already been made the content view or added to the layout.

      if (mImageView == null) {
        int presplashId = this.resourceManager.getIdentifier("presplash", "drawable");
        InputStream is = this.getResources().openRawResource(presplashId);
        Bitmap bitmap = null;
        try {
          bitmap = BitmapFactory.decodeStream(is);
        } finally {
          try {
            is.close();
          } catch (IOException e) {};
        }

        mImageView = new ImageView(this);
        mImageView.setImageBitmap(bitmap);

        /*
     * Set the presplash loading screen background color
         * https://developer.android.com/reference/android/graphics/Color.html
         * Parse the color string, and return the corresponding color-int.
         * If the string cannot be parsed, throws an IllegalArgumentException exception.
         * Supported formats are: #RRGGBB #AARRGGBB or one of the following names:
         * 'red', 'blue', 'green', 'black', 'white', 'gray', 'cyan', 'magenta', 'yellow',
         * 'lightgray', 'darkgray', 'grey', 'lightgrey', 'darkgrey', 'aqua', 'fuchsia',
         * 'lime', 'maroon', 'navy', 'olive', 'purple', 'silver', 'teal'.
         */
        String backgroundColor = resourceManager.getString("presplash_color");
        if (backgroundColor != null) {
          try {
            mImageView.setBackgroundColor(Color.parseColor(backgroundColor));
          } catch (IllegalArgumentException e) {}
        }   
        mImageView.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.FILL_PARENT,
        ViewGroup.LayoutParams.FILL_PARENT));
        mImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

      }

      if (mLayout == null) {
        setContentView(mImageView);
      } else if (PythonActivity.mImageView.getParent() == null){
        mLayout.addView(mImageView);
      }

    }

    public static void start_service(String serviceTitle, String serviceDescription,
                String pythonServiceArgument) {
        Intent serviceIntent = new Intent(PythonActivity.mActivity, PythonService.class);
        String argument = PythonActivity.mActivity.getFilesDir().getAbsolutePath();
        String filesDirectory = argument;
        serviceIntent.putExtra("androidPrivate", argument);
        serviceIntent.putExtra("androidArgument", argument);
        serviceIntent.putExtra("serviceEntrypoint", "service/main.pyo");
        serviceIntent.putExtra("pythonHome", argument);
        serviceIntent.putExtra("pythonPath", argument + ":" + filesDirectory + "/lib");
        serviceIntent.putExtra("serviceTitle", serviceTitle);
        serviceIntent.putExtra("serviceDescription", serviceDescription);
        serviceIntent.putExtra("pythonServiceArgument", pythonServiceArgument);
        PythonActivity.mActivity.startService(serviceIntent);
    }

    public static void stop_service() {
        Intent serviceIntent = new Intent(PythonActivity.mActivity, PythonService.class);
        PythonActivity.mActivity.stopService(serviceIntent);
    }


    public static native void nativeSetEnv(String j_name, String j_value);
    public static native int nativeInit(Object arguments);

}


class PythonMain implements Runnable {
    @Override
    public void run() {
        PythonActivity.nativeInit(new String[0]);
    }
}

class WebViewLoaderMain implements Runnable {
    @Override
    public void run() {
        WebViewLoader.testConnection();
    }
}
