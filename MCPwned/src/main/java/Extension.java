import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.hotkey.HotKey;
import burp.api.montoya.ui.settings.SettingsPanelBuilder;
import burp.api.montoya.ui.settings.SettingsPanelPersistence;
import burp.api.montoya.ui.settings.SettingsPanelSetting;
import burp.api.montoya.ui.settings.SettingsPanelWithData;
import utils.Logger;

@SuppressWarnings("unused")
public class Extension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        Logger.init(api);
        Logger.info("Extension starting. V1.0.0");
        api.extension().setName("MCPwned");

        // Settings panel (Burp > Settings > Extensions > MCPwned)
        SettingsPanelWithData settings = SettingsPanelBuilder.settingsPanel()
                .withPersistence(SettingsPanelPersistence.USER_SETTINGS)
                .withTitle("MCPwned")
                .withDescription("Configuration for the MCPwned extension.")
                .withSetting(SettingsPanelSetting.booleanSetting(
                        Logger.VERBOSE_SETTING,
                        "Enable detailed debug output in the extension log.",
                        true))
                .build();
        api.userInterface().registerSettingsPanel(settings);
        Logger.setSettings(settings);

        // extension tab
        MCPwnedTab tab = new MCPwnedTab(api);

        api.userInterface().registerSuiteTab("MCPwned", tab.getUiComponent());
        api.userInterface().registerContextMenuItemsProvider(new MenuProvider(tab));

        // Ctrl+M to scan selected request as MCP endpoint
        api.userInterface().registerHotKeyHandler(
                HotKey.hotKey("Scan MCP endpoint", "Ctrl+M"),
                event -> {
                    for (HttpRequestResponse reqRes : event.selectedRequestResponses()) {
                        tab.addRequest(reqRes);
                    }
                });

        // Ctrl+Shift+M to switch to the MCPwned tab
        api.userInterface().registerHotKeyHandler(
                HotKey.hotKey("Go to MCPwned tab", "Ctrl+Shift+M"),
                event -> tab.selectTab());

        // Custom response editor for MCP SSE responses
        api.userInterface().registerHttpResponseEditorProvider(new ResponseEditor());

        // Auto-detect MCP traffic in proxy and highlight in gray
        api.proxy().registerResponseHandler(new McpProxyDetector());

        Logger.info("Extension loaded.");
    }
}
