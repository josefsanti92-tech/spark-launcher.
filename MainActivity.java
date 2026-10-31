package com.spark.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Spark LTZ Launcher: pantalla de inicio para el estéreo Android.
 * La interfaz es una página web (assets/index.html) y esta clase le da
 * acceso a lo que solo Android puede hacer: abrir apps, voz, GPS, controles de música.
 */
public class MainActivity extends Activity implements LocationListener, TextToSpeech.OnInitListener {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeak = null;
    private SharedPreferences prefs;
    private LocationManager lm;
    private Location lastGps = null;
    private boolean pageReady = false;
    private String lastLocJs = null;
    private BroadcastReceiver screenRx;
    private String appsCache = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        prefs = getSharedPreferences("spark", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (lastLocJs != null) js(lastLocJs);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("file:")) return false;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) { /* sin navegador */ }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, this);

        screenRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                js("window.onScreenOn&&window.onScreenOn()");
            }
        };
        registerReceiver(screenRx, new IntentFilter(Intent.ACTION_SCREEN_ON));

        startLocation();
    }

    // ---------- utilidades ----------

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean launch(String pkg) {
        if (pkg == null) return false;
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<ResolveInfo> launchables() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo != null && !getPackageName().equals(ri.activityInfo.packageName)) out.add(ri);
        }
        final PackageManager fpm = pm;
        Collections.sort(out, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(fpm)).compareToIgnoreCase(String.valueOf(b.loadLabel(fpm)));
            }
        });
        return out;
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Busca una app adecuada para cada botón si el usuario aún no eligió una. */
    private String guess(String key) {
        String[] pkgs;
        String[] words;
        if ("maps".equals(key)) {
            pkgs = new String[]{"com.google.android.apps.maps", "com.waze"};
            words = new String[]{"maps", "mapas", "waze"};
        } else if ("ytmusic".equals(key)) {
            pkgs = new String[]{"com.google.android.apps.youtube.music", "com.google.android.youtube"};
            words = new String[]{"youtube music", "yt music"};
        } else if ("zlink".equals(key)) {
            pkgs = new String[]{};
            words = new String[]{"zlink", "carplay", "car play", "carlink", "autolink", "android auto", "tlink"};
        } else if ("eq".equals(key)) {
            pkgs = new String[]{};
            words = new String[]{"ecualiz", "equaliz", "dsp", "sonido", "audio", "sound"};
        } else if ("phone".equals(key)) {
            pkgs = new String[]{};
            words = new String[]{"bluetooth", "teléfono", "telefono", "phone", "marcador", "dialer", "bt"};
        } else {
            return null;
        }
        for (String p : pkgs) if (installed(p)) return p;
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = launchables();
        for (String w : words) {
            for (ResolveInfo ri : apps) {
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
                boolean hit = w.length() <= 2 ? label.equals(w) : (label.contains(w) || pkg.contains(w.replace(" ", "")));
                if (hit) return ri.activityInfo.packageName;
            }
        }
        return null;
    }

    private void pickApp(final String key, final boolean openAfter) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final PackageManager pm = getPackageManager();
                final List<ResolveInfo> apps = launchables();
                String[] labels = new String[apps.size()];
                for (int i = 0; i < apps.size(); i++) labels[i] = String.valueOf(apps.get(i).loadLabel(pm));
                new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("¿Qué app abre este botón?")
                        .setItems(labels, (d, w) -> {
                            String pkg = apps.get(w).activityInfo.packageName;
                            prefs.edit().putString("app_" + key, pkg).apply();
                            toast("Listo: este botón abrirá " + labels[w]);
                            if (openAfter) launch(pkg);
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            }
        });
    }

    private float oilRemaining() {
        float odo = prefs.getFloat("odo", 0f);
        float base = prefs.getFloat("oil_base", 0f);
        int interval = prefs.getInt("oil_interval", 5000);
        return interval - (odo - base);
    }

    // ---------- puente para la página ----------

    private class Bridge {
        @JavascriptInterface
        public boolean isApp() {
            return true;
        }

        @JavascriptInterface
        public void speak(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saludo");
                    else pendingSpeak = text;
                }
            });
        }

        @JavascriptInterface
        public void openKey(final String key) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String pkg = prefs.getString("app_" + key, null);
                    if (pkg != null && launch(pkg)) return;
                    pkg = guess(key);
                    if (pkg != null && launch(pkg)) return;
                    toast("Elige qué app quieres abrir con este botón");
                    MainActivity.this.pickApp(key, true);
                }
            });
        }

        @JavascriptInterface
        public void pickApp(String key) {
            MainActivity.this.pickApp(key, false);
        }

        @JavascriptInterface
        public void openPkg(final String pkg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!launch(pkg)) toast("No se pudo abrir la app");
                }
            });
        }

        @JavascriptInterface
        public void openSettings() {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("No se pudieron abrir los ajustes");
            }
        }

        @JavascriptInterface
        public String getApps() {
            if (appsCache != null) return appsCache;
            try {
                PackageManager pm = getPackageManager();
                JSONArray arr = new JSONArray();
                for (ResolveInfo ri : launchables()) {
                    JSONObject o = new JSONObject();
                    o.put("l", String.valueOf(ri.loadLabel(pm)));
                    o.put("p", ri.activityInfo.packageName);
                    try {
                        Drawable d = ri.loadIcon(pm);
                        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(b);
                        d.setBounds(0, 0, 96, 96);
                        d.draw(c);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
                        o.put("i", "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                    } catch (Exception e) { /* sin ícono */ }
                    arr.put(o);
                }
                appsCache = arr.toString();
                return appsCache;
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void media(String action) {
            int code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            if ("prev".equals(action)) code = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            else if ("next".equals(action)) code = KeyEvent.KEYCODE_MEDIA_NEXT;
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }

        @JavascriptInterface
        public String getOil() {
            try {
                JSONObject o = new JSONObject();
                o.put("remaining", Math.round(oilRemaining()));
                o.put("interval", prefs.getInt("oil_interval", 5000));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void oilMenu() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    final String[] items = {
                            "Ya cambié el aceite (reiniciar)",
                            "Avisarme cada 3,000 km",
                            "Avisarme cada 5,000 km",
                            "Avisarme cada 7,500 km",
                            "Avisarme cada 10,000 km"};
                    final int[] km = {0, 3000, 5000, 7500, 10000};
                    new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                            .setTitle("Cambio de aceite")
                            .setItems(items, (d, w) -> {
                                SharedPreferences.Editor e = prefs.edit();
                                if (w == 0) e.putFloat("oil_base", prefs.getFloat("odo", 0f));
                                else e.putInt("oil_interval", km[w]);
                                e.apply();
                                js("window.refreshOil&&window.refreshOil()");
                            })
                            .setNegativeButton("Cerrar", null)
                            .show();
                }
            });
        }

        @JavascriptInterface
        public String placeName(double lat, double lon) {
            try {
                if (!Geocoder.isPresent()) return "";
                List<Address> res = new Geocoder(MainActivity.this, new Locale("es", "MX")).getFromLocation(lat, lon, 1);
                if (res == null || res.isEmpty()) return "";
                Address a = res.get(0);
                if (a.getLocality() != null) return a.getLocality();
                if (a.getSubAdminArea() != null) return a.getSubAdminArea();
                return "";
            } catch (Exception e) {
                return "";
            }
        }
    }

    // ---------- voz ----------

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        int r = tts.setLanguage(new Locale("es", "MX"));
        if (r < 0) r = tts.setLanguage(new Locale("es", "US"));
        if (r < 0) r = tts.setLanguage(new Locale("es", "ES"));
        if (r < 0) tts.setLanguage(new Locale("es"));
        ttsReady = true;
        if (pendingSpeak != null) {
            tts.speak(pendingSpeak, TextToSpeech.QUEUE_FLUSH, null, "saludo");
            pendingSpeak = null;
        }
    }

    // ---------- GPS ----------

    private void startLocation() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (Exception e) { /* sin GPS */ }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 0, this);
        } catch (Exception e) { /* sin red */ }
        try {
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) sendLocation(l, 0f);
        } catch (Exception e) { /* nada */ }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startLocation();
    }

    @Override
    public void onLocationChanged(Location l) {
        float kmh = 0f;
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        if (gps) {
            if (l.hasSpeed()) kmh = l.getSpeed() * 3.6f;
            else if (lastGps != null) {
                float dt = (l.getTime() - lastGps.getTime()) / 1000f;
                if (dt > 0) kmh = lastGps.distanceTo(l) / dt * 3.6f;
            }
            // odómetro para el aviso de aceite
            if (lastGps != null && l.getAccuracy() < 35) {
                float d = lastGps.distanceTo(l);
                if (d > 3 && d < 800 && kmh > 4) {
                    prefs.edit().putFloat("odo", prefs.getFloat("odo", 0f) + d / 1000f).apply();
                }
            }
            lastGps = l;
        } else if (lastGps != null && System.currentTimeMillis() - lastGps.getTime() < 15000) {
            return; // el GPS manda; la red solo sirve cuando no hay GPS
        }
        sendLocation(l, kmh);
    }

    private void sendLocation(Location l, float kmh) {
        lastLocJs = "window.onCarLocation&&window.onCarLocation(" + kmh + "," + l.getLatitude() + "," + l.getLongitude() + ")";
        if (pageReady) js(lastLocJs);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    // ---------- ciclo de vida ----------

    @Override
    public void onBackPressed() {
        js("window.onBack&&window.onBack()");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        js("window.onHome&&window.onHome()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        appsCache = null; // por si se instalaron apps nuevas
        js("window.refreshOil&&window.refreshOil()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(screenRx); } catch (Exception e) { /* nada */ }
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception e) { /* nada */ }
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
