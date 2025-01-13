const WebSocket = require("ws");
const http = require("http");
const url = require("url");

// Define the target WebSocket server base URL
const targetBaseWsUrl = "wss://mosquitto.sre.turtle-feature.com:8443"; // Base WebSocket URL without path

// Function to create a WebSocket client for a specific path
function createWsClient(path) {
  const targetWsUrl = `${targetBaseWsUrl}${path}`; // Append the path to the base URL
  console.log(`Connecting to WebSocket server at: ${targetWsUrl}`);

  return new WebSocket(targetWsUrl, {
    rejectUnauthorized: false, // This will bypass SSL certificate verification
  });
}

// Create an HTTP server to handle incoming requests (for status or other purposes)
const server = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("WebSocket proxy server");
});

// Listen on port 8442 for incoming HTTP requests
server.listen(8442, () => {
  console.log("Proxy server listening on http://localhost:8442");
});

// Create a WebSocket server that forwards connections to the target WebSocket server
const wss = new WebSocket.Server({ server });

// When a client connects to the proxy server
wss.on("connection", (ws, req) => {
  // Extract the WebSocket path from the request URL
  const parsedUrl = url.parse(req.url, true);
  const path = parsedUrl.pathname; // Get the WebSocket path from the URL

  console.log(`Client connected to path: ${path}`);

  // Create a WebSocket client to the target WebSocket server using the specific path
  const wsClient = createWsClient(path);

  // Relay messages between the client and the external WebSocket server
  ws.on("message", (message) => {
    console.log("Relaying message from client to target server:", message);
    wsClient.send(message); // Forward message from WebSocket proxy client to WebSocket server
  });

  // When the target WebSocket server sends a message, relay it to the client
  wsClient.on("message", (message) => {
    // Check if the message is binary (Buffer)
    if (Buffer.isBuffer(message)) {
      console.log("Received binary message from target WebSocket server");
      message = message.toString("utf-8"); // Assuming binary data represents UTF-8 text
    }

    console.log(`Relaying message from target server to client: ${message}`);
    ws.send(message); // Send the message to the WebSocket proxy client
  });

  // Handle client disconnect
  ws.on("close", () => {
    console.log("Client disconnected from path:", path);
  });

  // Handle errors for the WebSocket client
  wsClient.on("error", (err) => {
    console.error("WebSocket client error:", err);
    ws.close(); // Close the proxy connection if the client WebSocket has an error
  });

  // Handle error if WebSocket server closes unexpectedly
  wsClient.on("close", () => {
    console.log("WebSocket server closed the connection");
    ws.close(); // Close the proxy connection when the WebSocket client is closed
  });
});

// Set up event listener for when the WebSocket Proxy Server is listening for connections
wss.on("listening", () => {
  console.log("WebSocket Proxy Server is listening for connections");
});
