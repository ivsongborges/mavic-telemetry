"""Servidor web do dashboard (somente biblioteca padrão).

Rotas:
    /             página do dashboard (web/index.html)
    /events       Server-Sent Events: uma mensagem JSON por pacote de telemetria
    /api/latest   último pacote (JSON)
    /api/track    trilha de posições [[lat, lon, alt, ts], ...]
    /api/logs     lista de logs (.csv/.jsonl) da pasta de logs
    /api/log?name=arquivo.csv   conteúdo de um log (para o modo Replay)
"""
import json
import os
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
INDEX = os.path.join(WEB_DIR, "index.html")


def _valid_pos(lat, lon):
    return (isinstance(lat, (int, float)) and isinstance(lon, (int, float))
            and abs(lat) <= 90 and abs(lon) <= 180 and (abs(lat) > 1e-6 or abs(lon) > 1e-6))


class Hub:
    """Estado compartilhado entre o loop UDP (produtor) e os clientes web (consumidores)."""

    def __init__(self, track_len=20000):
        self.cond = threading.Condition()
        self.version = 0
        self.count = 0
        self.lost = 0
        self.latest = None
        self.payload = "{}"
        self.track = deque(maxlen=track_len)
        self.log_dir = None

    def publish(self, msg, lost):
        with self.cond:
            self.count += 1
            self.lost = lost
            self.latest = msg
            self.payload = json.dumps(dict(msg, _n=self.count, _lost=lost))
            lat, lon = msg.get("lat"), msg.get("lon")
            if _valid_pos(lat, lon):
                last = self.track[-1] if self.track else None
                # ~0,5 m: evita encher a trilha com o drone parado
                if last is None or abs(lat - last[0]) > 5e-6 or abs(lon - last[1]) > 5e-6:
                    self.track.append([lat, lon, msg.get("alt"), msg.get("ts")])
            self.version += 1
            self.cond.notify_all()


def _log_path(hub, name):
    """Caminho seguro de um log: só arquivos .csv/.jsonl direto dentro da pasta de logs."""
    if not hub.log_dir or not name or os.path.basename(name) != name:
        return None
    if not name.lower().endswith((".csv", ".jsonl")):
        return None
    path = os.path.join(hub.log_dir, name)
    return path if os.path.isfile(path) else None


def _list_logs(hub):
    out = []
    if hub.log_dir and os.path.isdir(hub.log_dir):
        for name in os.listdir(hub.log_dir):
            path = _log_path(hub, name)
            if path:
                st = os.stat(path)
                out.append({"name": name, "size": st.st_size, "mtime": int(st.st_mtime)})
    out.sort(key=lambda f: f["mtime"], reverse=True)
    return out


def make_handler(hub):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):  # silencia o log por requisição
            pass

        def _send(self, code, body, ctype):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            url = urlsplit(self.path)
            path = url.path
            if path in ("/", "/index.html"):
                try:
                    with open(INDEX, "rb") as f:
                        self._send(200, f.read(), "text/html; charset=utf-8")
                except OSError:
                    self._send(500, b"web/index.html nao encontrado", "text/plain")
            elif path == "/api/latest":
                with hub.cond:
                    body = hub.payload.encode()
                self._send(200, body, "application/json")
            elif path == "/api/track":
                with hub.cond:
                    body = json.dumps(list(hub.track)).encode()
                self._send(200, body, "application/json")
            elif path == "/api/logs":
                self._send(200, json.dumps(_list_logs(hub)).encode(), "application/json")
            elif path == "/api/log":
                name = (parse_qs(url.query).get("name") or [""])[0]
                fpath = _log_path(hub, name)
                if not fpath:
                    self._send(404, b"log nao encontrado", "text/plain")
                else:
                    with open(fpath, "rb") as f:
                        self._send(200, f.read(), "text/plain; charset=utf-8")
            elif path == "/events":
                self._events()
            else:
                self._send(404, b"not found", "text/plain")

        def _events(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()
            last = 0
            try:
                while True:
                    with hub.cond:
                        hub.cond.wait_for(lambda: hub.version != last, timeout=10)
                        changed = hub.version != last
                        last = hub.version
                        payload = hub.payload
                    chunk = f"data: {payload}\n\n" if changed else ": ping\n\n"
                    self.wfile.write(chunk.encode())
                    self.wfile.flush()
            except OSError:  # cliente fechou a aba
                pass

    return Handler


def start(hub, host, port, log_dir=None):
    """Sobe o servidor HTTP numa thread daemon. Retorna a URL ou None se a porta estiver ocupada."""
    hub.log_dir = log_dir
    try:
        server = ThreadingHTTPServer((host, port), make_handler(hub))
    except OSError as e:
        print(f"Aviso: dashboard web desativado ({e})")
        return None
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    shown = "localhost" if host in ("0.0.0.0", "127.0.0.1") else host
    return f"http://{shown}:{port}"
