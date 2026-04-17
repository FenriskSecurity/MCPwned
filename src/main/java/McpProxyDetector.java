import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;

/**
 * Passively detects MCP (Model Context Protocol) traffic in the proxy
 * and highlights matching rows in gray.
 */
public class McpProxyDetector implements ProxyResponseHandler {

    @Override
    public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
        if (looksLikeMcp(response)) {
            Annotations annotations = response.annotations()
                    .withHighlightColor(HighlightColor.GRAY)
                    .withNotes("MCP endpoint detected by MCPwned");
            return ProxyResponseReceivedAction.continueWith(response, annotations);
        }
        return ProxyResponseReceivedAction.continueWith(response);
    }

    @Override
    public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
        return ProxyResponseToBeSentAction.continueWith(response);
    }

    private boolean looksLikeMcp(InterceptedResponse response) {
        // Fast header checks first
        boolean hasMcpSessionHeader = response.hasHeader("Mcp-Session-Id")
                || response.request().hasHeader("Mcp-Session-Id");
        if (hasMcpSessionHeader) return true;

        // Check request: POST with JSON body containing jsonrpc
        String reqContentType = response.request().headerValue("Content-Type");
        if (reqContentType == null || !reqContentType.contains("application/json")) return false;

        String reqBody = response.request().bodyToString();
        if (reqBody == null || !reqBody.contains("\"jsonrpc\"")) return false;

        // Check response: SSE or JSON with jsonrpc
        String resContentType = response.headerValue("Content-Type");
        if (resContentType == null) return false;

        if (resContentType.contains("text/event-stream")) return true;

        if (resContentType.contains("application/json")) {
            String resBody = response.bodyToString();
            return resBody != null && resBody.contains("\"jsonrpc\"");
        }

        return false;
    }
}
