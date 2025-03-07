package io.github.ratul.topactivity.ui;

import android.app.*;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import java.util.List;
import io.github.ratul.topactivity.*;
import io.github.ratul.topactivity.utils.*;
import io.github.ratul.topactivity.service.*;
import io.github.ratul.topactivity.model.TypefaceSpan;
import java.io.*;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_FROM_QS_TILE = "from_qs_tile";
    public static final String ACTION_STATE_CHANGED = "io.github.ratul.topactivity.ACTION_STATE_CHANGED";
    private SwitchMaterial mWindowSwitch, mNotificationSwitch, mAccessibilitySwitch;
    private BroadcastReceiver mReceiver;
    private MaterialAlertDialogBuilder fancy;
    public static MainActivity INSTANCE;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (!isGranted) {
                    showToast("Permission denied. Some features may not work.", Toast.LENGTH_LONG);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        INSTANCE = this;

        // Проверка разрешений на Android 13+ (POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkNotificationPermission();
        }

        if (AccessibilityMonitoringService.getInstance() == null && DatabaseUtil.hasAccess()) {
            startService(new Intent(this, AccessibilityMonitoringService.class));
        }

        DatabaseUtil.setDisplayWidth(getScreenWidth());
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
                startService(new Intent(MainActivity.this, AccessibilityMonitoringService.class));
            }
        });

        mWindowSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) {
                showPermissionDialog("Overlay Permission",
                        "Please enable overlay permission to show window over other apps",
                        "Settings", Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                mWindowSwitch.setChecked(false);
            } else if (DatabaseUtil.hasAccess() && AccessibilityMonitoringService.getInstance() == null) {
                showPermissionDialog("Accessibility Permission",
                        "Please grant Accessibility permission for Current Activity app",
                        "Settings", Settings.ACTION_ACCESSIBILITY_SETTINGS);
                mWindowSwitch.setChecked(false);
            } else if (!usageStats(MainActivity.this)) {
                showPermissionDialog("Usage Access",
                        "Please grant Usage Access permission for Current Activity app",
                        "Settings", Settings.ACTION_USAGE_ACCESS_SETTINGS);
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

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void checkNotificationPermission() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void showPermissionDialog(String title, String message, String buttonText, String action) {
        fancy.setTitle(title)
                .setMessage(message)
                .setPositiveButton(buttonText, (di, btn) -> {
                    Intent intent = new Intent(action);
                    if (!action.equals(Settings.ACTION_ACCESSIBILITY_SETTINGS)) {
                        intent.setData(Uri.parse("package:" + getPackageName()));
                    }
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
                fancy.setTitle("About App").setMessage("An open-source tool for Android Developers...").show();
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
            case "GitHub Repo":
                fancy.setTitle("GitHub Repo").setMessage("Visit the official GitHub repo?")
                        .setPositiveButton("Yes", (di, btn) -> {
                            di.dismiss();
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FlutterGenerator/Current-Activity")));
                        }).show();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean usageStats(Context context) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        long currentTime = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, currentTime - 1000 * 60 * 60, currentTime);
        return stats != null && !stats.isEmpty();
    }

    private int getScreenWidth() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        return displayMetrics.widthPixels;
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
