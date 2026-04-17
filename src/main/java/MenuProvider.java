import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class MenuProvider implements ContextMenuItemsProvider {

    /**
     * Provides a way to send a request from another pane to the MCPwned pane
     */

    private final MCPwnedTab tab;

    public MenuProvider(MCPwnedTab tab) {
        this.tab = tab;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> menuItems = new ArrayList<>();

        // Get the request from either the table selection or the message editor
        HttpRequestResponse requestResponse = getRequestResponse(event);

        JMenuItem sendItem = new JMenuItem("Scan MCP endpoint with MCPwned");
        sendItem.addActionListener(e -> {
            HttpRequestResponse rr = getRequestResponse(event);
            if (rr != null) tab.addRequest(rr);
        });
        menuItems.add(sendItem);

        // Show "Refresh MCP Session" if the request matches a known server
        if (requestResponse != null) {
            String serverUrl = tab.findServerUrlForRequest(requestResponse);
            if (serverUrl != null) {
                JMenuItem refreshItem = new JMenuItem("Refresh MCP Session (MCPwned)");
                // If invoked from a message editor (Repeater), update the request header in-place
                MessageEditorHttpRequestResponse editor = event.messageEditorRequestResponse().orElse(null);
                refreshItem.addActionListener(e -> tab.refreshServer(serverUrl, sessionId -> {
                    if (editor != null && sessionId != null) {
                        editor.setRequest(
                                editor.requestResponse().request()
                                        .withRemovedHeader("Mcp-Session-Id")
                                        .withHeader("Mcp-Session-Id", sessionId));
                    }
                }));
                menuItems.add(refreshItem);
            }
        }

        return menuItems;
    }

    private static HttpRequestResponse getRequestResponse(ContextMenuEvent event) {
        // In Repeater/message editors, the request is in messageEditorRequestResponse
        if (event.messageEditorRequestResponse().isPresent()) {
            return event.messageEditorRequestResponse().get().requestResponse();
        }
        // In Proxy history / Site map, it's in selectedRequestResponses
        List<HttpRequestResponse> selected = event.selectedRequestResponses();
        if (!selected.isEmpty()) {
            return selected.getFirst();
        }
        return null;
    }
}
