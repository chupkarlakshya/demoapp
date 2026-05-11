const API = "";
let map;
let markersLayer = L.layerGroup();
let policeLayer = L.layerGroup();
let showPolice = false;

const els = {
  totalIncidents: document.querySelector("#totalIncidents"),
  pendingReports: document.querySelector("#pendingReports"),
  activeSos: document.querySelector("#activeSos"),
  highRiskZones: document.querySelector("#highRiskZones"),
  mapCanvas: document.querySelector("#mapCanvas"),
  sosList: document.querySelector("#sosList"),
  incidentTable: document.querySelector("#incidentTable"),
  refreshButton: document.querySelector("#refreshButton"),
  policeToggle: document.querySelector("#policeToggle"),
  statusFilter: document.querySelector("#statusFilter")
};

const policeStations = [
  { name: "Vijay Nagar Police Station", lat: 22.7533, lng: 75.8937 },
  { name: "Palasia Police Station", lat: 22.7244, lng: 75.8839 },
  { name: "Sarafa Police Station (Rajwada)", lat: 22.7196, lng: 75.8577 },
  { name: "Bhawarkuan Police Station", lat: 22.7001, lng: 75.8701 },
  { name: "Annapurna Police Station", lat: 22.6934, lng: 75.8344 },
  { name: "Khajrana Police Station", lat: 22.7441, lng: 75.9012 },
  { name: "Tukoganj Police Station", lat: 22.7231, lng: 75.8744 },
  { name: "Aerodrome Police Station", lat: 22.7248, lng: 75.8075 }
];

// Initialize Map
function initMap() {
  map = L.map('mapCanvas').setView([22.7196, 75.8577], 13);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }).addTo(map);

  markersLayer.addTo(map);
  policeLayer.addTo(map);

  renderPoliceStations();
}

function renderPoliceStations() {
  policeLayer.clearLayers();
  policeStations.forEach(ps => {
    const marker = L.marker([ps.lat, ps.lng], {
      icon: L.divIcon({
        className: 'police-icon',
        html: `<div style="background: #276fc2; color: white; padding: 5px; border-radius: 50%; width: 30px; height: 30px; display: flex; align-items: center; justify-content: center; border: 2px solid white; box-shadow: 0 2px 5px rgba(0,0,0,0.3);">👮</div>`,
        iconSize: [30, 30],
        iconAnchor: [15, 15]
      })
    });
    marker.bindPopup(`
      <div class="modern-popup">
        <h3>👮 ${ps.name}</h3>
        <p>Status: <span style="color: var(--green)">Ready to Dispatch</span></p>
        <p>Response Time: ~4 mins</p>
      </div>
    `);
    policeLayer.addLayer(marker);
  });
}

