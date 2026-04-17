import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.persistence.PersistedObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import utils.Logger;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class MCPwnedTab {

    private final MontoyaApi api;
    private final MCPProber prober;
    private final JPanel panel;
    private final DefaultMutableTreeNode rootNode;
    private final DefaultTreeModel treeModel;
    private final JTree tree;
    private final JEditorPane detailPane;
    private JButton refreshButton;
    private final Map<String, DefaultMutableTreeNode> serverNodesByUrl = new HashMap<>();

    private static final String PERSIST_KEY = "mcpwned_servers";

    private static final LinkedHashMap<String, Color> HIGHLIGHT_COLORS = new LinkedHashMap<>();
    static {
        HIGHLIGHT_COLORS.put("None", null);
        HIGHLIGHT_COLORS.put("Red", new Color(0xFF, 0x64, 0x64));
        HIGHLIGHT_COLORS.put("Orange", new Color(0xFF, 0xA5, 0x00));
        HIGHLIGHT_COLORS.put("Yellow", new Color(0xFF, 0xE0, 0x50));
        HIGHLIGHT_COLORS.put("Green", new Color(0x77, 0xDD, 0x77));
        HIGHLIGHT_COLORS.put("Cyan", new Color(0x77, 0xDD, 0xEE));
        HIGHLIGHT_COLORS.put("Blue", new Color(0x77, 0xBB, 0xFF));
        HIGHLIGHT_COLORS.put("Pink", new Color(0xFF, 0x99, 0xCC));
        HIGHLIGHT_COLORS.put("Magenta", new Color(0xDD, 0x77, 0xFF));
        HIGHLIGHT_COLORS.put("Gray", new Color(0xBB, 0xBB, 0xBB));
    }

    public MCPwnedTab(MontoyaApi api) {
        this.api = api;
        this.prober = new MCPProber();
        this.rootNode = new DefaultMutableTreeNode("MCP Servers");
        this.treeModel = new DefaultTreeModel(rootNode);
        this.tree = new JTree(treeModel);
        this.detailPane = new JEditorPane();
        this.panel = buildPanel();
        loadFromProject();
    }

    private JPanel buildPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());

        // Tree on the left
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        Icon folderOpen = UIManager.getIcon("Tree.openIcon");
        Icon folderClosed = UIManager.getIcon("Tree.closedIcon");
        Icon toolIcon = new WrenchIcon();
        Icon resourceIcon = new BookIcon();
        Icon promptIcon = new RobotIcon();
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            private final Color defaultBgNonSel = getBackgroundNonSelectionColor();
            private final Color defaultBgSel = getBackgroundSelectionColor();
            private final Color defaultFgNonSel = getTextNonSelectionColor();
            private final Color defaultFgSel = getTextSelectionColor();
            private Color fullRowBg; // set per-render, painted in paint()

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
                // Reset BEFORE super so it uses the correct defaults for this row
                setBackgroundNonSelectionColor(defaultBgNonSel);
                setBackgroundSelectionColor(defaultBgSel);
                setTextNonSelectionColor(defaultFgNonSel);
                setTextSelectionColor(defaultFgSel);
                fullRowBg = null;

                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

                boolean isLeafNode = false;
                if (value instanceof DefaultMutableTreeNode) {
                    Object userObj = ((DefaultMutableTreeNode) value).getUserObject();
                    if (userObj instanceof MCPNodeData nd) {
                        switch (nd.getType()) {
                            case TOOL: setIcon(toolIcon); isLeafNode = true; break;
                            case RESOURCE, RESOURCE_TEMPLATE: setIcon(resourceIcon); isLeafNode = true; break;
                            case PROMPT: setIcon(promptIcon); isLeafNode = true; break;
                            default:
                                if (leaf) setIcon(null);
                                else if (expanded) setIcon(folderOpen);
                                else setIcon(folderClosed);
                        }
                        Color hl = nd.getHighlightColor();
                        if (hl != null) {
                            Color bg = sel ? hl.darker() : hl;
                            fullRowBg = bg;
                            setBackgroundNonSelectionColor(bg);
                            setBackgroundSelectionColor(hl.darker());
                            double lum = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
                            Color fg = lum > 128 ? Color.BLACK : Color.WHITE;
                            setForeground(fg);
                            setTextNonSelectionColor(fg);
                            double lumDark = 0.299 * hl.darker().getRed() + 0.587 * hl.darker().getGreen() + 0.114 * hl.darker().getBlue();
                            Color fgSel = lumDark > 128 ? Color.BLACK : Color.WHITE;
                            setTextSelectionColor(fgSel);
                        } else if (isLeafNode && sel) {
                            fullRowBg = defaultBgSel;
                        }
                    } else {
                        if (leaf) setIcon(null);
                        else if (expanded) setIcon(folderOpen);
                        else setIcon(folderClosed);
                    }
                }
                return this;
            }

            @Override
            public void paint(Graphics g) {
                if (fullRowBg != null) {
                    g.setColor(fullRowBg);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                super.paint(g);
            }
        });
        tree.addTreeSelectionListener(e -> onNodeSelected());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }
        });
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(350, 0));

        // Detail pane on the right with a toolbar
        detailPane.setContentType("text/html");
        detailPane.setEditable(false);
        detailPane.setText("<html><body style='font-family: monospace; padding: 8px;'>"
                + "<i>Select a tool, resource, or prompt to see details.</i></body></html>");
        JScrollPane detailScroll = new JScrollPane(detailPane);

        refreshButton = new JButton("Refresh Session ID");
        refreshButton.setVisible(false);
        refreshButton.addActionListener(ev -> refreshSelectedServer());

        JPanel detailPanel = new JPanel(new BorderLayout());
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolBar.add(refreshButton);

        JButton exportButton = new JButton("Export to Markdown");
        exportButton.addActionListener(ev -> exportToMarkdown());
        toolBar.add(exportButton);
        detailPanel.add(toolBar, BorderLayout.NORTH);
        detailPanel.add(detailScroll, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, detailPanel);
        splitPane.setDividerLocation(500);
        splitPane.setResizeWeight(0.3);

        mainPanel.add(splitPane, BorderLayout.CENTER);
        return mainPanel;
    }

    public Component getUiComponent() {
        return panel;
    }

    private void refreshSelectedServer() {
        DefaultMutableTreeNode selected = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (selected == null) return;

        Object userObj = selected.getUserObject();
        if (!(userObj instanceof MCPNodeData nodeData)) return;
        if (nodeData.getType() != MCPNodeData.NodeType.SERVER) return;

        HttpRequestResponse originalRequest = nodeData.getOriginalRequest();
        if (originalRequest == null) return;

        refreshServer(originalRequest.request().url());
    }

    /**
     * Refreshes a server by URL — re-probes the endpoint to get a fresh session ID
     * and updates the tree. Can be called from the context menu or the refresh button.
     */
    public void refreshServer(String url) {
        refreshServer(url, null);
    }

    /**
     * Refreshes a server by URL with an optional callback that receives the new session ID.
     * The callback runs on the EDT after the probe completes.
     */
    public void refreshServer(String url, java.util.function.Consumer<String> onSessionId) {
        DefaultMutableTreeNode serverNode = serverNodesByUrl.get(url);
        if (serverNode == null) {
            Logger.info("No server found for URL: " + url + ", scanning as new.");
            return;
        }

        Object userObj = serverNode.getUserObject();
        String customLabel = url;
        HttpRequestResponse originalRequest = null;
        if (userObj instanceof MCPNodeData nd) {
            customLabel = nd.getLabel();
            originalRequest = nd.getOriginalRequest();
        }
        if (originalRequest == null) return;

        String finalCustomLabel = customLabel;
        HttpRequestResponse finalOriginalRequest = originalRequest;

        SwingUtilities.invokeLater(() -> {
            serverNode.setUserObject(new MCPNodeData(
                    MCPNodeData.NodeType.SERVER, finalCustomLabel + " (refreshing...)", null, finalOriginalRequest, null));
            serverNode.removeAllChildren();
            treeModel.reload(serverNode);
            refreshButton.setEnabled(false);
        });

        new Thread(() -> {
            MCPProber.MCPProbeResult result = prober.probe(finalOriginalRequest);
            SwingUtilities.invokeLater(() -> {
                buildServerTree(serverNode, result, finalOriginalRequest);
                if (!result.hasError()) {
                    Object obj = serverNode.getUserObject();
                    if (obj instanceof MCPNodeData) {
                        ((MCPNodeData) obj).setLabel(finalCustomLabel);
                        treeModel.nodeChanged(serverNode);
                    }
                }
                tree.setSelectionPath(new TreePath(serverNode.getPath()));
                refreshButton.setEnabled(true);
                flashTab();

                // Notify callback with the new session ID
                if (onSessionId != null) {
                    onSessionId.accept(result.getSessionId());
                }
            });
        }).start();
    }

    /**
     * Finds the URL of a known server that matches the given request URL.
     * Matches if the request URL starts with a known server URL.
     */
    public String findServerUrlForRequest(HttpRequestResponse requestResponse) {
        if (requestResponse == null) return null;
        String reqUrl = requestResponse.request().url();
        // Exact match first
        if (serverNodesByUrl.containsKey(reqUrl)) return reqUrl;
        // Prefix match (request URL may have extra path from endpoint)
        for (String serverUrl : serverNodesByUrl.keySet()) {
            if (reqUrl.startsWith(serverUrl)) return serverUrl;
        }
        return null;
    }

    public void selectTab() {
        SwingUtilities.invokeLater(() -> {
            Container parent = panel.getParent();
            while (parent != null && !(parent instanceof JTabbedPane)) {
                parent = parent.getParent();
            }
            if (parent instanceof JTabbedPane tabbedPane) {
                int tabIndex = tabbedPane.indexOfComponent(panel);
                if (tabIndex >= 0) {
                    tabbedPane.setSelectedIndex(tabIndex);
                }
            }
        });
    }

    /**
     * Flashes the MCPwned suite tab to attract user attention, similar to
     * how Burp highlights the Repeater tab when a request is sent to it.
     * Walks up the Swing hierarchy to find the parent JTabbedPane and
     * temporarily changes the tab foreground color.
     */
    public void flashTab() {
        SwingUtilities.invokeLater(() -> {
            Container parent = panel.getParent();
            while (parent != null && !(parent instanceof JTabbedPane)) {
                parent = parent.getParent();
            }
            if (!(parent instanceof JTabbedPane tabbedPane)) return;

            int tabIndex = tabbedPane.indexOfComponent(panel);
            if (tabIndex < 0) return;

            Color originalColor = tabbedPane.getForegroundAt(tabIndex);
            Timer timer = getTimer(tabbedPane, tabIndex, originalColor);
            timer.start();
        });
    }

    private static Timer getTimer(JTabbedPane tabbedPane, int tabIndex, Color originalColor) {
        Color flashColor = new Color(0xFF, 0x6B, 0x00); // orange

        Timer timer = new Timer(300, null);
        int[] count = {0};
        timer.addActionListener(e -> {
            if (count[0] >= 8) {
                tabbedPane.setForegroundAt(tabIndex, originalColor);
                timer.stop();
            } else {
                tabbedPane.setForegroundAt(tabIndex,
                        count[0] % 2 == 0 ? flashColor : originalColor);
                count[0]++;
            }
        });
        return timer;
    }

    public void addRequest(HttpRequestResponse requestResponse) {
        String url = requestResponse.request().url();
        Logger.info("Scanning MCP endpoint: " + url);
        flashTab();

        // Reuse existing node for this URL, or create a new one
        DefaultMutableTreeNode serverNode;
        if (serverNodesByUrl.containsKey(url)) {
            serverNode = serverNodesByUrl.get(url);
            Logger.info("Re-scanning existing endpoint: " + url);
            SwingUtilities.invokeLater(() -> {
                serverNodesByUrl.get(url).setUserObject(url + " (re-scanning...)");
                serverNodesByUrl.get(url).removeAllChildren();
                treeModel.reload(serverNodesByUrl.get(url));
            });
        } else {
            serverNode = new DefaultMutableTreeNode(url + " (scanning...)");
            serverNodesByUrl.put(url, serverNode);
            SwingUtilities.invokeLater(() -> {
                rootNode.add(serverNode);
                treeModel.reload(rootNode);
                tree.expandPath(new TreePath(rootNode.getPath()));
            });
        }

        // Probe in background
        new Thread(() -> {
            MCPProber.MCPProbeResult result = prober.probe(requestResponse);
            SwingUtilities.invokeLater(() -> buildServerTree(serverNode, result, requestResponse));
        }).start();
    }

    private void buildServerTree(DefaultMutableTreeNode serverNode, MCPProber.MCPProbeResult result,
                                 HttpRequestResponse originalRequest) {
        serverNode.removeAllChildren();
        String sid = result.getSessionId();

        if (result.hasError()) {
            serverNode.setUserObject(new MCPNodeData(
                    MCPNodeData.NodeType.SERVER, result.getUrl() + " (error)", null, originalRequest, sid));
            serverNode.add(new DefaultMutableTreeNode(result.getError()));
            treeModel.reload(serverNode);
            return;
        }

        // Server node with server info
        String serverLabel = result.getUrl();
        if (result.getServerInfo() != null && result.getServerInfo().has("serverInfo")) {
            JsonObject si = result.getServerInfo().getAsJsonObject("serverInfo");
            String name = si.has("name") ? si.get("name").getAsString() : "";
            String version = si.has("version") ? si.get("version").getAsString() : "";
            if (!name.isEmpty()) {
                serverLabel = name + (version.isEmpty() ? "" : " v" + version) + " — " + result.getUrl();
            }
        }
        serverNode.setUserObject(new MCPNodeData(
                MCPNodeData.NodeType.SERVER, serverLabel, result.getServerInfo(), originalRequest, sid));

        // Tools
        DefaultMutableTreeNode toolsCategory = new DefaultMutableTreeNode(
                new MCPNodeData(MCPNodeData.NodeType.CATEGORY,
                        "Tools (" + result.getTools().size() + ")", null, originalRequest, sid));
        for (JsonElement el : result.getTools()) {
            JsonObject tool = el.getAsJsonObject();
            String name = tool.has("name") ? tool.get("name").getAsString() : "?";
            toolsCategory.add(new DefaultMutableTreeNode(
                    new MCPNodeData(MCPNodeData.NodeType.TOOL, name, tool, originalRequest, sid)));
        }
        serverNode.add(toolsCategory);

        // Resources
        DefaultMutableTreeNode resourcesCategory = new DefaultMutableTreeNode(
                new MCPNodeData(MCPNodeData.NodeType.CATEGORY,
                        "Resources (" + result.getResources().size() + ")", null, originalRequest, sid));
        for (JsonElement el : result.getResources()) {
            JsonObject res = el.getAsJsonObject();
            String name = res.has("name") ? res.get("name").getAsString() : "?";
            resourcesCategory.add(new DefaultMutableTreeNode(
                    new MCPNodeData(MCPNodeData.NodeType.RESOURCE, name, res, originalRequest, sid)));
        }
        serverNode.add(resourcesCategory);

        // Resource Templates
        if (!result.getResourceTemplates().isEmpty()) {
            DefaultMutableTreeNode templatesCategory = new DefaultMutableTreeNode(
                    new MCPNodeData(MCPNodeData.NodeType.CATEGORY,
                            "Resource Templates (" + result.getResourceTemplates().size() + ")", null, originalRequest, sid));
            for (JsonElement el : result.getResourceTemplates()) {
                JsonObject tpl = el.getAsJsonObject();
                String name = tpl.has("name") ? tpl.get("name").getAsString() : "?";
                templatesCategory.add(new DefaultMutableTreeNode(
                        new MCPNodeData(MCPNodeData.NodeType.RESOURCE_TEMPLATE, name, tpl, originalRequest, sid)));
            }
            serverNode.add(templatesCategory);
        }

        // Prompts
        DefaultMutableTreeNode promptsCategory = new DefaultMutableTreeNode(
                new MCPNodeData(MCPNodeData.NodeType.CATEGORY,
                        "Prompts (" + result.getPrompts().size() + ")", null, originalRequest, sid));
        for (JsonElement el : result.getPrompts()) {
            JsonObject prompt = el.getAsJsonObject();
            String name = prompt.has("name") ? prompt.get("name").getAsString() : "?";
            promptsCategory.add(new DefaultMutableTreeNode(
                    new MCPNodeData(MCPNodeData.NodeType.PROMPT, name, prompt, originalRequest, sid)));
        }
        serverNode.add(promptsCategory);

        treeModel.reload(serverNode);
        expandAll(new TreePath(serverNode.getPath()));

        // Persist to Burp project
        saveToProject(result.getUrl(), result, originalRequest);
    }

    private void expandAll(TreePath parent) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) parent.getLastPathComponent();
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            expandAll(parent.pathByAddingChild(child));
        }
        tree.expandPath(parent);
    }

    private void onNodeSelected() {
        DefaultMutableTreeNode selected = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (selected == null) {
            refreshButton.setVisible(false);
            return;
        }

        Object userObject = selected.getUserObject();
        if (userObject instanceof MCPNodeData nodeData) {
            detailPane.setText(nodeData.buildDetailHtml());
            detailPane.setCaretPosition(0);
            refreshButton.setVisible(nodeData.getType() == MCPNodeData.NodeType.SERVER);
        } else {
            detailPane.setText("<html><body style='font-family: monospace; padding: 8px;'>"
                    + "<i>Select a tool, resource, or prompt to see details.</i></body></html>");
            refreshButton.setVisible(false);
        }
    }

    private void showContextMenu(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;

        tree.setSelectionPath(path);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        if (!(userObject instanceof MCPNodeData nodeData)) return;

        JPopupMenu menu = new JPopupMenu();

        // "Send to Repeater" for leaf nodes (tool/resource/prompt)
        boolean isLeaf = nodeData.getType() == MCPNodeData.NodeType.TOOL
                || nodeData.getType() == MCPNodeData.NodeType.RESOURCE
                || nodeData.getType() == MCPNodeData.NodeType.RESOURCE_TEMPLATE
                || nodeData.getType() == MCPNodeData.NodeType.PROMPT;
        if (isLeaf) {
            JMenuItem sendToRepeater = new JMenuItem("Send to Repeater");
            sendToRepeater.addActionListener(ev -> sendNodeToRepeater(nodeData));
            menu.add(sendToRepeater);
        }

        // "Send completion/complete to Repeater" for resource templates and prompts
        if (nodeData.getType() == MCPNodeData.NodeType.RESOURCE_TEMPLATE
                || nodeData.getType() == MCPNodeData.NodeType.PROMPT) {
            JMenuItem completeItem = getCompleteItem(nodeData);
            menu.add(completeItem);
        }

        // "Highlight" submenu for leaf nodes
        if (isLeaf) {
            JMenu highlightMenu = getHighlightMenu(nodeData, node);
            menu.add(highlightMenu);

            // "Edit Notes" for leaf nodes
            JMenuItem notesItem = getNoteItem(nodeData, node);
            menu.add(notesItem);
        }

        // "Rename" for server nodes
        if (nodeData.getType() == MCPNodeData.NodeType.SERVER) {
            JMenuItem renameItem = new JMenuItem("Rename");
            renameItem.addActionListener(ev -> {
                String newName = JOptionPane.showInputDialog(panel, "Enter new server name:",
                        nodeData.getLabel());
                if (newName != null && !newName.trim().isEmpty()) {
                    nodeData.setLabel(newName.trim());
                    treeModel.nodeChanged(node);
                    saveCurrentStateForNode(node);
                }
            });
            menu.add(renameItem);
        }

        menu.addSeparator();

        // "Remove server" for any node — walk up to the server node
        DefaultMutableTreeNode serverNode = findServerNode(node);
        if (serverNode != null) {
            JMenuItem removeItem = getJMenuItem(serverNode);
            menu.add(removeItem);
        }

        menu.show(tree, e.getX(), e.getY());
    }

    private JMenuItem getNoteItem(MCPNodeData nodeData, DefaultMutableTreeNode node) {
        JMenuItem notesItem = new JMenuItem("Edit Notes");
        notesItem.addActionListener(ev -> {
            JTextArea notesArea = new JTextArea(10, 40);
            notesArea.setText(nodeData.getNotes() != null ? nodeData.getNotes() : "");
            notesArea.setLineWrap(true);
            notesArea.setWrapStyleWord(true);
            int result = JOptionPane.showConfirmDialog(panel,
                    new JScrollPane(notesArea), "Edit Notes — " + nodeData.getLabel(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result == JOptionPane.OK_OPTION) {
                nodeData.setNotes(notesArea.getText());
                onNodeSelected(); // refresh detail pane
                saveCurrentStateForNode(node);
            }
        });
        return notesItem;
    }

    private JMenu getHighlightMenu(MCPNodeData nodeData, DefaultMutableTreeNode node) {
        JMenu highlightMenu = new JMenu("Highlight");
        for (Map.Entry<String, Color> entry : HIGHLIGHT_COLORS.entrySet()) {
            JMenuItem colorItem = new JMenuItem(entry.getKey());
            if (entry.getValue() != null) {
                colorItem.setIcon(new ColorIcon(entry.getValue()));
            }
            colorItem.addActionListener(ev -> {
                nodeData.setHighlightColor(entry.getValue());
                tree.repaint();
                saveCurrentStateForNode(node);
            });
            highlightMenu.add(colorItem);
        }
        return highlightMenu;
    }

    private JMenuItem getCompleteItem(MCPNodeData nodeData) {
        JMenuItem completeItem = new JMenuItem("Send completion/complete to Repeater");
        completeItem.addActionListener(ev -> {
            String argName = JOptionPane.showInputDialog(panel,
                    "Argument name to complete:", "");
            if (argName == null || argName.trim().isEmpty()) return;
            String argValue = JOptionPane.showInputDialog(panel,
                    "Partial value (can be empty):", "");
            if (argValue == null) return;

            String body = nodeData.buildCompleteRepeaterBody(argName.trim(), argValue);
            if (body == null) return;

            HttpRequestResponse originalReq = nodeData.getOriginalRequest();
            HttpRequest req = originalReq.request()
                    .withMethod("POST")
                    .withBody(body)
                    .withRemovedHeader("Accept")
                    .withRemovedHeader("Content-Type")
                    .withRemovedHeader("Mcp-Session-Id")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "text/event-stream, application/json");
            if (nodeData.getSessionId() != null) {
                req = req.withHeader("Mcp-Session-Id", nodeData.getSessionId());
            }
            String tabName = uniqueRepeaterTabName("complete/" + nodeData.getLabel());
            api.repeater().sendToRepeater(req, tabName);
            Logger.info("Sent completion/complete for " + nodeData.getLabel() + " to Repeater");
            focusRepeaterSubTab(tabName);
        });
        return completeItem;
    }

    private JMenuItem getJMenuItem(DefaultMutableTreeNode serverNode) {
        Object serverObj = serverNode.getUserObject();
        String serverLabel = serverObj instanceof MCPNodeData
                ? ((MCPNodeData) serverObj).getLabel() : serverObj.toString();

        JMenuItem removeItem = new JMenuItem("Remove server");
        removeItem.addActionListener(ev -> {
            int confirm = JOptionPane.showConfirmDialog(panel,
                    "Are you sure you want to delete " + serverLabel + "?",
                    "Remove MCP Server",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                // Find and remove URL from map and persistence
                String removedUrl = null;
                for (Map.Entry<String, DefaultMutableTreeNode> entry : serverNodesByUrl.entrySet()) {
                    if (entry.getValue() == serverNode) {
                        removedUrl = entry.getKey();
                        break;
                    }
                }
                if (removedUrl != null) {
                    serverNodesByUrl.remove(removedUrl);
                    removeFromProject(removedUrl);
                }
                rootNode.remove(serverNode);
                treeModel.reload(rootNode);
                detailPane.setText("<html><body style='font-family: monospace; padding: 8px;'>"
                        + "<i>Select a tool, resource, or prompt to see details.</i></body></html>");
                Logger.info("Removed server " + serverLabel);
            }
        });
        return removeItem;
    }

    private DefaultMutableTreeNode findServerNode(DefaultMutableTreeNode node) {
        DefaultMutableTreeNode current = node;
        while (current != null && current != rootNode) {
            if (current.getParent() == rootNode) {
                return current;
            }
            current = (DefaultMutableTreeNode) current.getParent();
        }
        return null;
    }

    private void saveCurrentStateForNode(DefaultMutableTreeNode node) {
        DefaultMutableTreeNode serverNode = findServerNode(node);
        if (serverNode == null && node.getParent() == rootNode) {
            serverNode = node; // node is itself a server node
        }
        if (serverNode == null) return;
        for (Map.Entry<String, DefaultMutableTreeNode> e : serverNodesByUrl.entrySet()) {
            if (e.getValue() == serverNode) {
                saveCurrentState(e.getKey());
                return;
            }
        }
    }

    private void saveToProject(String url, MCPProber.MCPProbeResult result, HttpRequestResponse originalRequest) {
        try {
            PersistedObject data = api.persistence().extensionData();
            PersistedObject servers = data.getChildObject(PERSIST_KEY);
            if (servers == null) {
                servers = PersistedObject.persistedObject();
            }

            // Use a sanitized key since URLs contain characters not ideal for keys
            String key = "server_" + Math.abs(url.hashCode());
            PersistedObject entry = PersistedObject.persistedObject();
            entry.setString("url", url);
            entry.setString("probeResult", result.toJson());
            entry.setHttpRequestResponse("originalRequest", originalRequest);

            // Save custom label and highlight colors from the tree
            DefaultMutableTreeNode serverNode = serverNodesByUrl.get(url);
            if (serverNode != null) {
                saveNodeCustomizations(entry, serverNode);
            }

            servers.setChildObject(key, entry);
            data.setChildObject(PERSIST_KEY, servers);
            Logger.info("Saved server data for " + url);
        } catch (Exception e) {
            Logger.error("Failed to save server data: " + e.getMessage());
        }
    }

    /**
     * Persists the server after a UI-only change (rename, highlight) without re-probing.
     */
    private void saveCurrentState(String url) {
        DefaultMutableTreeNode serverNode = serverNodesByUrl.get(url);
        if (serverNode == null) return;
        try {
            PersistedObject data = api.persistence().extensionData();
            PersistedObject servers = data.getChildObject(PERSIST_KEY);
            if (servers == null) return;

            String key = "server_" + Math.abs(url.hashCode());
            PersistedObject entry = servers.getChildObject(key);
            if (entry == null) return;

            saveNodeCustomizations(entry, serverNode);
            servers.setChildObject(key, entry);
            data.setChildObject(PERSIST_KEY, servers);
        } catch (Exception e) {
            Logger.error("Failed to save customizations: " + e.getMessage());
        }
    }

    private void saveNodeCustomizations(PersistedObject entry, DefaultMutableTreeNode serverNode) {
        Object serverObj = serverNode.getUserObject();
        if (serverObj instanceof MCPNodeData) {
            entry.setString("customLabel", ((MCPNodeData) serverObj).getLabel());
        }
        // Save highlight colors and notes as JSON: "nodetype/name" -> value
        JsonObject colors = new JsonObject();
        JsonObject notes = new JsonObject();
        for (int i = 0; i < serverNode.getChildCount(); i++) {
            DefaultMutableTreeNode category = (DefaultMutableTreeNode) serverNode.getChildAt(i);
            for (int j = 0; j < category.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) category.getChildAt(j);
                if (leaf.getUserObject() instanceof MCPNodeData nd) {
                    String nodeKey = nd.getType().name() + "/" + nd.getLabel();
                    if (nd.getHighlightColor() != null) {
                        colors.addProperty(nodeKey, nd.getHighlightColor().getRGB());
                    }
                    if (nd.getNotes() != null && !nd.getNotes().isEmpty()) {
                        notes.addProperty(nodeKey, nd.getNotes());
                    }
                }
            }
        }
        entry.setString("highlightColors", colors.toString());
        entry.setString("notes", notes.toString());
    }

    private void removeFromProject(String url) {
        try {
            PersistedObject data = api.persistence().extensionData();
            PersistedObject servers = data.getChildObject(PERSIST_KEY);
            if (servers == null) return;

            String key = "server_" + Math.abs(url.hashCode());
            servers.deleteChildObject(key);
            data.setChildObject(PERSIST_KEY, servers);
            Logger.info("Removed saved data for " + url);
        } catch (Exception e) {
            Logger.error("Failed to remove server data: " + e.getMessage());
        }
    }

    private void loadFromProject() {
        try {
            PersistedObject data = api.persistence().extensionData();
            PersistedObject servers = data.getChildObject(PERSIST_KEY);
            if (servers == null) return;

            for (String key : servers.childObjectKeys()) {
                PersistedObject entry = servers.getChildObject(key);
                if (entry == null) continue;

                String url = entry.getString("url");
                String probeJson = entry.getString("probeResult");
                HttpRequestResponse originalRequest = entry.getHttpRequestResponse("originalRequest");
                if (url == null || probeJson == null || originalRequest == null) continue;

                MCPProber.MCPProbeResult result = MCPProber.MCPProbeResult.fromJson(probeJson);

                DefaultMutableTreeNode serverNode = new DefaultMutableTreeNode(url + " (loading...)");
                serverNodesByUrl.put(url, serverNode);
                rootNode.add(serverNode);
                buildServerTree(serverNode, result, originalRequest);

                // Restore custom label
                String customLabel = entry.getString("customLabel");
                if (customLabel != null && serverNode.getUserObject() instanceof MCPNodeData) {
                    ((MCPNodeData) serverNode.getUserObject()).setLabel(customLabel);
                }

                // Restore highlight colors and notes
                String colorsJson = entry.getString("highlightColors");
                String notesJson = entry.getString("notes");
                JsonObject colors = colorsJson != null ? com.google.gson.JsonParser.parseString(colorsJson).getAsJsonObject() : null;
                JsonObject notesMap = notesJson != null ? com.google.gson.JsonParser.parseString(notesJson).getAsJsonObject() : null;
                for (int i = 0; i < serverNode.getChildCount(); i++) {
                    DefaultMutableTreeNode category = (DefaultMutableTreeNode) serverNode.getChildAt(i);
                    for (int j = 0; j < category.getChildCount(); j++) {
                        DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) category.getChildAt(j);
                        if (leaf.getUserObject() instanceof MCPNodeData nd) {
                            String nodeKey = nd.getType().name() + "/" + nd.getLabel();
                            if (colors != null && colors.has(nodeKey)) {
                                nd.setHighlightColor(new Color(colors.get(nodeKey).getAsInt(), true));
                            }
                            if (notesMap != null && notesMap.has(nodeKey)) {
                                nd.setNotes(notesMap.get(nodeKey).getAsString());
                            }
                        }
                    }
                }
            }

            treeModel.reload(rootNode);
            tree.expandPath(new TreePath(rootNode.getPath()));
            Logger.info("Restored " + serverNodesByUrl.size() + " servers from project.");
        } catch (Exception e) {
            Logger.error("Failed to load saved data: " + e.getMessage());
        }
    }

    private void sendNodeToRepeater(MCPNodeData nodeData) {
        String body = nodeData.buildRepeaterBody();
        if (body == null) return;

        HttpRequestResponse originalReq = nodeData.getOriginalRequest();

        HttpRequest repeaterRequest = originalReq.request()
                .withMethod("POST")
                .withBody(body)
                .withRemovedHeader("Accept")
                .withRemovedHeader("Content-Type")
                .withRemovedHeader("Mcp-Session-Id")
                .withHeader("Content-Type", "application/json")
                .withHeader("Accept", "text/event-stream, application/json");

        if (nodeData.getSessionId() != null) {
            repeaterRequest = repeaterRequest.withHeader("Mcp-Session-Id", nodeData.getSessionId());
        }

        String baseName = nodeData.getType().name().toLowerCase() + "/" + nodeData.getLabel();
        String tabName = uniqueRepeaterTabName(baseName);
        api.repeater().sendToRepeater(repeaterRequest, tabName);
        Logger.info("Sent " + tabName + " to Repeater");
        focusRepeaterSubTab(tabName);
    }

    /**
     * Finds the Repeater's internal tab strip and selects the sub-tab matching the given name.
     * Runs with a short delay to let Burp finish creating the tab.
     */
    private void focusRepeaterSubTab(String tabName) {
        Timer timer = new Timer(200, ev -> {
            // Find all JTabbedPanes in the Burp frame and look for one containing our tab name
            java.awt.Window window = SwingUtilities.getWindowAncestor(panel);
            if (window == null) return;
            selectTabRecursive(window, tabName);
        });
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * Returns a unique tab name for the Repeater by appending #N if the name already exists.
     */
    private String uniqueRepeaterTabName(String baseName) {
        java.awt.Window window = SwingUtilities.getWindowAncestor(panel);
        if (window == null) return baseName;
        java.util.Set<String> existing = new java.util.HashSet<>();
        collectTabNamesRecursive(window, existing);
        if (!existing.contains(baseName)) return baseName;
        int n = 2;
        while (existing.contains(baseName + " #" + n)) n++;
        return baseName + " #" + n;
    }

    private static void collectTabNamesRecursive(Container container, java.util.Set<String> names) {
        if (container instanceof JTabbedPane tp) {
            for (int i = 0; i < tp.getTabCount(); i++) {
                names.add(tp.getTitleAt(i));
            }
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                collectTabNamesRecursive((Container) child, names);
            }
        }
    }

    private static boolean selectTabRecursive(Container container, String tabName) {
        if (container instanceof JTabbedPane tp) {
            for (int i = 0; i < tp.getTabCount(); i++) {
                if (tabName.equals(tp.getTitleAt(i))) {
                    tp.setSelectedIndex(i);
                    return true;
                }
            }
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                if (selectTabRecursive((Container) child, tabName)) return true;
            }
        }
        return false;
    }

    private void exportToMarkdown() {
        if (rootNode.getChildCount() == 0) {
            JOptionPane.showMessageDialog(panel, "No servers to export.", "Export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export MCPwned Report");
        chooser.setSelectedFile(new java.io.File("mcpwned-report.md"));
        if (chooser.showSaveDialog(panel) != JFileChooser.APPROVE_OPTION) return;

        StringBuilder md = new StringBuilder();
        md.append("# MCPwned Report\n\n");
        md.append("*Generated: ").append(java.time.LocalDateTime.now()).append("*\n\n");

        for (int s = 0; s < rootNode.getChildCount(); s++) {
            DefaultMutableTreeNode serverNode = (DefaultMutableTreeNode) rootNode.getChildAt(s);
            Object serverObj = serverNode.getUserObject();
            if (!(serverObj instanceof MCPNodeData serverData)) continue;

            md.append("## ").append(serverData.getLabel()).append("\n\n");
            if (serverData.getOriginalRequest() != null) {
                md.append("- **URL:** `").append(serverData.getOriginalRequest().request().url()).append("`\n");
            }
            if (serverData.getSessionId() != null) {
                md.append("- **Session ID:** `").append(serverData.getSessionId()).append("`\n");
            }
            if (serverData.getData() != null) {
                if (serverData.getData().has("protocolVersion")) {
                    md.append("- **Protocol Version:** ").append(serverData.getData().get("protocolVersion").getAsString()).append("\n");
                }
                if (serverData.getData().has("serverInfo") && serverData.getData().get("serverInfo").isJsonObject()) {
                    JsonObject si = serverData.getData().getAsJsonObject("serverInfo");
                    String name = si.has("name") ? si.get("name").getAsString() : "?";
                    String ver = si.has("version") ? si.get("version").getAsString() : "?";
                    md.append("- **Server:** ").append(name).append(" v").append(ver).append("\n");
                }
            }
            md.append("\n");

            // Walk categories
            for (int c = 0; c < serverNode.getChildCount(); c++) {
                DefaultMutableTreeNode catNode = (DefaultMutableTreeNode) serverNode.getChildAt(c);
                Object catObj = catNode.getUserObject();
                String catLabel = catObj instanceof MCPNodeData ? ((MCPNodeData) catObj).getLabel() : catObj.toString();
                md.append("### ").append(catLabel).append("\n\n");

                if (catNode.getChildCount() == 0) {
                    md.append("*None*\n\n");
                    continue;
                }

                for (int l = 0; l < catNode.getChildCount(); l++) {
                    DefaultMutableTreeNode leafNode = (DefaultMutableTreeNode) catNode.getChildAt(l);
                    Object leafObj = leafNode.getUserObject();
                    if (leafObj instanceof MCPNodeData) {
                        // buildMarkdown uses ### so bump it to #### for leaves under category
                        String leafMd = ((MCPNodeData) leafObj).buildMarkdown();
                        md.append(leafMd.replace("### ", "#### "));
                    }
                }
            }
            md.append("---\n\n");
        }

        try {
            java.nio.file.Files.writeString(chooser.getSelectedFile().toPath(), md.toString());
            Logger.info("Exported report to " + chooser.getSelectedFile().getAbsolutePath());
            JOptionPane.showMessageDialog(panel,
                    "Report exported to:\n" + chooser.getSelectedFile().getAbsolutePath(),
                    "Export", JOptionPane.INFORMATION_MESSAGE);
        } catch (java.io.IOException ex) {
            Logger.error("Failed to export report: " + ex.getMessage());
            JOptionPane.showMessageDialog(panel,
                    "Failed to export: " + ex.getMessage(),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
         * Small colored square icon for the highlight color menu items.
         */
        private record ColorIcon(Color color) implements Icon {

        @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(color);
                g.fillRect(x, y, getIconWidth(), getIconHeight());
                g.setColor(Color.DARK_GRAY);
                g.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
            }

            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }
        }

    /**
     * Hand-painted icons for tree leaf nodes. No font or file dependencies.
     */
    private static abstract class PaintedIcon implements Icon {
        static final int SIZE = 14;
        @Override public int getIconWidth() { return SIZE; }
        @Override public int getIconHeight() { return SIZE; }

        protected Graphics2D setup(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new java.awt.BasicStroke(1.5f));
            return g2;
        }
    }

    /** Wrench icon for tools */
    private static class WrenchIcon extends PaintedIcon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = setup(g);
            g2.setColor(new Color(0x66, 0x66, 0x66));
            // Handle
            g2.drawLine(x + 3, y + 11, x + 8, y + 6);
            // Head - circle at top-right
            g2.drawOval(x + 6, y + 1, 6, 6);
            // Notch in head
            g2.setColor(c.getBackground() != null ? c.getBackground() : Color.WHITE);
            g2.fillRect(x + 7, y + 1, 2, 3);
            g2.setColor(new Color(0x66, 0x66, 0x66));
            g2.drawLine(x + 7, y + 1, x + 7, y + 4);
            g2.drawLine(x + 9, y + 1, x + 9, y + 4);
            g2.dispose();
        }
    }

    /** Book icon for resources */
    private static class BookIcon extends PaintedIcon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = setup(g);
            // Book cover
            g2.setColor(new Color(0x44, 0x88, 0xCC));
            g2.fillRoundRect(x + 2, y + 1, 10, 12, 2, 2);
            // Pages
            g2.setColor(Color.WHITE);
            g2.fillRect(x + 4, y + 2, 7, 10);
            // Spine
            g2.setColor(new Color(0x33, 0x66, 0x99));
            g2.drawLine(x + 4, y + 1, x + 4, y + 13);
            // Lines on page
            g2.setColor(new Color(0xBB, 0xBB, 0xBB));
            g2.drawLine(x + 6, y + 4, x + 10, y + 4);
            g2.drawLine(x + 6, y + 6, x + 10, y + 6);
            g2.drawLine(x + 6, y + 8, x + 10, y + 8);
            g2.dispose();
        }
    }

    /** Robot icon for prompts */
    private static class RobotIcon extends PaintedIcon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = setup(g);
            // Head
            g2.setColor(new Color(0x88, 0x88, 0x88));
            g2.fillRoundRect(x + 2, y + 3, 10, 8, 3, 3);
            // Eyes
            g2.setColor(new Color(0x44, 0xCC, 0xFF));
            g2.fillRect(x + 4, y + 5, 2, 2);
            g2.fillRect(x + 8, y + 5, 2, 2);
            // Antenna
            g2.setColor(new Color(0x88, 0x88, 0x88));
            g2.drawLine(x + 7, y + 3, x + 7, y + 1);
            g2.fillOval(x + 6, y, 3, 3);
            // Mouth
            g2.setColor(new Color(0x44, 0xCC, 0xFF));
            g2.drawLine(x + 5, y + 9, x + 9, y + 9);
            g2.dispose();
        }
    }
}
