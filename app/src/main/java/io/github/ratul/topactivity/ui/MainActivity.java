/*
 *   Copyright (C) 2022 Ratul Hasan
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 package io.github.ratul.topactivity.ui;

import android.app.*;
import android.app.AppOpsManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import io.github.ratul.topactivity.*;
import io.github.ratul.topactivity.model.NotificationMonitor;
import io.github.ratul.topactivity.service.*;
import io.github.ratul.topactivity.utils.*;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_FROM_QS_TILE = "from_qs_tile";
    public static final String ACTION_STATE_CHANGED = "io.github.ratul.topactivity.ACTION_STATE_CHANGED";

    private SwitchMaterial mWindowSwitch, mNotificationSwitch, mAccessibilitySwitch;
    private UpdateSwitchReceiver mReceiver;
    private MaterialAlertDialogBuilder fancy;
    public static MainActivity INSTANCE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        INSTANCE = this;

        if (AccessibilityMonitoringService.getInstance() == null && DatabaseUtil.hasAccess()) {
            startService(new Intent(this, AccessibilityMonitoringService.class));
        }

        DatabaseUtil.setDisplayWidth(getScreenWidth(this));

        fancy = new MaterialAlertDialogBuilder(this)
                .setNegativeButton("Close", (di, btn) -> di.dismiss())
                .setCancelable(false);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(getString(R.string.app_name));
        }

        mWindowSwitch = findViewById(R.id.sw_window);
        mNotificationSwitch = findViewById(R.id.sw_notification);
        mAccessibilitySwitch = findViewById(R.id.sw_accessibility);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            mNotificationSwitch.setVisibility(View.INVISIBLE);
            findViewById(R.id.divider_useNotificationPref).setVisibility(View.INVISIBLE);
        }

        mReceiver = new UpdateSwitchReceiver();
        IntentFilter filter = new IntentFilter(ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mReceiver, filter);
        }

        setupSwitchListeners();

        if (getIntent().getBooleanExtra(EXTRA_FROM_QS_TILE, false)) {
            mWindowSwitch.setChecked(true);
        }
    }

    private void setupSwitchListeners() {
        mNotificationSwitch.setOnCheckedChangeListener((button, isChecked) ->
                DatabaseUtil.setNotificationToggleEnabled(!isChecked));

        mAccessibilitySwitch.setOnCheckedChangeListener((button, isChecked) -> {
            DatabaseUtil.setHasAccess(isChecked);
            if (isChecked && AccessibilityMonitoringService.getInstance() == null) {
                startService(new Intent(this, AccessibilityMonitoringService.class));
            }
        });

        mWindowSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                showPermissionDialog("Overlay Permission",
                        "Please enable overlay permission to show window over other apps",
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                mWindowSwitch.setChecked(false);
            } else if (DatabaseUtil.hasAccess() && AccessibilityMonitoringService.getInstance() == null) {
                showPermissionDialog("Accessibility Permission",
                        "Please grant permission to use Accessibility Service for Current Activity app.",
                        Settings.ACTION_ACCESSIBILITY_SETTINGS);
                mWindowSwitch.setChecked(false);
            } else if (!usageStats(this)) {
                showPermissionDialog("Usage Access",
                        "Please grant Usage Access permission for Current Activity app.",
                        Settings.ACTION_USAGE_ACCESS_SETTINGS);
                mWindowSwitch.setChecked(false);
            } else {
                DatabaseUtil.setAppInitiated(true);
                DatabaseUtil.setIsShowWindow(isChecked);
                if (!isChecked) {
                    WindowUtil.dismiss(this);
                } else {
                    WindowUtil.show(this, getPackageName(), getClass().getName());
                    startService(new Intent(this, MonitoringService.class));
                }
            }
        });
    }

    private void showPermissionDialog(String title, String message, String settingsAction) {
        fancy.setTitle(title)
                .setMessage(message)
                .setPositiveButton("Settings", (di, btn) -> {
                    di.dismiss();
                    Intent intent = new Intent(settingsAction);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .show();
    }

    public static int getScreenWidth(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            return metrics.getBounds().width();
        } else {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            return displayMetrics.widthPixels;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_FROM_QS_TILE, false)) {
            mWindowSwitch.setChecked(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSwitches();
        NotificationMonitor.cancelNotification(this);
    }

    private void refreshSwitches() {
        refreshWindowSwitch();
        refreshNotificationSwitch();
        refreshAccessibilitySwitch();
    }

    private void refreshWindowSwitch() {
        mWindowSwitch.setChecked(DatabaseUtil.isShowWindow());
    }

    private void refreshAccessibilitySwitch() {
        mAccessibilitySwitch.setChecked(DatabaseUtil.hasAccess());
    }

    private void refreshNotificationSwitch() {
        mNotificationSwitch.setChecked(!DatabaseUtil.isNotificationToggleEnabled());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            // Игнорируем ошибку, если ресивер не был зарегистрирован
        }
    }

    class UpdateSwitchReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSwitches();
        }
    }

    public static boolean usageStats(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return mode == AppOpsManager.MODE_ALLOWED;
        }
    }

    public void setupBattery() {
        fancy.setTitle("Battery Optimizations")
                .setMessage("Please disable battery optimization for this app to run in the background.")
                .setPositiveButton("Ok", (di, btn) -> {
                    di.dismiss();
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .show();
    }
}
