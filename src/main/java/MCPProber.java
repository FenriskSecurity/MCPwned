import burp.api.montoya.http.message.HttpRequestResponse;
import com.google.gson.*;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSession;
import utils.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.FutureTask;

public class MCPProber {

    private final Gson gson;

    public MCPProber() {
        this.gson = new Gson();
    }

    public MCPProbeResult probe(HttpRequestResponse originalRequest) {
        String url = originalRequest.request().url();
        MCPProbeResult result = new MCPProbeResult(url);

        Logger.info("Probing MCP endpoint: " + url);

        McpSyncClient client = null;
        // Burp's extension classloader doesn't set the thread context classloader
        // to the extension's classloader. The MCP SDK uses ServiceLoader internally
        // (for McpJsonMapperSupplier, JsonSchemaValidatorSupplier, etc.) which relies
        // on the thread context classloader to find META-INF/services entries.
        // Fix: set it for the duration of all MCP SDK calls.
        ClassLoader extensionClassLoader = HttpClientStreamableHttpTransport.class.getClassLoader();
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(extensionClassLoader);
        try {
            Logger.debug("Creating Streamable HTTP transport for: " + url);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new SecureRandom());

            // HttpClientStreamableHttpTransport.builder() calls HttpClient.newBuilder()
            // in its constructor, which deadlocks inside Burp Suite's classloader.
            // Build the entire transport on a separate thread to break the lock chain.
            Logger.debug("Building transport on separate thread to avoid classloader deadlock...");
            // The SDK does baseUri.resolve(endpoint) where the default endpoint
            // is "/mcp". URI.resolve() with an absolute path replaces the entire
            // path, so we must split the URL into origin + path to preserve it.
            java.net.URI parsedUri = java.net.URI.create(url);
            FutureTask<McpClientTransport> transportTask = getMcpClientTransportFutureTask(parsedUri, sslContext);
            Thread transportThread = new Thread(transportTask, "MCPwned-Transport-Init");
            transportThread.setContextClassLoader(extensionClassLoader);
            transportThread.setDaemon(true);
            transportThread.start();
            McpClientTransport transport = transportTask.get(15, java.util.concurrent.TimeUnit.SECONDS);
            Logger.debug("Transport build() returned");

            Logger.debug("Building MCP sync client...");
            client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(15))
                    .capabilities(McpSchema.ClientCapabilities.builder().build())
                    .build();
            Logger.debug("Sync client built, calling initialize...");

            McpSchema.InitializeResult initResult = client.initialize();
            Logger.debug("initialize() returned");

            if (initResult == null) {
                Logger.info("initialize() returned null");
                result.setError("Failed to initialize MCP connection: null result");
                return result;
            }

            Logger.debug("Server info: " + initResult.serverInfo());
            Logger.debug("Protocol version: " + initResult.protocolVersion());
            Logger.debug("Capabilities: " + initResult.capabilities());

            Logger.info("Initialized with server: "
                    + initResult.serverInfo().name() + " v" + initResult.serverInfo().version());

            // Extract Mcp-Session-Id from the transport via reflection
            try {
                McpTransportSession<?> session = getMcpTransportSession(transport);
                if (session != null) {
                    session.sessionId().ifPresent(id -> {
                        result.setSessionId(id);
                        Logger.info("Mcp-Session-Id: " + id);
                    });
                }
            } catch (Exception e) {
                Logger.debug("Could not extract session ID: " + e.getMessage());
            }

            // Store server info as JsonObject for the UI
            String initJson = gson.toJson(initResult);
            Logger.debug("InitResult JSON: " + initJson);
            JsonObject serverInfo = JsonParser.parseString(initJson).getAsJsonObject();
            result.setServerInfo(serverInfo);

