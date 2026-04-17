import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;
import com.google.gson.*;

import javax.swing.*;
import java.awt.*;

/**
 * Provides a custom "MCP" tab in Burp's response viewer that parses
 * SSE-formatted MCP JSON-RPC responses and displays them in Pretty, Raw, and Hex views.
 */
public class ResponseEditor implements HttpResponseEditorProvider {

    public ResponseEditor() {
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext context) {
        return new McpResponseEditorTab();
    }

    private static class McpResponseEditorTab implements ExtensionProvidedHttpResponseEditor {

        private final JTabbedPane tabbedPane;
        private final JTextArea prettyArea;
        private final JTextArea rawArea;
        private final JTextArea hexArea;
        private HttpRequestResponse currentRequestResponse;

        McpResponseEditorTab() {
            this.tabbedPane = new JTabbedPane();

            prettyArea = createTextArea();
            rawArea = createTextArea();
            hexArea = createTextArea();

            tabbedPane.addTab("Pretty", new JScrollPane(prettyArea));
            tabbedPane.addTab("Raw", new JScrollPane(rawArea));
            tabbedPane.addTab("Hex", new JScrollPane(hexArea));
        }

        private static JTextArea createTextArea() {
            JTextArea area = new JTextArea();
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            return area;
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            if (requestResponse == null || requestResponse.response() == null) return false;
            String body = requestResponse.response().bodyToString();
            // Enable for SSE-formatted MCP responses containing JSON-RPC
            return body != null && body.contains("event:") && body.contains("\"jsonrpc\"");
        }

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            this.currentRequestResponse = requestResponse;
            if (requestResponse == null || requestResponse.response() == null) {
                prettyArea.setText("");
                rawArea.setText("");
                hexArea.setText("");
                return;
            }

            String body = requestResponse.response().bodyToString();
            ParsedMcpResponse parsed = parseSseResponse(body);

            prettyArea.setText(parsed.pretty);
            prettyArea.setCaretPosition(0);

            rawArea.setText(parsed.raw);
            rawArea.setCaretPosition(0);

            hexArea.setText(parsed.hex);
            hexArea.setCaretPosition(0);
        }

        @Override
        public HttpResponse getResponse() {
            return currentRequestResponse != null ? currentRequestResponse.response() : null;
        }

        @Override
        public String caption() {
            return "MCParsed (MCPwned)";
        }

        @Override
        public Component uiComponent() {
            return tabbedPane;
        }

        @Override
        public Selection selectedData() {
            JTextArea active;
            int tabIndex = tabbedPane.getSelectedIndex();
            active = switch (tabIndex) {
                case 1 -> rawArea;
                case 2 -> hexArea;
                default -> prettyArea;
            };
            if (active.getSelectedText() != null) {
                return Selection.selection(active.getSelectionStart(), active.getSelectionEnd());
            }
            return null;
        }

        @Override
        public boolean isModified() {
            return false;
        }

