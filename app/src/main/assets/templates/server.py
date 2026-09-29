"""A local JSON API. Port and bind address come from Project settings."""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/", "/status", "/api"):
            self.send_error(404)
            return
        payload = json.dumps({"service": "runcode", "status": "ok", "path": self.path}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    address = os.environ.get("RUNCODE_BIND_ADDRESS", "127.0.0.1")
    port = int(os.environ.get("RUNCODE_PORT", "8080"))
    with HTTPServer((address, port), Handler) as server:
        # Bound waits let a pending stop reach the worker even when there is no traffic.
        server.timeout = 0.5
        print(f"HTTP API listening on {address}:{port}")
        while True:
            server.handle_request()
