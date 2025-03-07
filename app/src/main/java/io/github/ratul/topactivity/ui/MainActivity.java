package io.github.ratul.topactivity.ui;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.content.pm.*;
import android.graphics.drawable.*;
import android.graphics.*;
import android.text.*;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import java.util.List;
import io.github.ratul.topactivity.*;
import io.github.ratul.topactivity.utils.*;
import io.github.ratul.topactivity.model.NotificationMonitor;
import io.github.ratul.topactivity.service.*;
import io.github.ratul.topactivity.model.TypefaceSpan;
import java.io.*;
import android.util.DisplayMetrics;
import android.app.AppOpsManager;

/**
 * Created by Wen on 16/02/2017.
 * Refactored by Ratul on 04/05/2022.
 */
public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_FROM_QS_TILE = "from_qs_tile";
    public static final String ACTION_STATE_CHANGED = "io.github.ratul.topactivity.ACTION_STATE_CHANGED";
    private SwitchMaterial mWindowSwitch, mNotificationSwitch, mAccessibilitySwitch;
    private BroadcastReceiver mReceiver;
    private MaterialAlertDialogBuilder fancy;
    public static MainActivity INSTANCE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        INSTANCE = this;

        if (AccessibilityMonitoringService.getInstance() == null && DatabaseUtil.hasAccess()) {
            startService(new Intent().setClass(this, AccessibilityMonitoringService.class));
        }

        DatabaseUtil.setDisplayWidth(getScreenWidth(this));
        fancy = new MaterialAlertDialogBuilder(this)
                .setNegativeButton("Close", (di, btn) -> di.dismiss())
                .setCancelable(false);

        SpannableString s = new SpannableString(getString(R.string.app_name));
        s.setSpan(new TypefaceSpan(this, "fonts/google_sans_bold.ttf"), 0, s.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(s);
        }

        mWindowSwitch = findViewById(R.id.sw_window);
        mNotificationSwitch = findViewById(R.id.sw_notification);
        mAccessibilitySwitch = findViewById(R.id.sw_accessibility);

        if (Build.VERSION.SDK_INT < 24) {
            mNotificationSwitch.setVisibility(View.INVISIBLE);
            findViewById(R.id.divider_useNotificationPref).setVisibility(View.INVISIBLE);
        }

        mReceiver = new UpdateSwitchReceiver();
        registerReceiver(mReceiver, new IntentFilter(ACTION_STATE_CHANGED));

        mNotificationSwitch.setOnCheckedChangeListener((button, isChecked) ->
                DatabaseUtil.setNotificationToggleEnabled(!isChecked));

        mAccessibilitySwitch.setOnCheckedChangeListener((button, isChecked) -> {
            DatabaseUtil.setHasAccess(isChecked);
            if (isChecked && AccessibilityMonitoringService.getInstance() == null) {
                startService(new Intent().setClass(MainActivity.this, AccessibilityMonitoringService.class));
            }
        });

        mWindowSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) {
                showPermissionDialog("Overlay Permission", "Please enable overlay permission to show window over other apps",
                        "Settings", Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                mWindowSwitch.setChecked(false);
            } else if (DatabaseUtil.hasAccess() && AccessibilityMonitoringService.getInstance() == null) {
                showPermissionDialog("Accessibility Permission",
                        "As per your choice, please grant permission to use Accessibility Service for Current Activity app in order to get current activity info",
                        "Settings", "android.settings.ACCESSIBILITY_SETTINGS");
                mWindowSwitch.setChecked(false);
            } else if (!usageStats(MainActivity.this)) {
                showPermissionDialog("Usage Access",
                        "In order to monitor current task, please grant Usage Access permission for Current Activity app",
                        "Settings", "android.settings.USAGE_ACCESS_SETTINGS");
                mWindowSwitch.setChecked(false);
            } else {
                DatabaseUtil.setAppInitiated(true);
                DatabaseUtil.setIsShowWindow(isChecked);
                if (!isChecked) {
                    WindowUtil.dismiss(MainActivity.this);
                } else {
                    WindowUtil.show(MainActivity.this, getPackageName(), getClass().getName());
                    startService(new Intent(MainActivity.this, MonitoringService.class));
                }
            }
        });

        if (getIntent().getBooleanExtra(EXTRA_FROM_QS_TILE, false)) {
            mWindowSwitch.setChecked(true);
        }
    }

    private void showPermissionDialog(String title, String message, String buttonText, String action) {
        fancy.setTitle(title)
                .setMessage(message)
                .setPositiveButton(buttonText, (di, btn) -> {
                    Intent intent = new Intent(action);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    di.dismiss();
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    public String readFile(File file) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                text.append(line).append("\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return text.toString();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        String title = item.getTitle().toString();
        switch (title) {
            case "About App":
                fancy.setTitle("About App").setMessage(
                        "An useful open source tool for Android Developers, which shows the package name and class name of current activity\n\nHere are the main features of this app!\n● It provides a freely moveable popup window to view current activity info\n● It supports text copying from popup window\n● It supports quick settings and app shortcut for easy access to the popup window. Meaning you can get the popup window in your screen from anywhere")
                        .show();
                break;
            case "Crash Log":
                String errorLog = readFile(new File(App.getCrashLogDir(), "crash.txt"));
                if (errorLog.isEmpty())
                    showToast("No log was found", Toast.LENGTH_SHORT);
                else {
                    Intent intent = new Intent(this, CrashActivity.class);
                    intent.putExtra(CrashActivity.EXTRA_CRASH_INFO, errorLog);
                    intent.putExtra("Restart", false);
                    startActivity(intent);
                }
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    public static int getScreenWidth(Context context) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        }
        return displayMetrics.widthPixels;
    }

    public static boolean usageStats(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return (context.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED);
            }
            return (mode == AppOpsManager.MODE_ALLOWED);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void showToast(String message, int length) {
        runOnUiThread(() -> Toast.makeText(this, message, length).show());
    }

    class UpdateSwitchReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            mWindowSwitch.setChecked(DatabaseUtil.isShowWindow());
            mNotificationSwitch.setChecked(!DatabaseUtil.isNotificationToggleEnabled());
            mAccessibilitySwitch.setChecked(DatabaseUtil.hasAccess());
        }
    }
    }
