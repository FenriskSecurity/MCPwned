import burp.api.montoya.http.message.HttpRequestResponse;
import com.google.gson.*;

import java.awt.*;
import java.util.Map;

/**
 * Data holder for tree nodes. Each leaf node (tool/resource/prompt) carries
 * the raw JsonObject, its type, and a reference to the original request
 * so we can build Repeater requests from it.
 */
public class MCPNodeData {

    public enum NodeType {
        SERVER, CATEGORY, TOOL, RESOURCE, RESOURCE_TEMPLATE, PROMPT
    }

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private final NodeType type;
    private String label;
    private final JsonObject data;
    private final HttpRequestResponse originalRequest;
    private final String sessionId;
    private Color highlightColor;
    private String notes;

    public MCPNodeData(NodeType type, String label, JsonObject data, HttpRequestResponse originalRequest, String sessionId) {
        this.type = type;
        this.label = label;
        this.data = data;
        this.originalRequest = originalRequest;
        this.sessionId = sessionId;
    }

    public NodeType getType() { return type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public JsonObject getData() { return data; }
    public HttpRequestResponse getOriginalRequest() { return originalRequest; }
    public String getSessionId() { return sessionId; }
    public Color getHighlightColor() { return highlightColor; }
    public void setHighlightColor(Color highlightColor) { this.highlightColor = highlightColor; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    @Override
    public String toString() {
        return label;
    }

    public String buildDetailHtml() {
        if (data == null) return "<html><body><i>No details available</i></body></html>";

        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family: monospace; padding: 8px;'>");

        switch (type) {
            case TOOL:
                buildToolDetail(sb);
                break;
            case RESOURCE:
                buildResourceDetail(sb);
                break;
            case RESOURCE_TEMPLATE:
                buildResourceTemplateDetail(sb);
                break;
            case PROMPT:
                buildPromptDetail(sb);
                break;
            case SERVER:
                buildServerDetail(sb);
                break;
            default:
                sb.append("<i>Select a tool, resource, or prompt to see details.</i>");
        }

        // Show notes section for leaf nodes
        if (type == NodeType.TOOL || type == NodeType.RESOURCE
                || type == NodeType.RESOURCE_TEMPLATE || type == NodeType.PROMPT) {
            sb.append("<hr><h3>Notes</h3>");
            if (notes != null && !notes.isEmpty()) {
                sb.append("<pre>").append(esc(notes)).append("</pre>");
            } else {
                sb.append("<i>No notes. Right-click to add.</i>");
            }
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private void buildToolDetail(StringBuilder sb) {
        String name = getStr("name");
        String description = getStr("description");

        sb.append("<h2>Tool: ").append(esc(name)).append("</h2>");
        if (!description.isEmpty()) {
            sb.append("<p>").append(esc(description)).append("</p>");
        }

        if (data.has("inputSchema")) {
            JsonObject schema = data.getAsJsonObject("inputSchema");
            sb.append("<h3>Input Schema</h3>");
            sb.append("<pre>").append(esc(PRETTY_GSON.toJson(schema))).append("</pre>");

            if (schema.has("properties")) {
                sb.append("<h3>Parameters</h3>");
                sb.append("<table border='1' cellpadding='4' cellspacing='0'>");
                sb.append("<tr><th>Name</th><th>Type</th><th>Description</th><th>Required</th></tr>");

                JsonObject props = schema.getAsJsonObject("properties");
                JsonArray required = schema.has("required") ? schema.getAsJsonArray("required") : new JsonArray();

                for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
                    String pName = entry.getKey();
                    JsonObject pDef = entry.getValue().getAsJsonObject();
                    String pType = pDef.has("type") ? pDef.get("type").getAsString() : "any";
                    String pDesc = pDef.has("description") ? pDef.get("description").getAsString() : "";
                    boolean isRequired = jsonArrayContains(required, pName);

                    sb.append("<tr>");
                    sb.append("<td><b>").append(esc(pName)).append("</b></td>");
                    sb.append("<td>").append(esc(pType)).append("</td>");
                    sb.append("<td>").append(esc(pDesc)).append("</td>");
                    sb.append("<td>").append(isRequired ? "yes" : "no").append("</td>");
                    sb.append("</tr>");
                }
                sb.append("</table>");
            }
        }
    }

    private void buildResourceDetail(StringBuilder sb) {
        String name = getStr("name");
        String description = getStr("description");
        String uri = getStr("uri");
        String mimeType = getStr("mimeType");

        sb.append("<h2>Resource: ").append(esc(name)).append("</h2>");
        if (!uri.isEmpty()) {
            sb.append("<p><b>URI:</b> ").append(esc(uri)).append("</p>");
        }
        if (!mimeType.isEmpty()) {
            sb.append("<p><b>MIME Type:</b> ").append(esc(mimeType)).append("</p>");
        }
        if (!description.isEmpty()) {
            sb.append("<p>").append(esc(description)).append("</p>");
        }
        sb.append("<h3>Raw</h3>");
        sb.append("<pre>").append(esc(PRETTY_GSON.toJson(data))).append("</pre>");
    }

    private void buildResourceTemplateDetail(StringBuilder sb) {
        String name = getStr("name");
        String description = getStr("description");
        String uriTemplate = getStr("uriTemplate");
        String mimeType = getStr("mimeType");

        sb.append("<h2>Resource Template: ").append(esc(name)).append("</h2>");
        if (!uriTemplate.isEmpty()) {
            sb.append("<p><b>URI Template:</b> <code>").append(esc(uriTemplate)).append("</code></p>");
        }
        if (!mimeType.isEmpty()) {
            sb.append("<p><b>MIME Type:</b> ").append(esc(mimeType)).append("</p>");
        }
        if (!description.isEmpty()) {
            sb.append("<p>").append(esc(description)).append("</p>");
        }
        sb.append("<h3>Raw</h3>");
        sb.append("<pre>").append(esc(PRETTY_GSON.toJson(data))).append("</pre>");
    }

    private void buildPromptDetail(StringBuilder sb) {
        String name = getStr("name");
        String description = getStr("description");

        sb.append("<h2>Prompt: ").append(esc(name)).append("</h2>");
        if (!description.isEmpty()) {
            sb.append("<p>").append(esc(description)).append("</p>");
        }

        if (data.has("arguments")) {
            JsonArray args = data.getAsJsonArray("arguments");
            sb.append("<h3>Arguments</h3>");
            sb.append("<table border='1' cellpadding='4' cellspacing='0'>");
            sb.append("<tr><th>Name</th><th>Description</th><th>Required</th></tr>");

            for (JsonElement el : args) {
                JsonObject arg = el.getAsJsonObject();
                String aName = arg.has("name") ? arg.get("name").getAsString() : "?";
                String aDesc = arg.has("description") ? arg.get("description").getAsString() : "";
                boolean aReq = arg.has("required") && arg.get("required").getAsBoolean();

                sb.append("<tr>");
                sb.append("<td><b>").append(esc(aName)).append("</b></td>");
                sb.append("<td>").append(esc(aDesc)).append("</td>");
                sb.append("<td>").append(aReq ? "yes" : "no").append("</td>");
                sb.append("</tr>");
            }
            sb.append("</table>");
        }

        sb.append("<h3>Raw</h3>");
        sb.append("<pre>").append(esc(PRETTY_GSON.toJson(data))).append("</pre>");
    }

    private void buildServerDetail(StringBuilder sb) {
        sb.append("<h2>Server</h2>");
        if (originalRequest != null) {
            sb.append("<h3><b>URL:</b> ").append(esc(originalRequest.request().url())).append("</h3>");
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            sb.append("<p><b>Session ID:</b> <code>").append(esc(sessionId)).append("</code></p>");
        } else {
            sb.append("<p><b>Session ID:</b> <i>none</i></p>");
        }

        if (data != null) {
            // Protocol version
            if (data.has("protocolVersion")) {
                sb.append("<p><b>Protocol Version:</b> ").append(esc(data.get("protocolVersion").getAsString())).append("</p>");
            }

            // Server info
            if (data.has("serverInfo") && data.get("serverInfo").isJsonObject()) {
                JsonObject si = data.getAsJsonObject("serverInfo");
                String name = si.has("name") ? si.get("name").getAsString() : "unknown";
                String version = si.has("version") ? si.get("version").getAsString() : "unknown";
                sb.append("<p><b>Server:</b> ").append(esc(name)).append(" v").append(esc(version)).append("</p>");
            }

            // Capabilities
            if (data.has("capabilities") && data.get("capabilities").isJsonObject()) {
                JsonObject caps = data.getAsJsonObject("capabilities");
                sb.append("<h3>Capabilities</h3>");
                sb.append("<table border='1' cellpadding='4' cellspacing='0'>");
                sb.append("<tr><th>Capability</th><th>Supported</th><th>Details</th></tr>");

                String[] capNames = {"tools", "resources", "prompts", "completions", "logging"};
                for (String capName : capNames) {
                    sb.append("<tr><td><b>").append(esc(capName)).append("</b></td>");
                    if (caps.has(capName)) {
                        sb.append("<td>yes</td><td>");
                        JsonElement capEl = caps.get(capName);
                        if (capEl.isJsonObject()) {
                            JsonObject capObj = capEl.getAsJsonObject();
                            java.util.StringJoiner details = new java.util.StringJoiner(", ");
                            for (Map.Entry<String, JsonElement> e : capObj.entrySet()) {
                                details.add(e.getKey() + "=" + e.getValue());
                            }
                            sb.append(esc(details.toString()));
                        }
                        sb.append("</td>");
                    } else {
                        sb.append("<td>no</td><td></td>");
                    }
                    sb.append("</tr>");
                }
                sb.append("</table>");
            }

            sb.append("<h3>Raw Server Info</h3>");
            sb.append("<pre>").append(esc(PRETTY_GSON.toJson(data))).append("</pre>");
        }
    }

    /**
     * Build a JSON-RPC request body for sending this item to Repeater.
     */
    public String buildRepeaterBody() {
        JsonObject rpc = new JsonObject();
        rpc.addProperty("jsonrpc", "2.0");
        rpc.addProperty("id", 1);

        switch (type) {
            case TOOL: {
                rpc.addProperty("method", "tools/call");
                JsonObject params = new JsonObject();
                params.addProperty("name", getStr("name"));
                // Build template arguments from inputSchema
                if (data.has("inputSchema")) {
                    JsonObject schema = data.getAsJsonObject("inputSchema");
                    params.add("arguments", buildTemplateArgs(schema));
                } else {
                    params.add("arguments", new JsonObject());
                }
                rpc.add("params", params);
                break;
            }
            case RESOURCE: {
                rpc.addProperty("method", "resources/read");
                JsonObject params = new JsonObject();
                params.addProperty("uri", getStr("uri"));
                rpc.add("params", params);
                break;
            }
            case RESOURCE_TEMPLATE: {
                rpc.addProperty("method", "resources/read");
                JsonObject params = new JsonObject();
                // URI template has placeholders like {id} — keep as-is for user to fill in
                params.addProperty("uri", getStr("uriTemplate"));
                rpc.add("params", params);
                break;
            }
            case PROMPT: {
                rpc.addProperty("method", "prompts/get");
                JsonObject params = new JsonObject();
                params.addProperty("name", getStr("name"));
                // Build template arguments
                JsonObject args = new JsonObject();
                if (data.has("arguments")) {
                    for (JsonElement el : data.getAsJsonArray("arguments")) {
                        JsonObject arg = el.getAsJsonObject();
                        String aName = arg.has("name") ? arg.get("name").getAsString() : "arg";
                        args.addProperty(aName, "TODO");
                    }
                }
                params.add("arguments", args);
                rpc.add("params", params);
                break;
            }
            default:
                return null;
        }

        return PRETTY_GSON.toJson(rpc);
    }

    /**
     * Build a JSON-RPC request body for completion/complete.
     * @param argumentName the argument name to complete
     * @param argumentValue partial value for autocomplete
     */
    public String buildCompleteRepeaterBody(String argumentName, String argumentValue) {
        JsonObject rpc = new JsonObject();
        rpc.addProperty("jsonrpc", "2.0");
        rpc.addProperty("id", 1);
        rpc.addProperty("method", "completion/complete");

        JsonObject params = new JsonObject();
        JsonObject ref = new JsonObject();

        switch (type) {
            case RESOURCE:
                ref.addProperty("type", "ref/resource");
                ref.addProperty("uri", getStr("uri"));
                break;
            case RESOURCE_TEMPLATE:
                ref.addProperty("type", "ref/resource");
                ref.addProperty("uri", getStr("uriTemplate"));
                break;
            case PROMPT:
                ref.addProperty("type", "ref/prompt");
                ref.addProperty("name", getStr("name"));
                break;
            default:
                return null;
        }
        params.add("ref", ref);

        JsonObject argument = new JsonObject();
        argument.addProperty("name", argumentName);
        argument.addProperty("value", argumentValue);
        params.add("argument", argument);

        rpc.add("params", params);
        return PRETTY_GSON.toJson(rpc);
    }

    private JsonObject buildTemplateArgs(JsonObject schema) {
        JsonObject args = new JsonObject();
        if (schema.has("properties")) {
            for (Map.Entry<String, JsonElement> entry : schema.getAsJsonObject("properties").entrySet()) {
                String pType = "string";
                if (entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("type")) {
                    pType = entry.getValue().getAsJsonObject().get("type").getAsString();
                }
                switch (pType) {
                    case "integer":
                    case "number":
                        args.addProperty(entry.getKey(), 0);
                        break;
                    case "boolean":
                        args.addProperty(entry.getKey(), false);
                        break;
                    case "array":
                        args.add(entry.getKey(), new JsonArray());
                        break;
                    case "object":
                        args.add(entry.getKey(), new JsonObject());
                        break;
                    default:
                        args.addProperty(entry.getKey(), "TODO");
                }
            }
        }
        return args;
    }

    /**
     * Returns a Markdown representation of this node.
     */
    public String buildMarkdown() {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        switch (type) {
            case TOOL:
                sb.append("### ").append(getStr("name")).append("\n\n");
                String desc = getStr("description");
                if (!desc.isEmpty()) sb.append(desc).append("\n\n");
                if (data.has("inputSchema")) {
                    sb.append("**Input Schema:**\n```json\n");
                    sb.append(PRETTY_GSON.toJson(data.getAsJsonObject("inputSchema")));
                    sb.append("\n```\n\n");
                }
                break;
            case RESOURCE:
                sb.append("### ").append(getStr("name")).append("\n\n");
                sb.append("- **URI:** `").append(getStr("uri")).append("`\n");
                String mimeType = getStr("mimeType");
                if (!mimeType.isEmpty()) sb.append("- **MIME Type:** ").append(mimeType).append("\n");
                String rDesc = getStr("description");
                if (!rDesc.isEmpty()) sb.append("\n").append(rDesc).append("\n");
                sb.append("\n");
                break;
            case RESOURCE_TEMPLATE:
                sb.append("### ").append(getStr("name")).append("\n\n");
                sb.append("- **URI Template:** `").append(getStr("uriTemplate")).append("`\n");
                String tMime = getStr("mimeType");
                if (!tMime.isEmpty()) sb.append("- **MIME Type:** ").append(tMime).append("\n");
                String tDesc = getStr("description");
                if (!tDesc.isEmpty()) sb.append("\n").append(tDesc).append("\n");
                sb.append("\n");
                break;
            case PROMPT:
                sb.append("### ").append(getStr("name")).append("\n\n");
                String pDesc = getStr("description");
                if (!pDesc.isEmpty()) sb.append(pDesc).append("\n\n");
                if (data.has("arguments")) {
                    sb.append("**Arguments:**\n\n");
                    for (JsonElement el : data.getAsJsonArray("arguments")) {
                        JsonObject arg = el.getAsJsonObject();
                        String aName = arg.has("name") ? arg.get("name").getAsString() : "?";
                        String aDesc = arg.has("description") ? arg.get("description").getAsString() : "";
                        boolean aReq = arg.has("required") && arg.get("required").getAsBoolean();
                        sb.append("- `").append(aName).append("`");
                        if (aReq) sb.append(" *(required)*");
                        if (!aDesc.isEmpty()) sb.append(" — ").append(aDesc);
                        sb.append("\n");
                    }
                    sb.append("\n");
                }
                break;
            case SERVER:
                // Handled by the tree walker
                break;
        }
        if (notes != null && !notes.isEmpty()) {
            sb.append("**Notes:**\n\n").append(notes).append("\n\n");
        }
        return sb.toString();
    }

    private String getStr(String key) {
        return data != null && data.has(key) ? data.get(key).getAsString() : "";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean jsonArrayContains(JsonArray arr, String value) {
        for (JsonElement el : arr) {
            if (el.getAsString().equals(value)) return true;
        }
        return false;
    }
}
