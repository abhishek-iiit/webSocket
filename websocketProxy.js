const fs = require("fs");
const https = require("https");
const WebSocket = require("ws");
const url = require("url");

const targetBaseWsUrl = "wss://mosquitto.sre.turtle-feature.com:8443";

function createWsClient(path) {
  const targetWsUrl = `${targetBaseWsUrl}${path}`;
  console.log(`Connecting to WebSocket server at: ${targetWsUrl}`);

  return new WebSocket(targetWsUrl, {
    rejectUnauthorized: false, // Accept self-signed certificates
  });
}

const options = {
  key: fs.readFileSync("key.pem"),
  cert: fs.readFileSync("cert.pem"),
};

const server = https.createServer(options, (req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("WebSocket proxy server with HTTPS");
});

server.listen(8442, () => {
  console.log("Proxy server listening on https://localhost:8442");
});

const wss = new WebSocket.Server({ server });

wss.on("connection", (ws, req) => {
  const parsedUrl = url.parse(req.url, true);
  const path = parsedUrl.pathname;

  console.log(`Client connected to path: ${path}`);

  const wsClient = createWsClient(path);

  ws.on("message", (message) => {
    console.log("Relaying message from client to target server:", message);
    wsClient.send(message);
  });

  wsClient.on("message", (message) => {
    if (Buffer.isBuffer(message)) {
      message = message.toString("utf-8");
    }
    console.log(`Relaying message from target server to client: ${message}`);
    ws.send(message);
  });

  ws.on("close", () => {
    console.log("Client disconnected from path:", path);
  });

  wsClient.on("error", (err) => {
    console.error("WebSocket client error:", err);
    ws.close();
  });

  wsClient.on("close", () => {
    console.log("WebSocket server closed the connection");
    ws.close();
  });
});

wss.on("listening", () => {
  console.log("WebSocket Proxy Server is listening for connections");
});