async function request(path, options) {
  const response = await fetch(`${API}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options
  });
  if (!response.ok) throw new Error(await response.text());
  return response.json();
}

function fmtType(type) {
  return String(type || "OTHER").replaceAll("_", " ");
}

function fmtTime(value) {
  if (!value) return "-";
  return new Date(value).toLocaleString();
}

function renderOverview(overview) {
  els.totalIncidents.textContent = overview.totalIncidents;
  els.pendingReports.textContent = overview.pendingReports;
  els.activeSos.textContent = overview.activeSos;
  els.highRiskZones.textContent = overview.highRiskZones;
}

function renderMap(incidents, sosEvents) {
  markersLayer.clearLayers();

  incidents.forEach(incident => {
    const color = incident.status === "verified" ? "#25895f" : "#c98215";
    const marker = L.circleMarker([incident.latitude, incident.longitude], {
      color: "#ffffff",
      fillColor: color,
      fillOpacity: 0.9,
      radius: 8,
      weight: 2
    });

    marker.bindPopup(`
      <div class="modern-popup">
        <h3>📍 ${fmtType(incident.type)}</h3>
        <p>${incident.description || "No additional details provided."}</p>
        <div class="status-tag" style="background: ${color}22; color: ${color}">
          ${incident.status}
        </div>
      </div>
    `);

    markersLayer.addLayer(marker);
  });

  sosEvents.filter(item => item.status === "active").forEach(sos => {
    const marker = L.circleMarker([sos.latitude, sos.longitude], {
      color: "#ffffff",
      fillColor: "#cb3d3d",
      fillOpacity: 1,
      radius: 10,
      weight: 3
    });

    marker.bindPopup(`
      <div class="modern-popup">
        <h3 style="color: var(--red)">⚠️ ACTIVE SOS</h3>
        <p><strong>User:</strong> ${sos.userId || "anonymous"}</p>
        <p><strong>Time:</strong> ${fmtTime(sos.createdAt)}</p>
        <p style="margin-top: 8px; font-size: 11px; color: var(--red);">Dispatch immediate response</p>
      </div>
    `);

    markersLayer.addLayer(marker);
  });
}

function renderSos(sosEvents) {
  const active = sosEvents.filter(item => item.status === "active");
  if (active.length === 0) {
    els.sosList.innerHTML = `<div class="item"><strong>No active SOS</strong><p>Emergency events will appear here instantly.</p></div>`;
    return;
  }
  els.sosList.innerHTML = active.map(item => `
    <div class="item" data-focus-lat="${item.latitude}" data-focus-lng="${item.longitude}" style="cursor: pointer;">
      <strong>${item.userId || "anonymous"} (Click to Zoom)</strong>
      <p>${Number(item.latitude).toFixed(5)}, ${Number(item.longitude).toFixed(5)}<br>${fmtTime(item.createdAt)}</p>
      <button class="primary" data-resolve-sos="${item.id}">Mark Resolved</button>
    </div>
  `).join("");
}

function renderIncidents(incidents) {
  const status = els.statusFilter.value;
  const rows = status ? incidents.filter(item => item.status === status) : incidents;
  els.incidentTable.innerHTML = rows.map(item => `
    <tr>
      <td>${fmtType(item.type)}</td>
      <td>${item.severity}</td>
      <td><span class="badge" data-status="${item.status}">${item.status}</span></td>
      <td>${Number(item.latitude).toFixed(5)}, ${Number(item.longitude).toFixed(5)}</td>
      <td>${item.description || "-"}</td>
      <td>
        <div class="actions">
          <button class="primary" data-status="verified" data-id="${item.id}">Verify</button>
          <button class="danger" data-status="rejected" data-id="${item.id}">Reject</button>
        </div>
      </td>
    </tr>
  `).join("");
}

async function load() {
  try {
    const [overview, incidents, sosEvents] = await Promise.all([
      request("/api/overview"),
      request("/api/incidents"),
      request("/api/sos")
    ]);
    renderOverview(overview);
    renderMap(incidents, sosEvents);
    renderSos(sosEvents);
    renderIncidents(incidents);
  } catch (err) {
    console.error("Failed to load dashboard data:", err);
  }
}

document.addEventListener("click", async event => {
  const focusEl = event.target.closest("[data-focus-lat]");
  if (focusEl && !event.target.closest("button")) {
    const lat = parseFloat(focusEl.dataset.focusLat);
    const lng = parseFloat(focusEl.dataset.focusLng);
    map.setView([lat, lng], 16, { animate: true });
    return;
  }

  const statusButton = event.target.closest("[data-status]");
  if (statusButton) {
    await request(`/api/incidents/${statusButton.dataset.id}`, {
      method: "PATCH",
      body: JSON.stringify({ status: statusButton.dataset.status })
    });
    await load();
  }

  const sosButton = event.target.closest("[data-resolve-sos]");
  if (sosButton) {
    await request(`/api/sos/${sosButton.dataset.resolveSos}`, {
      method: "PATCH",
      body: JSON.stringify({ status: "resolved" })
    });
    await load();
  }
});

els.policeToggle.addEventListener("click", () => {
  showPolice = !showPolice;
  if (showPolice) {
    map.addLayer(policeLayer);
    els.policeToggle.style.background = "#276fc2";
    els.policeToggle.style.color = "white";
    els.policeToggle.textContent = "Hide Police Stations";
  } else {
    map.removeLayer(policeLayer);
    els.policeToggle.style.background = "white";
    els.policeToggle.style.color = "#276fc2";
    els.policeToggle.textContent = "Show Police Stations";
  }
});

els.refreshButton.addEventListener("click", load);
els.statusFilter.addEventListener("change", load);

initMap();
// Start with police stations hidden
map.removeLayer(policeLayer);
load();
setInterval(load, 5000);
