/**
 * C4 Diagram Renderer
 * A custom SVG-based renderer for C4 architecture diagrams.
 *
 * Supports: Person, System, System_Ext, Container, ContainerDb, Component,
 *           Container_Boundary, System_Boundary, Enterprise_Boundary, Rel
 */

const C4Renderer = (function() {
  'use strict';

  // Configuration
  const CONFIG = {
    // Element dimensions
    personWidth: 160,
    personHeight: 140,
    boxWidth: 180,
    boxHeight: 110, // Includes space for labels below the box

    // Spacing
    horizontalGap: 60,
    verticalGap: 80,
    boundaryPadding: 30,

    // Colors (Mermaid C4 blue palette)
    colors: {
      person: '#08427b',      // Dark blue for people
      system: '#1168bd',      // Blue for internal systems
      systemExt: '#666666',   // Gray for external systems
      container: '#438dd5',   // Lighter blue for containers
      containerDb: '#438dd5', // Same for databases
      component: '#85bbf0',   // Light blue for components
      boundary: '#444444',    // Dark gray dashed border
      relation: '#666666',    // Gray for relationship lines
      text: '#ffffff',        // White text on dark backgrounds
      textDark: '#333333',    // Dark text for light backgrounds
      background: 'transparent' // Transparent diagram background
    },

    // Typography
    fontSize: 12,
    titleFontSize: 14,
    labelFontSize: 11
  };

  // Parser: Convert C4 DSL to structured data
  function parse(code) {
    const elements = [];
    const relations = [];
    const boundaries = [];
    let currentBoundary = null;
    let title = '';

    const lines = code.split('\n').map(l => l.trim()).filter(l => l);

    for (const line of lines) {
      // Title
      if (line.startsWith('title ')) {
        title = line.substring(6).trim();
        continue;
      }

      // Boundary start
      const boundaryMatch = line.match(/^(Container_Boundary|System_Boundary|Enterprise_Boundary)\((\w+),\s*"([^"]+)"\)\s*\{/);
      if (boundaryMatch) {
        currentBoundary = {
          id: boundaryMatch[2],
          label: boundaryMatch[3],
          type: boundaryMatch[1],
          elements: []
        };
        boundaries.push(currentBoundary);
        continue;
      }

      // Boundary end
      if (line === '}') {
        currentBoundary = null;
        continue;
      }

      // Elements: Person, System, System_Ext, Container, ContainerDb, Component
      const elementMatch = line.match(/^(Person|System|System_Ext|Container|ContainerDb|Component)\((\w+),\s*"([^"]+)"(?:,\s*"([^"]*)")?(?:,\s*"([^"]*)")?\)/);
      if (elementMatch) {
        const element = {
          type: elementMatch[1],
          id: elementMatch[2],
          label: elementMatch[3],
          technology: elementMatch[4] || '',
          description: elementMatch[5] || ''
        };

        if (currentBoundary) {
          currentBoundary.elements.push(element);
        } else {
          elements.push(element);
        }
        continue;
      }

      // Relations: Rel(from, to, label, technology?)
      const relMatch = line.match(/^Rel\((\w+),\s*(\w+),\s*"([^"]+)"(?:,\s*"([^"]*)")?\)/);
      if (relMatch) {
        relations.push({
          from: relMatch[1],
          to: relMatch[2],
          label: relMatch[3],
          technology: relMatch[4] || ''
        });
        continue;
      }
    }

    return { title, elements, boundaries, relations };
  }

  // Layout: Calculate positions for all elements
  function layout(parsed) {
    const positions = new Map();
    let currentY = 60;
    let maxWidth = 0;

    // Layout standalone elements first (top row)
    if (parsed.elements.length > 0) {
      let currentX = CONFIG.boundaryPadding;
      for (const el of parsed.elements) {
        const width = el.type === 'Person' ? CONFIG.personWidth : CONFIG.boxWidth;
        const height = el.type === 'Person' ? CONFIG.personHeight : CONFIG.boxHeight;

        positions.set(el.id, {
          x: currentX,
          y: currentY,
          width,
          height,
          element: el
        });

        currentX += width + CONFIG.horizontalGap;
      }
      maxWidth = Math.max(maxWidth, currentX);
      currentY += CONFIG.personHeight + CONFIG.verticalGap;
    }

    // Layout boundaries
    for (const boundary of parsed.boundaries) {
      const boundaryStartY = currentY;
      let boundaryWidth = CONFIG.boundaryPadding * 2;
      let boundaryHeight = CONFIG.boundaryPadding * 2 + 30; // Extra for label

      // Layout elements within boundary
      let elementsPerRow = Math.min(boundary.elements.length, 3);
      let rows = Math.ceil(boundary.elements.length / elementsPerRow);

      let rowX = CONFIG.boundaryPadding * 2;
      let rowY = boundaryStartY + CONFIG.boundaryPadding + 30;
      let colIndex = 0;
      let rowMaxHeight = 0;

      for (const el of boundary.elements) {
        const width = CONFIG.boxWidth;
        const height = CONFIG.boxHeight;

        if (colIndex >= elementsPerRow) {
          colIndex = 0;
          rowX = CONFIG.boundaryPadding * 2;
          rowY += rowMaxHeight + CONFIG.verticalGap / 2;
          rowMaxHeight = 0;
        }

        positions.set(el.id, {
          x: rowX,
          y: rowY,
          width,
          height,
          element: el,
          boundaryId: boundary.id
        });

        rowX += width + CONFIG.horizontalGap / 2;
        rowMaxHeight = Math.max(rowMaxHeight, height);
        colIndex++;

        boundaryWidth = Math.max(boundaryWidth, rowX + CONFIG.boundaryPadding);
      }

      boundaryHeight = (rowY - boundaryStartY) + rowMaxHeight + CONFIG.boundaryPadding;

      positions.set(boundary.id, {
        x: CONFIG.boundaryPadding,
        y: boundaryStartY,
        width: boundaryWidth,
        height: boundaryHeight,
        boundary
      });

      maxWidth = Math.max(maxWidth, boundaryWidth + CONFIG.boundaryPadding * 2);
      currentY = boundaryStartY + boundaryHeight + CONFIG.verticalGap / 2;
    }

    return {
      positions,
      width: maxWidth + CONFIG.boundaryPadding,
      height: currentY + CONFIG.boundaryPadding
    };
  }

  // Render: Generate SVG
  function render(containerId, code) {
    const container = document.getElementById(containerId);
    if (!container) return;

    const parsed = parse(code);
    const { positions, width, height } = layout(parsed);

    // Create SVG
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('width', '100%');
    svg.setAttribute('height', height);
    svg.style.fontFamily = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';

    // Defs for markers and filters
    const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    defs.innerHTML = `
      <marker id="arrowhead-${containerId}" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
        <polygon points="0 0, 10 3.5, 0 7" fill="${CONFIG.colors.relation}" />
      </marker>
      <filter id="shadow-${containerId}" x="-20%" y="-20%" width="140%" height="140%">
        <feDropShadow dx="2" dy="2" stdDeviation="3" flood-opacity="0.2"/>
      </filter>
    `;
    svg.appendChild(defs);

    // Title (theme-aware)
    if (parsed.title) {
      const isDark = document.documentElement.getAttribute('data-theme') !== 'light';
      const titleColor = isDark ? '#c9d1d9' : '#1f2328';
      const titleEl = createText(width / 2, 25, parsed.title, CONFIG.titleFontSize + 2, 'middle', titleColor, 'bold');
      svg.appendChild(titleEl);
    }

    // Render relations FIRST (so they're behind everything else)
    for (const rel of parsed.relations) {
      const fromPos = positions.get(rel.from);
      const toPos = positions.get(rel.to);
      if (fromPos && toPos) {
        svg.appendChild(renderRelation(fromPos, toPos, rel, containerId));
      }
    }

    // Render boundaries
    for (const boundary of parsed.boundaries) {
      const pos = positions.get(boundary.id);
      svg.appendChild(renderBoundary(pos, containerId));
    }

    // Render elements (on top)
    for (const el of parsed.elements) {
      const pos = positions.get(el.id);
      svg.appendChild(renderElement(pos, containerId));
    }

    for (const boundary of parsed.boundaries) {
      for (const el of boundary.elements) {
        const pos = positions.get(el.id);
        svg.appendChild(renderElement(pos, containerId));
      }
    }

    container.innerHTML = '';
    container.appendChild(svg);
    container.style.display = 'block'; // Show the container (was hidden to prevent flash of unstyled content)
  }

  function renderBoundary(pos, containerId) {
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');

    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', pos.x);
    rect.setAttribute('y', pos.y);
    rect.setAttribute('width', pos.width);
    rect.setAttribute('height', pos.height);
    rect.setAttribute('fill', 'none');
    rect.setAttribute('stroke', CONFIG.colors.boundary);
    rect.setAttribute('stroke-width', '2');
    rect.setAttribute('stroke-dasharray', '10,5');
    rect.setAttribute('rx', '4');
    g.appendChild(rect);

    // Boundary label (theme-aware)
    const isDark = document.documentElement.getAttribute('data-theme') !== 'light';
    const labelColor = isDark ? '#c9d1d9' : '#1f2328';
    const subtitleColor = isDark ? '#8b949e' : '#666666';

    const label = createText(pos.x + 10, pos.y + 20, pos.boundary.label, CONFIG.titleFontSize, 'start', labelColor, 'bold');
    g.appendChild(label);

    // Boundary type subtitle
    const typeLabel = pos.boundary.type.replace('_Boundary', '').replace('_', ' ');
    const subtitle = createText(pos.x + 10, pos.y + 35, `[${typeLabel}]`, CONFIG.labelFontSize, 'start', subtitleColor);
    g.appendChild(subtitle);

    return g;
  }

  function renderElement(pos, containerId) {
    const el = pos.element;
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    g.setAttribute('filter', `url(#shadow-${containerId})`);

    if (el.type === 'Person') {
      // Person: circle head + body shape
      const color = CONFIG.colors.person;
      const cx = pos.x + pos.width / 2;
      const cy = pos.y + 30;

      // Head
      const head = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      head.setAttribute('cx', cx);
      head.setAttribute('cy', cy);
      head.setAttribute('r', 20);
      head.setAttribute('fill', color);
      g.appendChild(head);

      // Body (trapezoid-ish shape)
      const body = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      const bodyTop = cy + 25;
      const bodyBot = cy + 70;
      body.setAttribute('d', `M ${cx - 40} ${bodyBot} L ${cx - 25} ${bodyTop} L ${cx + 25} ${bodyTop} L ${cx + 40} ${bodyBot} Z`);
      body.setAttribute('fill', color);
      g.appendChild(body);

      // Labels (theme-aware)
      const isDark = document.documentElement.getAttribute('data-theme') !== 'light';
      const labelColor = isDark ? '#c9d1d9' : '#1f2328';
      g.appendChild(createText(cx, bodyBot + 20, el.label, CONFIG.fontSize, 'middle', labelColor, 'bold'));
      if (el.description) {
        g.appendChild(createText(cx, bodyBot + 35, el.description, CONFIG.labelFontSize, 'middle', isDark ? '#8b949e' : '#666666'));
      }
    } else {
      // Box-based elements - clean design with labels BELOW the box
      let color = CONFIG.colors.system;
      if (el.type === 'System_Ext') color = CONFIG.colors.systemExt;
      else if (el.type === 'Container' || el.type === 'ContainerDb') color = CONFIG.colors.container;
      else if (el.type === 'Component') color = CONFIG.colors.component;

      const isDark = document.documentElement.getAttribute('data-theme') !== 'light';
      const boxHeight = 60; // Shorter box, labels go below

      const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      rect.setAttribute('x', pos.x);
      rect.setAttribute('y', pos.y);
      rect.setAttribute('width', pos.width);
      rect.setAttribute('height', boxHeight);
      rect.setAttribute('fill', color);
      rect.setAttribute('rx', '6');
      g.appendChild(rect);

      // Database cylinder decoration
      if (el.type === 'ContainerDb') {
        const cylY = pos.y + 8;
        const ellipse = document.createElementNS('http://www.w3.org/2000/svg', 'ellipse');
        ellipse.setAttribute('cx', pos.x + pos.width / 2);
        ellipse.setAttribute('cy', cylY);
        ellipse.setAttribute('rx', pos.width / 2 - 10);
        ellipse.setAttribute('ry', 8);
        ellipse.setAttribute('fill', shadeColor(color, -20));
        g.appendChild(ellipse);
      }

      const cx = pos.x + pos.width / 2;
      const labelColor = isDark ? '#c9d1d9' : '#1f2328';
      const subtleColor = isDark ? '#8b949e' : '#666666';

      // Stereotype inside box (small, white)
      const stereotype = el.type.replace('_Ext', '').replace('Db', ' DB');
      g.appendChild(createText(cx, pos.y + boxHeight/2 + 4, stereotype, CONFIG.labelFontSize, 'middle', '#ffffff', 'normal', 0.9));

      // Labels BELOW the box for readability
      let textY = pos.y + boxHeight + 18;

      // Main label
      g.appendChild(createText(cx, textY, el.label, CONFIG.fontSize, 'middle', labelColor, 'bold'));
      textY += 14;

      // Technology
      if (el.technology) {
        g.appendChild(createText(cx, textY, `[${el.technology}]`, CONFIG.labelFontSize, 'middle', subtleColor));
        textY += 12;
      }

      // Description
      if (el.description) {
        g.appendChild(createText(cx, textY, el.description, CONFIG.labelFontSize - 1, 'middle', subtleColor, 'normal', 0.85));
      }
    }

    return g;
  }

  function renderRelation(from, to, rel, containerId) {
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');

    // Calculate connection points
    const fromCenter = { x: from.x + from.width / 2, y: from.y + from.height / 2 };
    const toCenter = { x: to.x + to.width / 2, y: to.y + to.height / 2 };

    // Find edge intersection points
    const fromPt = getEdgePoint(from, toCenter);
    const toPt = getEdgePoint(to, fromCenter);

    // Draw simple line with arrow - no labels (they're usually redundant)
    const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    line.setAttribute('x1', fromPt.x);
    line.setAttribute('y1', fromPt.y);
    line.setAttribute('x2', toPt.x);
    line.setAttribute('y2', toPt.y);
    line.setAttribute('stroke', CONFIG.colors.relation);
    line.setAttribute('stroke-width', '1');
    line.setAttribute('stroke-dasharray', '4,2'); // Dashed line for subtlety
    line.setAttribute('marker-end', `url(#arrowhead-${containerId})`);
    g.appendChild(line);

    return g;
  }

  function getEdgePoint(rect, target) {
    const cx = rect.x + rect.width / 2;
    const cy = rect.y + rect.height / 2;
    const dx = target.x - cx;
    const dy = target.y - cy;

    const absDx = Math.abs(dx);
    const absDy = Math.abs(dy);

    // Determine which edge to intersect
    if (absDx * rect.height > absDy * rect.width) {
      // Left or right edge
      const signX = dx > 0 ? 1 : -1;
      return {
        x: cx + signX * rect.width / 2,
        y: cy + dy * (rect.width / 2) / absDx
      };
    } else {
      // Top or bottom edge
      const signY = dy > 0 ? 1 : -1;
      return {
        x: cx + dx * (rect.height / 2) / absDy,
        y: cy + signY * rect.height / 2
      };
    }
  }

  function createText(x, y, text, size, anchor, fill, weight = 'normal', opacity = 1) {
    const el = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    el.setAttribute('x', x);
    el.setAttribute('y', y);
    el.setAttribute('font-size', size);
    el.setAttribute('text-anchor', anchor);
    el.setAttribute('fill', fill);
    el.setAttribute('font-weight', weight);
    el.setAttribute('opacity', opacity);
    el.textContent = text;
    return el;
  }

  function shadeColor(color, percent) {
    const num = parseInt(color.replace('#', ''), 16);
    const amt = Math.round(2.55 * percent);
    const R = (num >> 16) + amt;
    const G = (num >> 8 & 0x00FF) + amt;
    const B = (num & 0x0000FF) + amt;
    return '#' + (0x1000000 + (R < 255 ? R < 1 ? 0 : R : 255) * 0x10000 +
      (G < 255 ? G < 1 ? 0 : G : 255) * 0x100 +
      (B < 255 ? B < 1 ? 0 : B : 255)).toString(16).slice(1);
  }

  // Auto-initialize on page load
  function init() {
    const elements = document.querySelectorAll('.c4-custom');
    console.log('[C4Renderer] Found', elements.length, 'diagrams to render');

    elements.forEach((el, idx) => {
      const code = el.textContent;
      const containerId = el.id || `c4-diagram-${idx}`;
      el.id = containerId;
      console.log('[C4Renderer] Rendering', containerId);
      try {
        render(containerId, code);
      } catch (err) {
        console.error('[C4Renderer] Error rendering', containerId, err);
      }
    });
  }

  // Public API
  return {
    parse,
    layout,
    render,
    init,
    CONFIG
  };
})();

// Auto-init when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', C4Renderer.init);
} else {
  C4Renderer.init();
}
