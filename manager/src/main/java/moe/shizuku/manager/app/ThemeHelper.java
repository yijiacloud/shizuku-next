package moe.shizuku.manager.app;

import android.content.Context;
import android.os.Build;

import androidx.annotation.StyleRes;

import moe.shizuku.manager.R;
import moe.shizuku.manager.ShizukuSettings;
import moe.shizuku.manager.utils.EnvironmentUtils;
import rikka.core.util.ResourceUtils;

public class ThemeHelper {

    private static final String THEME_DEFAULT = "DEFAULT";
    private static final String THEME_BLACK = "BLACK";
    private static final String THEME_MD3 = "MD3";
    private static final String THEME_MD3_BLACK = "MD3_BLACK";

    public static final String KEY_LIGHT_THEME = "light_theme";
    public static final String KEY_BLACK_NIGHT_THEME = "black_night_theme";
    public static final String KEY_USE_SYSTEM_COLOR = "use_system_color";

    public static boolean isBlackNightTheme(Context context) {
        return ShizukuSettings.getPreferences().getBoolean(KEY_BLACK_NIGHT_THEME, EnvironmentUtils.isWatch(context));
    }

    public static boolean isUsingSystemColor() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ShizukuSettings.getPreferences().getBoolean(KEY_USE_SYSTEM_COLOR, true);
    }

    public static boolean isMD3Theme(Context context) {
        return ShizukuSettings.getPreferences().getBoolean(ShizukuSettings.USE_MD3_THEME, false);
    }

    public static String getTheme(Context context) {
        boolean md3 = isMD3Theme(context);
        boolean blackNight = isBlackNightTheme(context)
                && ResourceUtils.isNightMode(context.getResources().getConfiguration());

        if (md3 && blackNight) {
            return THEME_MD3_BLACK;
        }
        if (md3) {
            return THEME_MD3;
        }
        if (blackNight) {
            return THEME_BLACK;
        }

        return ShizukuSettings.getPreferences().getString(KEY_LIGHT_THEME, THEME_DEFAULT);
    }

    @StyleRes
    public static int getThemeStyleRes(Context context) {
        switch (getTheme(context)) {
            case THEME_BLACK:
                return R.style.ThemeOverlay_Black;
            case THEME_MD3:
                return R.style.ThemeOverlay_MD3;
            case THEME_MD3_BLACK:
                return R.style.ThemeOverlay_MD3_Black;
            case THEME_DEFAULT:
            default:
                return R.style.ThemeOverlay;
        }
    }
}