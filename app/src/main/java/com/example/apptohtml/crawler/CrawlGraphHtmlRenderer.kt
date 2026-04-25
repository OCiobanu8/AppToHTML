package com.example.apptohtml.crawler

import java.util.Locale

object CrawlGraphHtmlRenderer {
    fun render(graph: CrawlGraph): String {
        val graphJson = escapeJsonForScriptTag(CrawlGraphJsonWriter.toJson(graph))
        val sessionTitle = escapeHtml("Crawl Graph ${graph.sessionId}")
        val packageName = escapeHtml(graph.packageName)
        val rootScreenId = escapeHtml(graph.rootScreenId ?: "Unavailable")
        val statusFilters = CrawlEdgeStatus.values().joinToString(separator = "\n") { status ->
            val statusName = status.jsonName()
            val label = escapeHtml(status.displayLabel())
            """          <label class="filter-chip status-$statusName"><input type="checkbox" data-status-filter="$statusName" checked> $label</label>"""
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>$sessionTitle</title>
              <style>
                :root {
                  color-scheme: light;
                  --bg: #eef4ff;
                  --bg-accent: #dbe9ff;
                  --panel: rgba(255, 255, 255, 0.88);
                  --panel-border: rgba(36, 53, 89, 0.14);
                  --ink: #132238;
                  --muted: #566579;
                  --grid: rgba(62, 94, 144, 0.12);
                  --captured: #2f6feb;
                  --linked-existing: #2f6feb;
                  --skipped-blacklist: #7b8798;
                  --skipped-no-navigation: #98a3b3;
                  --skipped-external-package: #d97706;
                  --failed: #d14343;
                  --node-width: 292px;
                  --node-height: 136px;
                }

                * {
                  box-sizing: border-box;
                }

                body {
                  margin: 0;
                  min-height: 100vh;
                  font-family: "Segoe UI", "Avenir Next", "Helvetica Neue", sans-serif;
                  color: var(--ink);
                  background:
                    radial-gradient(circle at top left, rgba(255, 255, 255, 0.9), transparent 32%),
                    linear-gradient(135deg, var(--bg) 0%, var(--bg-accent) 100%);
                }

                a {
                  color: inherit;
                }

                .shell {
                  display: flex;
                  flex-direction: column;
                  min-height: 100vh;
                  gap: 16px;
                  padding: 20px;
                }

                .topbar {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 16px;
                  align-items: flex-start;
                  justify-content: space-between;
                  padding: 20px 22px;
                  border: 1px solid var(--panel-border);
                  border-radius: 22px;
                  background: var(--panel);
                  backdrop-filter: blur(12px);
                  box-shadow: 0 18px 48px rgba(25, 42, 72, 0.12);
                }

                .headline {
                  display: grid;
                  gap: 8px;
                  max-width: 780px;
                }

                .eyebrow {
                  margin: 0;
                  font-size: 12px;
                  font-weight: 700;
                  letter-spacing: 0.16em;
                  text-transform: uppercase;
                  color: #44638f;
                }

                h1 {
                  margin: 0;
                  font-size: clamp(28px, 3.3vw, 40px);
                  line-height: 1.08;
                }

                .subhead {
                  margin: 0;
                  color: var(--muted);
                  line-height: 1.5;
                }

                .summary-grid {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
                  gap: 12px;
                  min-width: min(100%, 440px);
                  flex: 1 1 360px;
                }

                .summary-card {
                  padding: 14px 16px;
                  border-radius: 16px;
                  background: rgba(244, 248, 255, 0.9);
                  border: 1px solid rgba(36, 53, 89, 0.08);
                }

                .summary-card span {
                  display: block;
                  font-size: 12px;
                  text-transform: uppercase;
                  letter-spacing: 0.08em;
                  color: #62748a;
                }

                .summary-card strong {
                  display: block;
                  margin-top: 8px;
                  font-size: 20px;
                  line-height: 1.2;
                }

                .controls {
                  display: grid;
                  gap: 14px;
                  padding: 18px 20px;
                  border: 1px solid var(--panel-border);
                  border-radius: 20px;
                  background: var(--panel);
                  backdrop-filter: blur(12px);
                  box-shadow: 0 14px 36px rgba(25, 42, 72, 0.08);
                }

                .controls-row {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 12px;
                  align-items: center;
                  justify-content: space-between;
                }

                .controls h2 {
                  margin: 0;
                  font-size: 18px;
                }

                .controls p {
                  margin: 0;
                  color: var(--muted);
                }

                .button-row {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 10px;
                }

                button {
                  appearance: none;
                  border: 0;
                  border-radius: 999px;
                  padding: 10px 16px;
                  font: inherit;
                  font-weight: 600;
                  color: white;
                  background: linear-gradient(135deg, #2f6feb 0%, #1f5bd9 100%);
                  box-shadow: 0 10px 20px rgba(47, 111, 235, 0.24);
                  cursor: pointer;
                }

                button.secondary {
                  color: var(--ink);
                  background: rgba(244, 248, 255, 0.95);
                  box-shadow: inset 0 0 0 1px rgba(36, 53, 89, 0.12);
                }

                .filter-list {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 10px;
                }

                .filter-chip {
                  display: inline-flex;
                  align-items: center;
                  gap: 8px;
                  padding: 8px 12px;
                  border-radius: 999px;
                  background: rgba(255, 255, 255, 0.92);
                  border: 1px solid rgba(36, 53, 89, 0.08);
                  color: var(--ink);
                  font-weight: 600;
                }

                .filter-chip input {
                  margin: 0;
                }

                .filter-chip.status-captured {
                  box-shadow: inset 0 0 0 1px rgba(47, 111, 235, 0.18);
                }

                .filter-chip.status-linked_existing {
                  box-shadow: inset 0 0 0 1px rgba(47, 111, 235, 0.18);
                }

                .filter-chip.status-skipped_blacklist,
                .filter-chip.status-skipped_no_navigation {
                  box-shadow: inset 0 0 0 1px rgba(123, 135, 152, 0.22);
                }

                .filter-chip.status-skipped_external_package {
                  box-shadow: inset 0 0 0 1px rgba(217, 119, 6, 0.24);
                }

                .filter-chip.status-failed {
                  box-shadow: inset 0 0 0 1px rgba(209, 67, 67, 0.24);
                }

                .board {
                  position: relative;
                  overflow: hidden;
                  min-height: 68vh;
                  border-radius: 28px;
                  border: 1px solid rgba(36, 53, 89, 0.12);
                  background:
                    linear-gradient(rgba(255, 255, 255, 0.24), rgba(255, 255, 255, 0.24)),
                    linear-gradient(90deg, var(--grid) 1px, transparent 1px),
                    linear-gradient(var(--grid) 1px, transparent 1px);
                  background-size: auto, 44px 44px, 44px 44px;
                  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.55), 0 28px 60px rgba(26, 41, 68, 0.12);
                  touch-action: none;
                  user-select: none;
                }

                .board::after {
                  content: "";
                  position: absolute;
                  inset: 0;
                  background: radial-gradient(circle at center, transparent 40%, rgba(12, 24, 42, 0.08) 100%);
                  pointer-events: none;
                }

                .canvas {
                  position: absolute;
                  inset: 0 auto auto 0;
                  transform-origin: 0 0;
                }

                .edge-layer {
                  position: absolute;
                  inset: 0;
                  overflow: visible;
                }

                .edge {
                  fill: none;
                  stroke-width: 3;
                  opacity: 0.88;
                  transition: opacity 120ms ease, stroke-width 120ms ease, filter 120ms ease;
                }

                .edge-hitbox {
                  fill: none;
                  stroke: transparent;
                  stroke-width: 18;
                  cursor: pointer;
                }

                .edge-label {
                  font-size: 12px;
                  font-weight: 700;
                  fill: #344760;
                  paint-order: stroke;
                  stroke: rgba(255, 255, 255, 0.92);
                  stroke-width: 5px;
                  stroke-linejoin: round;
                }

                .edge-terminal {
                  fill: white;
                  stroke-width: 2;
                }

                .edge-group.status-captured .edge,
                .edge-group.status-captured .edge-terminal {
                  stroke: var(--captured);
                }

                .edge-group.status-linked_existing .edge,
                .edge-group.status-linked_existing .edge-terminal {
                  stroke: var(--linked-existing);
                  stroke-dasharray: 12 8;
                }

                .edge-group.status-skipped_blacklist .edge,
                .edge-group.status-skipped_blacklist .edge-terminal {
                  stroke: var(--skipped-blacklist);
                  stroke-dasharray: 3 8;
                }

                .edge-group.status-skipped_no_navigation .edge,
                .edge-group.status-skipped_no_navigation .edge-terminal {
                  stroke: var(--skipped-no-navigation);
                  stroke-width: 2;
                  stroke-dasharray: 2 10;
                }

                .edge-group.status-skipped_external_package .edge,
                .edge-group.status-skipped_external_package .edge-terminal {
                  stroke: var(--skipped-external-package);
                  stroke-dasharray: 10 7;
                }

                .edge-group.status-failed .edge,
                .edge-group.status-failed .edge-terminal {
                  stroke: var(--failed);
                }

                .node-card {
                  position: absolute;
                  width: var(--node-width);
                  min-height: var(--node-height);
                  padding: 16px 18px;
                  border-radius: 20px;
                  background: rgba(255, 255, 255, 0.95);
                  border: 1px solid rgba(36, 53, 89, 0.12);
                  box-shadow: 0 16px 34px rgba(24, 40, 68, 0.12);
                  display: grid;
                  gap: 10px;
                  transition: transform 120ms ease, opacity 120ms ease, box-shadow 120ms ease, border-color 120ms ease;
                  cursor: pointer;
                }

                .node-card:hover {
                  transform: translateY(-2px);
                  box-shadow: 0 20px 38px rgba(24, 40, 68, 0.16);
                }

                .node-card.is-root {
                  border-color: rgba(47, 111, 235, 0.35);
                  box-shadow: 0 18px 38px rgba(47, 111, 235, 0.16);
                }

                .node-topline,
                .node-meta,
                .artifact-list {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px;
                  align-items: center;
                }

                .node-title {
                  margin: 0;
                  font-size: 17px;
                  line-height: 1.25;
                  font-weight: 700;
                }

                .node-title-link {
                  color: var(--ink);
                  text-decoration: none;
                }

                .node-title-link:hover,
                .artifact-link:hover {
                  text-decoration: underline;
                }

                .pill {
                  display: inline-flex;
                  align-items: center;
                  gap: 6px;
                  padding: 5px 9px;
                  border-radius: 999px;
                  font-size: 11px;
                  font-weight: 700;
                  letter-spacing: 0.04em;
                  text-transform: uppercase;
                  color: #49617e;
                  background: #eef4ff;
                }

                .pill.root-pill {
                  color: #1854cc;
                  background: #dfeaff;
                }

                .node-meta {
                  font-size: 12px;
                  color: var(--muted);
                }

                .meta-label {
                  font-weight: 700;
                  color: #445972;
                }

                .artifact-list {
                  gap: 10px;
                }

                .artifact-link {
                  font-size: 12px;
                  font-weight: 700;
                  color: #1f5bd9;
                  text-decoration: none;
                }

                .node-fingerprint {
                  font-family: Consolas, "SFMono-Regular", Menlo, monospace;
                  font-size: 12px;
                  color: #5a6980;
                  white-space: nowrap;
                  overflow: hidden;
                  text-overflow: ellipsis;
                }

                .instructions {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 10px 18px;
                  color: var(--muted);
                  font-size: 13px;
                }

                .legend {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 12px;
                }

                .legend-item {
                  display: inline-flex;
                  align-items: center;
                  gap: 8px;
                  font-size: 13px;
                  color: var(--muted);
                }

                .legend-line {
                  width: 30px;
                  height: 0;
                  border-top-width: 3px;
                  border-top-style: solid;
                }

                .legend-line.status-captured {
                  border-color: var(--captured);
                }

                .legend-line.status-linked_existing {
                  border-color: var(--linked-existing);
                  border-top-style: dashed;
                }

                .legend-line.status-skipped_blacklist {
                  border-color: var(--skipped-blacklist);
                  border-top-style: dotted;
                }

                .legend-line.status-skipped_no_navigation {
                  border-color: var(--skipped-no-navigation);
                  border-top-style: dotted;
                  border-top-width: 2px;
                }

                .legend-line.status-skipped_external_package {
                  border-color: var(--skipped-external-package);
                  border-top-style: dashed;
                }

                .legend-line.status-failed {
                  border-color: var(--failed);
                }

                .is-hidden {
                  display: none;
                }

                .is-dimmed {
                  opacity: 0.18;
                }

                .is-highlighted {
                  opacity: 1;
                }

                .edge-group.is-highlighted .edge {
                  stroke-width: 5;
                  filter: drop-shadow(0 0 6px rgba(23, 36, 61, 0.22));
                }

                .empty-state {
                  position: absolute;
                  inset: 40px;
                  display: none;
                  place-items: center;
                  text-align: center;
                  color: var(--muted);
                  font-size: 16px;
                }

                .board.is-empty .empty-state {
                  display: grid;
                }

                @media (max-width: 960px) {
                  .shell {
                    padding: 14px;
                  }

                  .topbar,
                  .controls {
                    border-radius: 18px;
                  }

                  .board {
                    min-height: 60vh;
                    border-radius: 22px;
                  }
                }
              </style>
            </head>
            <body>
              <div class="shell">
                <section class="topbar">
                  <div class="headline">
                    <p class="eyebrow">Offline Crawl Graph</p>
                    <h1>$sessionTitle</h1>
                    <p class="subhead">Session package: <strong>$packageName</strong>. This viewer is fully self-contained and keeps the graph usable even when the crawl folder is copied to a machine with no network access.</p>
                  </div>
                  <div class="summary-grid">
                    <div class="summary-card">
                      <span>Nodes</span>
                      <strong id="summary-node-count">${graph.nodes.size}</strong>
                    </div>
                    <div class="summary-card">
                      <span>Edges</span>
                      <strong id="summary-edge-count">${graph.edges.size}</strong>
                    </div>
                    <div class="summary-card">
                      <span>Max Depth</span>
                      <strong id="summary-max-depth">${graph.maxDepthReached}</strong>
                    </div>
                    <div class="summary-card">
                      <span>Root Screen</span>
                      <strong id="summary-root-screen">$rootScreenId</strong>
                    </div>
                  </div>
                </section>

                <section class="controls">
                  <div class="controls-row">
                    <div>
                      <h2>Explore</h2>
                      <p>Drag to pan, use the mouse wheel to zoom, and hover any node or edge to spotlight its neighbors.</p>
                    </div>
                    <div class="button-row">
                      <button type="button" id="zoom-in-button">Zoom In</button>
                      <button type="button" id="zoom-out-button" class="secondary">Zoom Out</button>
                      <button type="button" id="reset-view-button" class="secondary">Reset View</button>
                    </div>
                  </div>

                  <div class="instructions">
                    <span>Columns follow crawl depth.</span>
                    <span>Rows follow discovery order.</span>
                    <span>Node links open the sibling HTML captures from this same folder.</span>
                  </div>

                  <div class="legend" id="status-legend"></div>

                  <div class="filter-list" id="status-filters">
$statusFilters
                  </div>
                </section>

                <section class="board" id="graph-board">
                  <div class="empty-state">
                    <div>
                      <h2>No graph nodes were saved for this crawl.</h2>
                      <p>Re-open the session folder after a manifest save to inspect traversal progress here.</p>
                    </div>
                  </div>
                  <div class="canvas" id="graph-canvas">
                    <svg class="edge-layer" id="edge-layer" aria-hidden="true"></svg>
                    <div id="node-layer"></div>
                  </div>
                </section>
              </div>

              <script type="application/json" id="crawl-graph-data">$graphJson</script>
              <script>
                (function() {
                  var rawGraph = document.getElementById("crawl-graph-data").textContent;
                  var graph = JSON.parse(rawGraph);
                  var svgNamespace = ["http", "://www.w3.org/2000/svg"].join("");
                  var statusMeta = {
                    captured: { label: "Captured", description: "Child screen captured normally." },
                    linked_existing: { label: "Linked Existing", description: "Traversal returned to a previously discovered screen." },
                    skipped_blacklist: { label: "Skipped Blacklist", description: "Edge was skipped by safety rules." },
                    skipped_no_navigation: { label: "Skipped No Navigation", description: "Tap did not lead to a new screen." },
                    skipped_external_package: { label: "Skipped External Package", description: "User chose to stay inside the selected app." },
                    failed: { label: "Failed", description: "Traversal could not capture the child screen." }
                  };
                  var layout = {
                    paddingX: 96,
                    paddingY: 72,
                    columnGap: 384,
                    rowGap: 176,
                    nodeWidth: 292,
                    nodeHeight: 136,
                    terminalOffsetX: 122,
                    terminalSpacingY: 34
                  };
                  var scale = 1;
                  var offsetX = 36;
                  var offsetY = 36;
                  var activeHighlight = null;
                  var dragState = null;
                  var board = document.getElementById("graph-board");
                  var canvas = document.getElementById("graph-canvas");
                  var edgeLayer = document.getElementById("edge-layer");
                  var nodeLayer = document.getElementById("node-layer");
                  var visibleStatuses = new Set(Object.keys(statusMeta));
                  var nodeElements = new Map();
                  var edgeElements = [];

                  if (!Array.isArray(graph.nodes) || graph.nodes.length === 0) {
                    board.classList.add("is-empty");
                    return;
                  }

                  populateLegend();
                  var laidOutNodes = createNodeLayout(graph.nodes);
                  var nodesById = new Map(laidOutNodes.map(function(node) {
                    return [node.screenId, node];
                  }));
                  var laidOutEdges = createEdgeLayout(graph.edges, nodesById);
                  var contentWidth = calculateContentWidth(laidOutNodes, laidOutEdges);
                  var contentHeight = calculateContentHeight(laidOutNodes, laidOutEdges);

                  canvas.style.width = contentWidth + "px";
                  canvas.style.height = contentHeight + "px";
                  edgeLayer.setAttribute("width", String(contentWidth));
                  edgeLayer.setAttribute("height", String(contentHeight));
                  edgeLayer.setAttribute("viewBox", "0 0 " + contentWidth + " " + contentHeight);
                  nodeLayer.style.width = contentWidth + "px";
                  nodeLayer.style.height = contentHeight + "px";

                  renderNodes(laidOutNodes);
                  renderEdges(laidOutEdges);
                  wireControls();
                  applyTransform();
                  applyFilters();

                  function populateLegend() {
                    var legend = document.getElementById("status-legend");
                    Object.keys(statusMeta).forEach(function(statusName) {
                      var item = document.createElement("span");
                      item.className = "legend-item";

                      var line = document.createElement("span");
                      line.className = "legend-line status-" + statusName;
                      item.appendChild(line);

                      var text = document.createElement("span");
                      text.textContent = statusMeta[statusName].label;
                      item.appendChild(text);

                      legend.appendChild(item);
                    });
                  }

                  function createNodeLayout(nodes) {
                    return nodes.map(function(node) {
                      return Object.assign({}, node, {
                        x: layout.paddingX + (node.depth * layout.columnGap),
                        y: layout.paddingY + (node.discoveryIndex * layout.rowGap)
                      });
                    });
                  }

                  function createEdgeLayout(edges, nodesById) {
                    var outgoingEdgeCounts = new Map();
                    return edges.map(function(edge, index) {
                      var source = nodesById.get(edge.fromScreenId);
                      var target = edge.toScreenId ? nodesById.get(edge.toScreenId) : null;
                      var sourceEdgeIndex = outgoingEdgeCounts.get(edge.fromScreenId) || 0;
                      outgoingEdgeCounts.set(edge.fromScreenId, sourceEdgeIndex + 1);

                      var fromX = source ? source.x + layout.nodeWidth : layout.paddingX;
                      var fromY = source ? source.y + (layout.nodeHeight / 2) : layout.paddingY;
                      var toX;
                      var toY;
                      if (target) {
                        toX = target.x;
                        toY = target.y + (layout.nodeHeight / 2);
                      } else {
                        toX = fromX + layout.terminalOffsetX;
                        toY = fromY - 28 + (sourceEdgeIndex * layout.terminalSpacingY);
                      }

                      return {
                        edgeId: edge.edgeId,
                        fromScreenId: edge.fromScreenId,
                        toScreenId: edge.toScreenId,
                        label: edge.label,
                        status: edge.status,
                        message: edge.message,
                        source: source,
                        target: target,
                        isTerminal: !target,
                        terminalX: target ? null : toX,
                        terminalY: target ? null : toY,
                        path: edgePath(fromX, fromY, toX, toY),
                        labelX: fromX + ((toX - fromX) / 2),
                        labelY: Math.min(fromY, toY) - 12 - ((index % 3) * 2)
                      };
                    });
                  }

                  function calculateContentWidth(nodes, edges) {
                    var nodeWidth = nodes.reduce(function(maxWidth, node) {
                      return Math.max(maxWidth, node.x + layout.nodeWidth);
                    }, 0);
                    var edgeWidth = edges.reduce(function(maxWidth, edge) {
                      return Math.max(maxWidth, edge.isTerminal ? edge.terminalX + 80 : 0);
                    }, 0);
                    return Math.max(nodeWidth, edgeWidth) + layout.paddingX;
                  }

                  function calculateContentHeight(nodes, edges) {
                    var nodeHeight = nodes.reduce(function(maxHeight, node) {
                      return Math.max(maxHeight, node.y + layout.nodeHeight);
                    }, 0);
                    var edgeHeight = edges.reduce(function(maxHeight, edge) {
                      return Math.max(maxHeight, edge.isTerminal ? edge.terminalY + 64 : 0);
                    }, 0);
                    return Math.max(nodeHeight, edgeHeight) + layout.paddingY;
                  }

                  function renderNodes(nodes) {
                    nodes.forEach(function(node) {
                      var card = document.createElement("article");
                      card.className = "node-card";
                      card.dataset.nodeId = node.screenId;
                      if (node.screenId === graph.rootScreenId) {
                        card.classList.add("is-root");
                      }
                      card.style.left = node.x + "px";
                      card.style.top = node.y + "px";

                      var topline = document.createElement("div");
                      topline.className = "node-topline";

                      var depthPill = document.createElement("span");
                      depthPill.className = "pill";
                      depthPill.textContent = "Depth " + node.depth;
                      topline.appendChild(depthPill);

                      var orderPill = document.createElement("span");
                      orderPill.className = "pill";
                      orderPill.textContent = "Order " + node.discoveryIndex;
                      topline.appendChild(orderPill);

                      if (node.screenId === graph.rootScreenId) {
                        var rootPill = document.createElement("span");
                        rootPill.className = "pill root-pill";
                        rootPill.textContent = "Root";
                        topline.appendChild(rootPill);
                      }

                      card.appendChild(topline);

                      var title = document.createElement("h2");
                      title.className = "node-title";
                      if (node.htmlFileName) {
                        var titleLink = document.createElement("a");
                        titleLink.className = "node-title-link";
                        titleLink.href = node.htmlFileName;
                        titleLink.textContent = node.screenName;
                        title.appendChild(titleLink);
                      } else {
                        title.textContent = node.screenName;
                      }
                      card.appendChild(title);

                      var meta = document.createElement("div");
                      meta.className = "node-meta";
                      appendMeta(meta, "ID", node.screenId);
                      appendMeta(meta, "Package", node.packageName);
                      card.appendChild(meta);

                      var artifacts = document.createElement("div");
                      artifacts.className = "artifact-list";
                      appendArtifactLink(artifacts, node.htmlFileName, "HTML");
                      appendArtifactLink(artifacts, node.xmlFileName, "XML");
                      appendArtifactLink(artifacts, node.mergedXmlFileName, "Merged XML");
                      card.appendChild(artifacts);

                      var fingerprint = document.createElement("div");
                      fingerprint.className = "node-fingerprint";
                      fingerprint.title = node.fingerprint;
                      fingerprint.textContent = "Fingerprint: " + node.fingerprint;
                      card.appendChild(fingerprint);

                      card.addEventListener("mouseenter", function() {
                        highlightForNode(node.screenId);
                      });
                      card.addEventListener("mouseleave", clearHighlight);
                      card.addEventListener("focusin", function() {
                        highlightForNode(node.screenId);
                      });
                      card.addEventListener("focusout", clearHighlight);

                      nodeLayer.appendChild(card);
                      nodeElements.set(node.screenId, card);
                    });
                  }

                  function renderEdges(edges) {
                    edges.forEach(function(edge) {
                      var group = document.createElementNS(svgNamespace, "g");
                      group.setAttribute("class", "edge-group status-" + edge.status);
                      group.dataset.edgeId = edge.edgeId;
                      group.dataset.status = edge.status;
                      group.dataset.fromScreenId = edge.fromScreenId;
                      if (edge.toScreenId) {
                        group.dataset.toScreenId = edge.toScreenId;
                      }

                      var path = document.createElementNS(svgNamespace, "path");
                      path.setAttribute("class", "edge");
                      path.setAttribute("d", edge.path);
                      group.appendChild(path);

                      var hitbox = document.createElementNS(svgNamespace, "path");
                      hitbox.setAttribute("class", "edge-hitbox");
                      hitbox.setAttribute("d", edge.path);
                      group.appendChild(hitbox);

                      if (edge.isTerminal) {
                        var terminal = document.createElementNS(svgNamespace, "circle");
                        terminal.setAttribute("class", "edge-terminal");
                        terminal.setAttribute("cx", String(edge.terminalX));
                        terminal.setAttribute("cy", String(edge.terminalY));
                        terminal.setAttribute("r", "8");
                        group.appendChild(terminal);
                      }

                      var label = document.createElementNS(svgNamespace, "text");
                      label.setAttribute("class", "edge-label");
                      label.setAttribute("x", String(edge.labelX));
                      label.setAttribute("y", String(edge.labelY));
                      label.textContent = edge.label;
                      group.appendChild(label);

                      var title = document.createElementNS(svgNamespace, "title");
                      var statusDescription = statusMeta[edge.status] ? statusMeta[edge.status].description : edge.status;
                      title.textContent = edge.label + " (" + statusDescription + ")" + (edge.message ? " - " + edge.message : "");
                      group.appendChild(title);

                      group.addEventListener("mouseenter", function() {
                        highlightForEdge(edge.edgeId);
                      });
                      group.addEventListener("mouseleave", clearHighlight);
                      hitbox.addEventListener("focus", function() {
                        highlightForEdge(edge.edgeId);
                      });

                      edgeLayer.appendChild(group);
                      edgeElements.push({
                        data: edge,
                        group: group
                      });
                    });
                  }

                  function appendMeta(container, label, value) {
                    var wrapper = document.createElement("span");
                    var labelSpan = document.createElement("span");
                    labelSpan.className = "meta-label";
                    labelSpan.textContent = label + ": ";
                    wrapper.appendChild(labelSpan);
                    wrapper.appendChild(document.createTextNode(value));
                    container.appendChild(wrapper);
                  }

                  function appendArtifactLink(container, fileName, label) {
                    if (!fileName) {
                      return;
                    }
                    var link = document.createElement("a");
                    link.className = "artifact-link";
                    link.href = fileName;
                    link.textContent = label;
                    container.appendChild(link);
                  }

                  function edgePath(fromX, fromY, toX, toY) {
                    var controlOffset = Math.max(84, (toX - fromX) * 0.42);
                    var controlX1 = fromX + controlOffset;
                    var controlX2 = toX - Math.max(64, controlOffset * 0.42);
                    return "M " + fromX + " " + fromY + " C " + controlX1 + " " + fromY + ", " + controlX2 + " " + toY + ", " + toX + " " + toY;
                  }

                  function wireControls() {
                    board.addEventListener("pointerdown", onPointerDown);
                    board.addEventListener("pointermove", onPointerMove);
                    board.addEventListener("pointerup", onPointerUp);
                    board.addEventListener("pointerleave", onPointerUp);
                    board.addEventListener("wheel", onWheel, { passive: false });

                    document.getElementById("zoom-in-button").addEventListener("click", function() {
                      zoomAround(board.clientWidth / 2, board.clientHeight / 2, 1.15);
                    });
                    document.getElementById("zoom-out-button").addEventListener("click", function() {
                      zoomAround(board.clientWidth / 2, board.clientHeight / 2, 1 / 1.15);
                    });
                    document.getElementById("reset-view-button").addEventListener("click", function() {
                      scale = 1;
                      offsetX = 36;
                      offsetY = 36;
                      applyTransform();
                    });

                    document.querySelectorAll("[data-status-filter]").forEach(function(input) {
                      input.addEventListener("change", function(event) {
                        var checkbox = event.currentTarget;
                        var status = checkbox.getAttribute("data-status-filter");
                        if (checkbox.checked) {
                          visibleStatuses.add(status);
                        } else {
                          visibleStatuses.delete(status);
                        }
                        applyFilters();
                      });
                    });
                  }

                  function onPointerDown(event) {
                    if (event.button !== 0) {
                      return;
                    }
                    dragState = {
                      pointerId: event.pointerId,
                      startX: event.clientX,
                      startY: event.clientY,
                      initialOffsetX: offsetX,
                      initialOffsetY: offsetY
                    };
                    if (board.setPointerCapture) {
                      board.setPointerCapture(event.pointerId);
                    }
                  }

                  function onPointerMove(event) {
                    if (!dragState || dragState.pointerId !== event.pointerId) {
                      return;
                    }
                    offsetX = dragState.initialOffsetX + (event.clientX - dragState.startX);
                    offsetY = dragState.initialOffsetY + (event.clientY - dragState.startY);
                    applyTransform();
                  }

                  function onPointerUp(event) {
                    if (!dragState || dragState.pointerId !== event.pointerId) {
                      return;
                    }
                    dragState = null;
                    if (board.releasePointerCapture) {
                      board.releasePointerCapture(event.pointerId);
                    }
                  }

                  function onWheel(event) {
                    event.preventDefault();
                    var rect = board.getBoundingClientRect();
                    zoomAround(event.clientX - rect.left, event.clientY - rect.top, event.deltaY < 0 ? 1.12 : 1 / 1.12);
                  }

                  function zoomAround(boardX, boardY, factor) {
                    var nextScale = clamp(scale * factor, 0.35, 2.8);
                    var canvasX = (boardX - offsetX) / scale;
                    var canvasY = (boardY - offsetY) / scale;
                    offsetX = boardX - (canvasX * nextScale);
                    offsetY = boardY - (canvasY * nextScale);
                    scale = nextScale;
                    applyTransform();
                  }

                  function clamp(value, min, max) {
                    return Math.min(max, Math.max(min, value));
                  }

                  function applyTransform() {
                    canvas.style.transform = "translate(" + offsetX + "px, " + offsetY + "px) scale(" + scale + ")";
                  }

                  function applyFilters() {
                    edgeElements.forEach(function(entry) {
                      var hidden = !visibleStatuses.has(entry.data.status);
                      entry.group.classList.toggle("is-hidden", hidden);
                    });
                    if (activeHighlight) {
                      if (activeHighlight.kind === "node") {
                        highlightForNode(activeHighlight.id);
                      } else {
                        highlightForEdge(activeHighlight.id);
                      }
                    } else {
                      clearHighlight();
                    }
                  }

                  function highlightForNode(screenId) {
                    activeHighlight = { kind: "node", id: screenId };
                    var connectedEdges = new Set();
                    var connectedNodes = new Set([screenId]);

                    edgeElements.forEach(function(entry) {
                      var edge = entry.data;
                      if (!visibleStatuses.has(edge.status)) {
                        return;
                      }
                      if (edge.fromScreenId === screenId || edge.toScreenId === screenId) {
                        connectedEdges.add(edge.edgeId);
                        connectedNodes.add(edge.fromScreenId);
                        if (edge.toScreenId) {
                          connectedNodes.add(edge.toScreenId);
                        }
                      }
                    });

                    paintHighlight(connectedNodes, connectedEdges);
                  }

                  function highlightForEdge(edgeId) {
                    activeHighlight = { kind: "edge", id: edgeId };
                    var edgeEntry = edgeElements.find(function(entry) {
                      return entry.data.edgeId === edgeId && visibleStatuses.has(entry.data.status);
                    });
                    if (!edgeEntry) {
                      clearHighlight();
                      return;
                    }

                    var nodes = new Set([edgeEntry.data.fromScreenId]);
                    if (edgeEntry.data.toScreenId) {
                      nodes.add(edgeEntry.data.toScreenId);
                    }
                    paintHighlight(nodes, new Set([edgeId]));
                  }

                  function paintHighlight(highlightedNodes, highlightedEdges) {
                    nodeElements.forEach(function(element, nodeId) {
                      var highlighted = highlightedNodes.has(nodeId);
                      element.classList.toggle("is-highlighted", highlighted);
                      element.classList.toggle("is-dimmed", !highlighted);
                    });

                    edgeElements.forEach(function(entry) {
                      var visible = visibleStatuses.has(entry.data.status);
                      var highlighted = visible && highlightedEdges.has(entry.data.edgeId);
                      entry.group.classList.toggle("is-highlighted", highlighted);
                      entry.group.classList.toggle("is-dimmed", visible && !highlighted);
                    });
                  }

                  function clearHighlight() {
                    activeHighlight = null;
                    nodeElements.forEach(function(element) {
                      element.classList.remove("is-highlighted");
                      element.classList.remove("is-dimmed");
                    });
                    edgeElements.forEach(function(entry) {
                      entry.group.classList.remove("is-highlighted");
                      entry.group.classList.remove("is-dimmed");
                    });
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeJsonForScriptTag(json: String): String {
        return json
            .replace("&", "\\u0026")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> char
                }
            )
        }
    }

    private fun CrawlEdgeStatus.displayLabel(): String {
        return when (this) {
            CrawlEdgeStatus.CAPTURED -> "Captured"
            CrawlEdgeStatus.LINKED_EXISTING -> "Linked Existing"
            CrawlEdgeStatus.SKIPPED_BLACKLIST -> "Skipped Blacklist"
            CrawlEdgeStatus.SKIPPED_NO_NAVIGATION -> "Skipped No Navigation"
            CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE -> "Skipped External Package"
            CrawlEdgeStatus.FAILED -> "Failed"
        }
    }

    private fun CrawlEdgeStatus.jsonName(): String = name.lowercase(Locale.US)
}
