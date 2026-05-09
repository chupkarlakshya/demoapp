const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 8787);
const DATA_DIR = path.join(__dirname, "data");
const DATA_FILE = path.join(DATA_DIR, "safepath.json");
const SEED_FILE = path.join(DATA_DIR, "seed.json");
const PUBLIC_DIR = path.join(__dirname, "public");

function ensureDataFile() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(DATA_FILE)) {
    fs.copyFileSync(SEED_FILE, DATA_FILE);
  }
}

function readData() {
  ensureDataFile();
  return JSON.parse(fs.readFileSync(DATA_FILE, "utf8"));
}

function writeData(data) {
  fs.writeFileSync(DATA_FILE, JSON.stringify(data, null, 2));
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,PATCH,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type"
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", chunk => {
      body += chunk;
      if (body.length > 1_000_000) {
        req.destroy();
        reject(new Error("Body too large"));
      }
    });
    req.on("end", () => {
      if (!body) return resolve({});
      try {
        resolve(JSON.parse(body));
      } catch (error) {
        reject(error);
      }
    });
  });
}

function normalizeIncident(input) {
  const severity = Number(input.severity || 3);
  return {
    id: input.id || `inc_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    type: String(input.type || "OTHER").toUpperCase(),
    description: String(input.description || "").slice(0, 500),
    latitude: Number(input.latitude ?? input.lat),
    longitude: Number(input.longitude ?? input.lng),
    severity: Math.min(5, Math.max(1, severity)),
    status: input.status || "pending",
    reportedBy: input.reportedBy || "anonymous",
    createdAt: input.createdAt || new Date().toISOString()
  };
}

function normalizeSos(input) {
  return {
    id: input.id || `sos_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    userId: input.userId || "anonymous",
    contact: input.contact || "",
    latitude: Number(input.latitude ?? input.lat),
    longitude: Number(input.longitude ?? input.lng),
    status: input.status || "active",
    createdAt: input.createdAt || new Date().toISOString()
  };
}

function hasValidLocation(item) {
  return Number.isFinite(item.latitude) && Number.isFinite(item.longitude);
}

function overview(data) {
  const activeSos = data.sosEvents.filter(item => item.status === "active");
  const pendingReports = data.incidents.filter(item => item.status === "pending");
  const verifiedReports = data.incidents.filter(item => item.status === "verified");
  return {
    totalIncidents: data.incidents.length,
    verifiedReports: verifiedReports.length,
    pendingReports: pendingReports.length,
    activeSos: activeSos.length,
    highRiskZones: data.riskZones.filter(zone => Number(zone.riskScore) >= 65).length,
    lastUpdated: new Date().toISOString()
  };
}

function serveStatic(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const pathname = url.pathname === "/" ? "/index.html" : url.pathname;
  const safePath = path.normalize(pathname).replace(/^(\.\.[/\\])+/, "");
  const filePath = path.join(PUBLIC_DIR, safePath);
  if (!filePath.startsWith(PUBLIC_DIR) || !fs.existsSync(filePath)) {
    res.writeHead(404);
    res.end("Not found");
    return;
  }
  const ext = path.extname(filePath);
  const types = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8"
  };
  res.writeHead(200, { "Content-Type": types[ext] || "application/octet-stream" });
  fs.createReadStream(filePath).pipe(res);
}

async function handleApi(req, res) {
  const data = readData();
  const url = new URL(req.url, `http://${req.headers.host}`);
  const parts = url.pathname.split("/").filter(Boolean);

  if (req.method === "OPTIONS") return sendJson(res, 204, {});

  if (req.method === "GET" && url.pathname === "/api/health") {
    return sendJson(res, 200, { ok: true, service: "SafePath backend", time: new Date().toISOString() });
  }

  if (req.method === "GET" && url.pathname === "/api/overview") {
    return sendJson(res, 200, overview(data));
  }

  if (req.method === "GET" && url.pathname === "/api/incidents") {
    const status = url.searchParams.get("status");
    const incidents = status ? data.incidents.filter(item => item.status === status) : data.incidents;
    return sendJson(res, 200, incidents.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))));
  }

  if (req.method === "POST" && url.pathname === "/api/incidents") {
    const incident = normalizeIncident(await readBody(req));
    if (!hasValidLocation(incident)) return sendJson(res, 400, { error: "latitude and longitude are required" });
    data.incidents.push(incident);
    writeData(data);
    return sendJson(res, 201, incident);
  }

  if (req.method === "PATCH" && parts[0] === "api" && parts[1] === "incidents" && parts[2]) {
    const patch = await readBody(req);
    const incident = data.incidents.find(item => item.id === parts[2]);
    if (!incident) return sendJson(res, 404, { error: "incident not found" });
    if (patch.status) incident.status = patch.status;
    if (patch.severity) incident.severity = Math.min(5, Math.max(1, Number(patch.severity)));
    incident.reviewedAt = new Date().toISOString();
    writeData(data);
    return sendJson(res, 200, incident);
  }

  if (req.method === "GET" && url.pathname === "/api/sos") {
    return sendJson(res, 200, data.sosEvents.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))));
  }

  if (req.method === "POST" && url.pathname === "/api/sos") {
    const sos = normalizeSos(await readBody(req));
    if (!hasValidLocation(sos)) return sendJson(res, 400, { error: "latitude and longitude are required" });
    data.sosEvents.push(sos);
    writeData(data);
    return sendJson(res, 201, sos);
  }

  if (req.method === "PATCH" && parts[0] === "api" && parts[1] === "sos" && parts[2]) {
    const patch = await readBody(req);
    const sos = data.sosEvents.find(item => item.id === parts[2]);
    if (!sos) return sendJson(res, 404, { error: "sos event not found" });
    sos.status = patch.status || sos.status;
    sos.resolvedAt = sos.status === "resolved" ? new Date().toISOString() : sos.resolvedAt;
    writeData(data);
    return sendJson(res, 200, sos);
  }

  if (req.method === "GET" && url.pathname === "/api/risk-zones") {
    const verified = data.incidents
      .filter(item => item.status === "verified")
      .map(item => ({
        id: `report_zone_${item.id}`,
        name: item.type.replaceAll("_", " "),
        latitude: item.latitude,
        longitude: item.longitude,
        radiusMeters: 220 + item.severity * 40,
        riskScore: Math.min(100, 45 + item.severity * 10),
        source: "verified crowd report",
        updatedAt: item.reviewedAt || item.createdAt
      }));
    return sendJson(res, 200, [...data.riskZones, ...verified]);
  }

  return sendJson(res, 404, { error: "route not found" });
}

const server = http.createServer((req, res) => {
  if (req.url.startsWith("/api/")) {
    handleApi(req, res).catch(error => sendJson(res, 500, { error: error.message }));
  } else {
    serveStatic(req, res);
  }
});

server.listen(PORT, () => {
  ensureDataFile();
  console.log(`SafePath backend and dashboard running at http://localhost:${PORT}`);
});
