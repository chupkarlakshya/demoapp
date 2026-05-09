const API = "";

// Area-wise contact number list derived from the 2 provided numbers
const responders = [
  { name: "Nidhi Rao", phone: "7000127676", role: "ZONE A: VIJAY NAGAR / NORTH" },
  { name: "Aman Singh", phone: "8889800445", role: "ZONE B: RAJWADA / CENTRAL" },
  { name: "Support Team 1", phone: "7000127676", role: "ZONE C: BHAWARKUAN / SOUTH" },
  { name: "Support Team 2", phone: "8889800445", role: "ZONE D: PALASIA / EAST" },
  { name: "Support Team 3", phone: "7000127676", role: "ZONE E: AERODROME / WEST" },
  { name: "Support Team 4", phone: "8889800445", role: "ZONE F: KANADIA / SOUTH-EAST" },
  { name: "Support Team 5", phone: "7000127676", role: "ZONE G: MR-10 / NORTH-WEST" },
  { name: "Support Team 6", phone: "8889800445", role: "ZONE H: ANNAPURNA / SOUTH-WEST" }
];

async function request(path, options) {
  const response = await fetch(`${API}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options
  });
  if (!response.ok) throw new Error(await response.text());
  return response.json();
}

async function sendSms(to, message) {
  try {
    const res = await request("/api/notify", {
      method: "POST",
      body: JSON.stringify({ to, message })
    });
    if (res.success) {
      alert(`Notification sent to ${to}`);
    } else {
      alert("Failed to send notification via Twilio.");
    }
  } catch (err) {
    console.error(err);
    alert("Network error: Could not reach backend.");
  }
}

function renderResponders() {
  const list = document.getElementById("responder-list");
  list.innerHTML = responders.map(r => `
    <div class="responder-card">
      <div class="resp-info">
        <strong>${r.name}</strong>
        <span class="phone">${r.phone}</span>
        <div class="role">${r.role}</div>
      </div>
      <div class="resp-actions">
        <button onclick="alert('Notification Status: SENT')">SENT</button>
        <button onclick="alert('Verification Status: PENDING')">VERIFY</button>
        <button class="primary" onclick="triggerResponderSms('${r.phone}')">SMS</button>
      </div>
    </div>
  `).join("");
}

function triggerResponderSms(phone) {
  const msg = "URGENT: SOS Alert triggered in your zone. Please check the SafePath Command Center immediately.";
  sendSms(phone, msg);
}

document.querySelector(".broadcast-btn").onclick = () => {
  if (confirm("Send urgent SMS broadcast to all 4 responders?")) {
    responders.forEach(r => triggerResponderSms(r.phone));
  }
};

async function loadActiveSOS() {
  try {
    const sosEvents = await request("/api/sos");
    const active = sosEvents.filter(s => s.status === "active");

    const empty = document.getElementById("no-active-sos");
    const card = document.getElementById("focus-card");

    if (active.length === 0) {
      empty.style.display = "block";
      card.style.display = "none";
      return;
    }

    // Focus on the most recent active SOS
    const current = active[0];
    empty.style.display = "none";
    card.style.display = "block";

    document.getElementById("sos-source").textContent = (current.userId || "RECEPTION").toUpperCase();
    document.getElementById("sos-title").textContent = `SOS Alert from ${current.userId || "Reception"}`;
    document.getElementById("stat-updated").textContent = new Date(current.createdAt).toLocaleString();
    document.getElementById("sos-trigger-id").textContent = `trigger:${current.id}`;
    document.getElementById("active-responders-count").textContent = `${active.length} Active Incidents`;

    document.getElementById("resolve-btn").onclick = async () => {
      await request(`/api/sos/${current.id}`, {
        method: "PATCH",
        body: JSON.stringify({ status: "resolved" })
      });
      loadActiveSOS();
    };

  } catch (err) {
    console.error("Failed to load SOS:", err);
  }
}

renderResponders();
loadActiveSOS();
setInterval(loadActiveSOS, 3000);
