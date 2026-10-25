import json
from http.server import HTTPServer, BaseHTTPRequestHandler

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.end_headers()
        self.wfile.write(json.dumps({"status": "ACTIVE", "expires_at": "2027-01-01 00:00:00"}).encode("utf-8"))

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status": "ONLINE"}')

print("Локальный сервер лицензий запущен на порту 8000!")
HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
