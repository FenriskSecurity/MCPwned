package utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.settings.SettingsPanelWithData;

public class Logger {

    private static final String PREFIX = "MCPwned";
    public static final String VERBOSE_SETTING = "Verbose debug logging";

    private static MontoyaApi api;
    private static SettingsPanelWithData settings;

    public static void init(MontoyaApi montoyaApi) {
        api = montoyaApi;
    }

    public static void setSettings(SettingsPanelWithData settingsPanel) {
        settings = settingsPanel;
    }

    public static boolean isVerbose() {
        if (settings != null) {
            return settings.getBoolean(VERBOSE_SETTING);
        }
        return true;
    }

    public static void info(String msg) {
        if (api != null) {
            api.logging().logToOutput(PREFIX + ": " + msg);
        }
    }

    public static void debug(String msg) {
        if (isVerbose() && api != null) {
            api.logging().logToOutput(PREFIX + " [DEBUG]: " + msg);
        }
    }

    public static void error(String msg) {
        if (api != null) {
            api.logging().logToError(PREFIX + ": " + msg);
        }
    }

    @SuppressWarnings("unused")
    public static void error(String msg, Exception e) {
        if (api != null) {
            api.logging().logToError(PREFIX + ": " + msg + ": " + e.getMessage());
        }
    }
}