        private static ParsedMcpResponse parseSseResponse(String body) {
            StringBuilder pretty = new StringBuilder();
            StringBuilder raw = new StringBuilder();
            StringBuilder contentText = new StringBuilder();

            // Parse SSE lines: extract "data:" lines
            String[] lines = body.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    String jsonStr = trimmed.substring(5).trim();
                    raw.append(jsonStr).append("\n");

                    try {
                        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();

                        // Check for error
                        if (json.has("error")) {
                            JsonObject error = json.getAsJsonObject("error");
                            pretty.append("ERROR");
                            if (error.has("code")) {
                                pretty.append(" [").append(error.get("code").getAsInt()).append("]");
                            }
                            pretty.append("\n");
                            if (error.has("message")) {
                                pretty.append(error.get("message").getAsString()).append("\n");
                            }
                            if (error.has("data")) {
                                pretty.append("\nData:\n");
                                pretty.append(new GsonBuilder().setPrettyPrinting().create().toJson(error.get("data")));
                                pretty.append("\n");
                            }
                            continue;
                        }

                        // Extract result
                        if (!json.has("result")) continue;
                        JsonObject result = json.getAsJsonObject("result");

                        // Extract content array (tools/call response)
                        if (result.has("content")) {
                            JsonArray content = result.getAsJsonArray("content");
                            for (JsonElement el : content) {
                                if (!el.isJsonObject()) continue;
                                JsonObject item = el.getAsJsonObject();
                                String type = item.has("type") ? item.get("type").getAsString() : "unknown";

                                if ("text".equals(type) && item.has("text")) {
                                    String text = item.get("text").getAsString();
                                    pretty.append(text);
                                    if (!text.endsWith("\n")) pretty.append("\n");
                                    contentText.append(text);
                                } else if ("image".equals(type)) {
                                    pretty.append("[Image: ");
                                    if (item.has("mimeType")) pretty.append(item.get("mimeType").getAsString()).append(" ");
                                    pretty.append("base64 data]\n");
                                } else if ("resource".equals(type) && item.has("resource")) {
                                    JsonObject res = item.getAsJsonObject("resource");
                                    pretty.append("[Resource: ");
                                    if (res.has("uri")) pretty.append(res.get("uri").getAsString());
                                    pretty.append("]\n");
                                    if (res.has("text")) {
                                        String text = res.get("text").getAsString();
                                        pretty.append(text);
                                        if (!text.endsWith("\n")) pretty.append("\n");
                                        contentText.append(text);
                                    }
                                } else {
                                    // Unknown content type — pretty print the whole object
                                    pretty.append(new GsonBuilder().setPrettyPrinting().create().toJson(item));
                                    pretty.append("\n");
                                }
                            }
                        }

                        // Extract isError flag
                        if (result.has("isError") && result.get("isError").getAsBoolean()) {
                            pretty.insert(0, "--- SERVER REPORTED ERROR ---\n");
                        }

                        // Resources/read response
                        if (result.has("contents")) {
                            JsonArray contents = result.getAsJsonArray("contents");
                            for (JsonElement el : contents) {
                                if (!el.isJsonObject()) continue;
                                JsonObject item = el.getAsJsonObject();
                                if (item.has("uri")) {
                                    pretty.append("URI: ").append(item.get("uri").getAsString()).append("\n");
                                }
                                if (item.has("text")) {
                                    String text = item.get("text").getAsString();
                                    pretty.append(text);
                                    if (!text.endsWith("\n")) pretty.append("\n");
                                    contentText.append(text);
                                }
                                if (item.has("mimeType")) {
                                    pretty.append("(").append(item.get("mimeType").getAsString()).append(")\n");
                                }
                            }
                        }

                        // Prompts/get response
                        if (result.has("messages")) {
                            JsonArray messages = result.getAsJsonArray("messages");
                            for (JsonElement el : messages) {
                                if (!el.isJsonObject()) continue;
                                JsonObject msg = el.getAsJsonObject();
                                String role = msg.has("role") ? msg.get("role").getAsString() : "?";
                                pretty.append("[").append(role).append("]\n");
                                if (msg.has("content")) {
                                    JsonObject msgContent = msg.getAsJsonObject("content");
                                    if (msgContent.has("text")) {
                                        String text = msgContent.get("text").getAsString();
                                        pretty.append(text);
                                        if (!text.endsWith("\n")) pretty.append("\n");
                                        contentText.append(text);
                                    }
                                }
                            }
                        }

                        // Fallback: if nothing was extracted, pretty-print the full result
                        if (pretty.isEmpty()) {
                            pretty.append(new GsonBuilder().setPrettyPrinting().create().toJson(result));
                            pretty.append("\n");
                        }

                    } catch (Exception e) {
                        // Not valid JSON, just append raw
                        pretty.append(jsonStr).append("\n");
                    }
                }
            }

            if (pretty.isEmpty()) {
                pretty.append("(No MCP data found in response)");
            }

            // Build hex view from the extracted content text
            String hexContent = !contentText.isEmpty() ? contentText.toString() : raw.toString();
            String hex = buildHexDump(hexContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return new ParsedMcpResponse(pretty.toString(), raw.toString(), hex);
        }

        private static String buildHexDump(byte[] data) {
            StringBuilder sb = new StringBuilder();
            int offset = 0;
            while (offset < data.length) {
                sb.append(String.format("%08x  ", offset));

                // Hex part
                int lineLen = Math.min(16, data.length - offset);
                for (int i = 0; i < 16; i++) {
                    if (i < lineLen) {
                        sb.append(String.format("%02x ", data[offset + i] & 0xFF));
                    } else {
                        sb.append("   ");
                    }
                    if (i == 7) sb.append(" ");
                }

                // ASCII part
                sb.append(" |");
                for (int i = 0; i < lineLen; i++) {
                    byte b = data[offset + i];
                    if (b >= 32 && b < 127) {
                        sb.append((char) b);
                    } else {
                        sb.append('.');
                    }
                }
                sb.append("|\n");
                offset += 16;
            }
            return sb.toString();
        }

        private record ParsedMcpResponse(String pretty, String raw, String hex) {
        }
    }
}