            // List tools
            Logger.debug("Calling client.listTools()...");
            try {
                McpSchema.ListToolsResult tools = client.listTools();
                Logger.debug("listTools() returned");
                if (tools == null) {
                    Logger.debug("listTools() returned null");
                } else {
                    Logger.debug("listTools() returned " + tools.tools().size() + " tools");
                    JsonArray toolsArray = new JsonArray();
                    for (McpSchema.Tool tool : tools.tools()) {
                        String toolJson = gson.toJson(tool);
                        Logger.debug("  Tool: " + tool.name() + " -> " + toolJson);
                        toolsArray.add(JsonParser.parseString(toolJson));
                    }
                    result.setTools(toolsArray);
                    Logger.info("Found " + toolsArray.size() + " tools");
                }
            } catch (Exception e) {
                Logger.info("tools/list failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Logger.debug("tools/list stack trace: " + stackTraceToString(e));
            }

            // List resources
            Logger.debug("Calling client.listResources()...");
            try {
                McpSchema.ListResourcesResult resources = client.listResources();
                Logger.debug("listResources() returned");
                if (resources == null) {
                    Logger.debug("listResources() returned null");
                } else {
                    Logger.debug("listResources() returned " + resources.resources().size() + " resources");
                    JsonArray resourcesArray = new JsonArray();
                    for (McpSchema.Resource resource : resources.resources()) {
                        String resJson = gson.toJson(resource);
                        Logger.debug("  Resource: " + resource.name() + " -> " + resJson);
                        resourcesArray.add(JsonParser.parseString(resJson));
                    }
                    result.setResources(resourcesArray);
                    Logger.info("Found " + resourcesArray.size() + " resources");
                }
            } catch (Exception e) {
                Logger.info("resources/list failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Logger.debug("resources/list stack trace: " + stackTraceToString(e));
            }

            // List resource templates
            Logger.debug("Calling client.listResourceTemplates()...");
            try {
                McpSchema.ListResourceTemplatesResult templates = client.listResourceTemplates();
                Logger.debug("listResourceTemplates() returned");
                if (templates == null) {
                    Logger.debug("listResourceTemplates() returned null");
                } else {
                    Logger.debug("listResourceTemplates() returned " + templates.resourceTemplates().size() + " templates");
                    JsonArray templatesArray = new JsonArray();
                    for (McpSchema.ResourceTemplate template : templates.resourceTemplates()) {
                        String templateJson = gson.toJson(template);
                        Logger.debug("  Template: " + template.name() + " -> " + templateJson);
                        templatesArray.add(JsonParser.parseString(templateJson));
                    }
                    result.setResourceTemplates(templatesArray);
                    Logger.info("Found " + templatesArray.size() + " resource templates");
                }
            } catch (Exception e) {
                Logger.info("resources/templates/list failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Logger.debug("resources/templates/list stack trace: " + stackTraceToString(e));
            }

            // List prompts
            Logger.debug("Calling client.listPrompts()...");
            try {
                McpSchema.ListPromptsResult prompts = client.listPrompts();
                Logger.debug("listPrompts() returned");
                if (prompts == null) {
                    Logger.debug("listPrompts() returned null");
                } else {
                    Logger.debug("listPrompts() returned " + prompts.prompts().size() + " prompts");
                    JsonArray promptsArray = new JsonArray();
                    for (McpSchema.Prompt prompt : prompts.prompts()) {
                        String promptJson = gson.toJson(prompt);
                        Logger.debug("  Prompt: " + prompt.name() + " -> " + promptJson);
                        promptsArray.add(JsonParser.parseString(promptJson));
                    }
                    result.setPrompts(promptsArray);
                    Logger.info("Found " + promptsArray.size() + " prompts");
                }
            } catch (Exception e) {
                Logger.info("prompts/list failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Logger.debug("prompts/list stack trace: " + stackTraceToString(e));
            }

            Logger.debug("Probe completed successfully");

        } catch (Exception e) {
            result.setError("Probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Logger.info("Probe error: " + e.getClass().getName() + ": " + e.getMessage());
            Logger.debug("Probe stack trace: " + stackTraceToString(e));
        } finally {
            // Do NOT call client.close() — the SDK sends an HTTP DELETE to the
            // endpoint which terminates the server-side session.  We want the
            // session ID to remain valid for manual queries in Repeater.
            // The HttpClient uses daemon threads and there is no persistent SSE
            // connection, so the objects are safe to abandon for GC.
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        Logger.debug("Returning result. hasError=" + result.hasError()
                + " tools=" + result.getTools().size()
                + " resources=" + result.getResources().size()
                + " prompts=" + result.getPrompts().size());

        return result;
    }

    private static McpTransportSession<?> getMcpTransportSession(McpClientTransport transport) throws NoSuchFieldException, IllegalAccessException {
        java.lang.reflect.Field sessionField = transport.getClass().getDeclaredField("activeSession");
        sessionField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<McpTransportSession<?>> sessionRef =
                (java.util.concurrent.atomic.AtomicReference<McpTransportSession<?>>) sessionField.get(transport);
        return sessionRef.get();
    }

    private static FutureTask<McpClientTransport> getMcpClientTransportFutureTask(URI parsedUri, SSLContext sslContext) {
        String baseUrl = parsedUri.getScheme() + "://" + parsedUri.getAuthority();
        String endpointPath = parsedUri.getPath();
        if (endpointPath == null || endpointPath.isEmpty()) {
            endpointPath = "/mcp";
        }
        String finalEndpointPath = endpointPath;

        return new FutureTask<>(() ->
                HttpClientStreamableHttpTransport.builder(baseUrl)
                        .clientBuilder(HttpClient.newBuilder().sslContext(sslContext))
                        .endpoint(finalEndpointPath)
                        .openConnectionOnStartup(false)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
    }

    private static String stackTraceToString(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
        for (StackTraceElement ste : e.getStackTrace()) {
            sb.append("  at ").append(ste.toString()).append("\n");
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            sb.append("Caused by: ").append(cause.getClass().getName()).append(": ").append(cause.getMessage()).append("\n");
            for (StackTraceElement ste : cause.getStackTrace()) {
                sb.append("  at ").append(ste.toString()).append("\n");
            }
            cause = cause.getCause();
        }
        return sb.toString();
    }

    public static class MCPProbeResult {
        private final String url;
        private JsonObject serverInfo;
        private JsonArray tools;
        private JsonArray resources;
        private JsonArray resourceTemplates;
        private JsonArray prompts;
        private String sessionId;
        private String error;

        public MCPProbeResult(String url) {
            this.url = url;
            this.tools = new JsonArray();
            this.resources = new JsonArray();
            this.resourceTemplates = new JsonArray();
            this.prompts = new JsonArray();
        }

        public String getUrl() { return url; }

        public JsonObject getServerInfo() { return serverInfo; }
        public void setServerInfo(JsonObject serverInfo) { this.serverInfo = serverInfo; }

        public JsonArray getTools() { return tools; }
        public void setTools(JsonArray tools) { this.tools = tools; }

        public JsonArray getResources() { return resources; }
        public void setResources(JsonArray resources) { this.resources = resources; }

        public JsonArray getResourceTemplates() { return resourceTemplates; }
        public void setResourceTemplates(JsonArray resourceTemplates) { this.resourceTemplates = resourceTemplates; }

        public JsonArray getPrompts() { return prompts; }
        public void setPrompts(JsonArray prompts) { this.prompts = prompts; }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public boolean hasError() { return error != null; }

        public String toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("url", url);
            if (serverInfo != null) obj.add("serverInfo", serverInfo);
            obj.add("tools", tools);
            obj.add("resources", resources);
            obj.add("resourceTemplates", resourceTemplates);
            obj.add("prompts", prompts);
            if (sessionId != null) obj.addProperty("sessionId", sessionId);
            if (error != null) obj.addProperty("error", error);
            return obj.toString();
        }

        public static MCPProbeResult fromJson(String json) {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            MCPProbeResult result = new MCPProbeResult(obj.get("url").getAsString());
            if (obj.has("serverInfo")) result.setServerInfo(obj.getAsJsonObject("serverInfo"));
            if (obj.has("tools")) result.setTools(obj.getAsJsonArray("tools"));
            if (obj.has("resources")) result.setResources(obj.getAsJsonArray("resources"));
            if (obj.has("resourceTemplates")) result.setResourceTemplates(obj.getAsJsonArray("resourceTemplates"));
            if (obj.has("prompts")) result.setPrompts(obj.getAsJsonArray("prompts"));
            if (obj.has("sessionId")) result.setSessionId(obj.get("sessionId").getAsString());
            if (obj.has("error")) result.setError(obj.get("error").getAsString());
            return result;
        }

    }
}
