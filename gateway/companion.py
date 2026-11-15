"""Oracle Companion LAN gateway and Windows console client."""
import argparse
import datetime as dt
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import sqlite3
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from dotenv import dotenv_values

try:
    import psutil
    psutil.cpu_percent(interval=None)
except Exception:
    psutil = None


def get_device_health():
    if not psutil:
        return None
    try:
        return {
            "cpuPercent": round(psutil.cpu_percent(interval=0.05), 1),
            "ramPercent": round(psutil.virtual_memory().percent, 1),
            "sampledAt": int(time.time()),
        }
    except Exception:
        return None

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ORACLE = ROOT.parent / "Oracle"
PREFIX = "/companion/v1"
TTL = 120
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                        urllib.request.HTTPSHandler(context=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)))


class Problem(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def local_json(port, path, body=None, token=None, timeout=6):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=None if body is None else json.dumps(body).encode(),
        headers=headers,
    )
    try:
        with LOCAL_HTTP.open(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            payload = json.load(error)
            message = payload.get("detail") or payload.get("message")
        except (ValueError, AttributeError):
            message = None
        raise Problem(error.code, message if isinstance(message, str) else "Oracle rejected the request.") from None
    except (OSError, ValueError):
        raise Problem(503, "The local Oracle service did not respond.") from None


class Gateway:
    def __init__(self, directory, oracle):
        self.directory = directory
        self.oracle = oracle
        self.name = socket.gethostname()
        self.generating = threading.Lock()
        directory.mkdir(parents=True, exist_ok=True)
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS codes (hash TEXT PRIMARY KEY, expires INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS devices (hash TEXT PRIMARY KEY, id TEXT NOT NULL, label TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS messages (
                    sequence INTEGER PRIMARY KEY, id TEXT UNIQUE NOT NULL, role TEXT NOT NULL,
                    text TEXT NOT NULL, reply_to TEXT UNIQUE, state TEXT NOT NULL DEFAULT 'complete'
                );
                CREATE TABLE IF NOT EXISTS actions (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, expires INTEGER NOT NULL,
                    status TEXT NOT NULL, message TEXT NOT NULL
                );
            """)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.directory / "companion.db")
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def recover(self):
        with self.db() as db:
            db.execute("UPDATE messages SET state='failed' WHERE state='pending'")
            db.execute("UPDATE actions SET status='unknown', message=? WHERE status='confirming'",
                       ("Windows outcome could not be verified after restart.",))

    def issue(self):
        code = secrets.token_urlsafe(24)
        with self.db() as db:
            db.execute("DELETE FROM codes")
            db.execute("INSERT INTO codes VALUES (?, ?)", (digest(code), int(time.time()) + TTL))
        return code

    def pair(self, code, label):
        credential, device = secrets.token_urlsafe(32), str(uuid.uuid4())
        with self.db() as db:
            used = db.execute("DELETE FROM codes WHERE hash=? AND expires>?", (digest(code), time.time()))
            if used.rowcount != 1:
                raise Problem(403, "Pairing code expired or already used. Create a new one on Windows.")
            db.execute("INSERT INTO devices VALUES (?, ?, ?)", (digest(credential), device, label[:60]))
        return {"token": credential, "pc": self.name}

    def device(self, credential):
        with self.db() as db:
            row = db.execute("SELECT id FROM devices WHERE hash=?", (digest(credential),)).fetchone()
        if not row:
            raise Problem(401, "Pair this phone again from Windows.")
        return row["id"]

    def capabilities(self):
        try:
            token = (self.oracle / "data/dev/api-token").read_text(encoding="utf-8").strip()
        except OSError:
            raise Problem(503, "Start Oracle and prepare its local API credential.") from None
        if not token:
            raise Problem(503, "Oracle local API credential is empty.")
        return token, local_json(8000, "/api/v1/capabilities", token=token)

    def ollama_port(self):
        values = dotenv_values(self.oracle / "backend/.env")
        configured = os.environ.get("OLLAMA_BASE_URL", values.get("OLLAMA_BASE_URL") or "http://127.0.0.1:11434")
        try:
            url = urlsplit(configured)
            if (url.scheme == "http" and url.hostname in {"127.0.0.1", "localhost"}
                    and not (url.username or url.password or url.query or url.fragment)
                    and not url.path.strip("/")):
                return url.port if url.port is not None else 80
        except ValueError:
            pass
        raise Problem(503, "Companion chat requires a local Ollama HTTP address.")

    def status(self):
        try:
            _, capabilities = self.capabilities()
        except Problem:
            return {"pc": self.name, "oracle": False, "model": False, "action": False}
        model = capabilities.get("model", {})
        try:
            tags = local_json(self.ollama_port(), "/api/tags", timeout=2)
            name = model.get("name")
            ready = model.get("processing") == "local" and bool(name) and any(
                item.get("name") in (name, f"{name}:latest") for item in tags.get("models", [])
            )
        except Problem:
            ready = False
        health = get_device_health()
        res = {
            "pc": self.name, "oracle": True, "model": ready, "action": True,
            "modelName": model.get("name") if ready else None,
            "chatStreaming": True,
            "replyModes": ready and name.split(":", 1)[0].rsplit("/", 1)[-1].lower() == "qwen3"
        }
        if health:
            res["deviceHealth"] = health
        return res

    def history(self):
        with self.db() as db:
            rows = db.execute(
                "SELECT id,role,text,state FROM messages ORDER BY sequence DESC LIMIT 100"
            ).fetchall()
        return {"messages": [dict(row) for row in reversed(rows)]}

    def send(self, identity, text, mode="quick", emit=None):
        if mode not in ("quick", "deep"):
            raise Problem(400, "Invalid reply mode.")
        if not self.generating.acquire(blocking=False):
            raise Problem(409, "Oracle is answering another message. Try again shortly.")
        try:
            with self.db() as db:
                db.execute("BEGIN IMMEDIATE")
                existing = db.execute("SELECT role,text,state FROM messages WHERE id=?", (identity,)).fetchone()
                if existing and (existing["role"] != "user" or existing["text"] != text):
                    raise Problem(409, "This message ID already belongs to another message.")
                if existing and existing["state"] == "complete":
                    return self.history()
                if db.execute("SELECT 1 FROM messages WHERE state='pending'").fetchone():
                    raise Problem(409, "Oracle is answering another message. Try again shortly.")
                rows = db.execute(
                    "SELECT role,text FROM messages WHERE state='complete' ORDER BY sequence DESC LIMIT 8"
                ).fetchall()
                db.execute("INSERT INTO messages(id,role,text,state) VALUES (?,'user',?,'pending') "
                           "ON CONFLICT(id) DO UPDATE SET state='pending'", (identity, text))
            try:
                _, capabilities = self.capabilities()
                model = capabilities.get("model", {})
                if model.get("processing") != "local" or not model.get("name"):
                    raise Problem(503, "Configure a local Ollama model in Oracle to use companion chat.")
                context = [{"role": "assistant" if row["role"] == "model" else "user",
                            "content": row["text"][:4000]} for row in reversed(rows)]
                context.append({"role": "user", "content": text})
                payload = {"model": model["name"], "stream": False, "messages": context,
                           "options": {"num_predict": 1024 if mode == "deep" else 512}}
                if model["name"].split(":", 1)[0].rsplit("/", 1)[-1].lower() == "qwen3":
                    payload["think"] = mode == "deep"
                partial = ""
                if emit is None:
                    result = local_json(self.ollama_port(), "/api/chat", payload, timeout=80)
                    reply = result.get("message", {}).get("content", "").strip()
                else:
                    payload["stream"] = True
                    request = urllib.request.Request(
                        f"http://127.0.0.1:{self.ollama_port()}/api/chat",
                        data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
                    with LOCAL_HTTP.open(request, timeout=80) as upstream:
                        for line in upstream:
                            event = json.loads(line)
                            if event.get("error"):
                                raise Problem(502, "Ollama could not finish the reply.")
                            delta = event.get("message", {}).get("content", "")[:12000-len(partial)]
                            if delta:
                                partial += delta
                                emit({"delta": delta})
                            if event.get("done"):
                                break
                        else:
                            raise Problem(502, "The model stream ended early.")
                    reply = partial.strip()
                if not reply:
                    raise Problem(502, "The local model returned an empty answer.")
                with self.db() as db:
                    db.execute("INSERT INTO messages(id,role,text,reply_to) VALUES (?,'model',?,?) ON CONFLICT(reply_to) DO UPDATE SET text=excluded.text,state='complete'",
                               (str(uuid.uuid4()), reply[:12000], identity))
                    db.execute("UPDATE messages SET state='complete' WHERE id=?", (identity,))
            except Exception:
                with self.db() as db:
                    db.execute("UPDATE messages SET state='failed' WHERE id=?", (identity,))
                    if locals().get("partial"):
                        db.execute("INSERT INTO messages(id,role,text,reply_to,state) VALUES (?,'model',?,?,'partial') "
                                   "ON CONFLICT(reply_to) DO UPDATE SET text=excluded.text,state='partial'",
                                   (str(uuid.uuid4()), partial, identity))
                raise
            return self.history()
        finally:
            self.generating.release()

    def propose(self, device):
        token, _ = self.capabilities()
        action = local_json(8000, "/api/v1/actions/propose", {"app_id": "vscode"}, token)
        with self.db() as db:
            db.execute("INSERT INTO actions VALUES (?,?,?,'pending',?)",
                       (action["id"], device, int(time.time()) + action["expires_in_seconds"],
                        "Confirm Open VS Code."))
        return {"id": action["id"], "pc": self.name}

    def confirm(self, device, identity):
        token, _ = self.capabilities()
        with self.db() as db:
            updated = db.execute("UPDATE actions SET status='confirming', message=? "
                                 "WHERE id=? AND owner=? AND status='pending' AND expires>?",
                                 ("Waiting for Windows.", identity, device, time.time()))
            if updated.rowcount != 1:
                raise Problem(409, "This action expired or was already submitted. Check Activity.")
        try:
            result = local_json(8000, f"/api/v1/actions/{identity}/confirm", {}, token)
            status = result.get("status")
            if status == "launch_requested":
                message = "Windows accepted the VS Code launch request. Window visibility is unverified."
            else:
                status, message = "unknown", "Windows returned an unrecognized launch result."
        except Problem as error:
            status = "unknown" if error.status >= 500 else "failed"
            message = error.message
        with self.db() as db:
            db.execute("UPDATE actions SET status=?, message=? WHERE id=?", (status, message, identity))
        return {"status": status, "message": message}

    def activity(self, device):
        with self.db() as db:
            rows = db.execute("SELECT status,message FROM actions WHERE owner=? AND status!='pending' "
                              "ORDER BY rowid DESC LIMIT 25", (device,)).fetchall()
        return {"events": [dict(row) for row in rows]}


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(8)
        super().setup()

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.handle_api()

    def do_POST(self):
        self.handle_api()

    def body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= 24_000:
                raise Problem(413, "Request is too large.")
            result = json.loads(self.rfile.read(length) or b"{}")
            if not isinstance(result, dict):
                raise ValueError()
            return result
        except ValueError:
            raise Problem(400, "Expected a JSON object.") from None

    def route(self):
        gateway = self.server.gateway
        if not self.path.startswith(PREFIX + "/"):
            raise Problem(404, "Unknown operation.")
        path = self.path[len(PREFIX):]
        body = self.body() if self.command == "POST" else {}
        if self.command == "POST" and path == "/pair":
            return gateway.pair(field(body, "code", 64), field(body, "label", 60))
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer ") or len(header) > 128:
            raise Problem(401, "Pair this phone first.")
        device = gateway.device(header[7:])
        if self.command == "GET":
            if path == "/status":
                return gateway.status()
            if path == "/chat":
                return gateway.history()
            if path == "/activity":
                return gateway.activity(device)
        else:
            if path == "/chat":
                try:
                    identity = str(uuid.UUID(field(body, "id", 36)))
                except ValueError:
                    raise Problem(400, "Invalid message ID.") from None
                text = field(body, "text", 4000)
                mode = body.get("mode", "quick")
                if body.get("stream") is True:
                    return self.stream_chat(gateway, identity, text, mode)
                return gateway.send(identity, text, mode)
            if path == "/actions/propose":
                return gateway.propose(device)
            if path == "/actions/confirm":
                return gateway.confirm(device, field(body, "id", 64))
            if path == "/unpair":
                with gateway.db() as db:
                    db.execute("DELETE FROM devices WHERE id=?", (device,))
                return {}
        raise Problem(404, "Unknown operation.")

    def stream_chat(self, gateway, identity, text, mode):
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        def emit(event):
            self.wfile.write(json.dumps(event).encode() + b"\n")
            self.wfile.flush()

        try:
            result = gateway.send(identity, text, mode, emit)
            emit({"history": result})
        except OSError:
            return None
        except Problem as error:
            emit({"error": error.message})
        except Exception:
            emit({"error": "Companion could not finish the reply."})
        return None

    def handle_api(self):
        try:
            status, data = 200, self.route()
            if data is None:
                return
        except Problem as error:
            status, data = error.status, {"message": error.message}
        except Exception as error:
            print(f"Companion request failed: {type(error).__name__}", flush=True)
            status, data = 500, {"message": "Companion could not complete the request."}
        payload = json.dumps(data).encode()
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except OSError:
            pass


def field(body, key, limit):
    value = body.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise Problem(400, f"Invalid {key}.")
    return value.strip()


def certificate(directory):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    cert, key = directory / "companion.pem", directory / "companion-key.pem"
    if not cert.exists() or not key.exists():
        private = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Oracle Companion")])
        now = dt.datetime.now(dt.timezone.utc)
        signed = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                  .public_key(private.public_key()).serial_number(x509.random_serial_number())
                  .not_valid_before(now - dt.timedelta(days=1)).not_valid_after(now + dt.timedelta(days=365))
                  .sign(private, hashes.SHA256()))
        key.write_bytes(private.private_bytes(serialization.Encoding.PEM,
                        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        cert.write_bytes(signed.public_bytes(serialization.Encoding.PEM))
    return cert, key


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--oracle", type=Path, default=DEFAULT_ORACLE)
    parser.add_argument("--data", type=Path, default=Path(os.environ.get("LOCALAPPDATA", ROOT)) / "OracleCompanion")
    commands = parser.add_subparsers(dest="command", required=True)
    serve = commands.add_parser("serve")
    serve.add_argument("--address", required=True, type=ipaddress.IPv4Address)
    serve.add_argument("--port", type=int, default=8443)
    commands.add_parser("devices")
    revoke = commands.add_parser("revoke")
    revoke.add_argument("id")
    commands.add_parser("history")
    send = commands.add_parser("chat")
    send.add_argument("text")
    args = parser.parse_args()
    gateway = Gateway(args.data, args.oracle)
    if args.command == "devices":
        with gateway.db() as db:
            for row in db.execute("SELECT id,label FROM devices"):
                print(row["id"], row["label"])
    elif args.command == "revoke":
        with gateway.db() as db:
            changed = db.execute("DELETE FROM devices WHERE id=?", (args.id,)).rowcount
        print("Device revoked." if changed else "Device ID not found.")
    elif args.command == "history":
        for row in gateway.history()["messages"]:
            print(f"{row['role']} ({row['state']}): {row['text']}")
    elif args.command == "chat":
        try:
            result = gateway.send(str(uuid.uuid4()), field({"text": args.text}, "text", 4000))
            print(result["messages"][-1]["text"])
        except Problem as error:
            parser.exit(1, error.message + "\n")
    else:
        if not any(args.address in ipaddress.ip_network(network) for network in
                   ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")):
            parser.error("Use a private Windows IPv4 address.")
        cert, key = certificate(args.data)
        fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, key)
        with ThreadingHTTPServer((str(args.address), args.port), Handler) as server:
            server.gateway = gateway
            gateway.recover()
            original = server.get_request
            def accept():
                connection, address = original()
                connection.settimeout(5)
                try:
                    return context.wrap_socket(connection, server_side=True, do_handshake_on_connect=False), address
                except OSError:
                    connection.close()
                    raise
            server.get_request = accept
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            import qrcode
            print(f"Oracle Companion listening on {args.address}:{args.port}.")
            try:
                while True:
                    payload = json.dumps({"v": 1, "host": str(args.address), "port": args.port,
                                          "pin": fingerprint, "pc": gateway.name,
                                          "code": gateway.issue()}, separators=(",", ":"))
                    qr = qrcode.QRCode(border=2)
                    qr.add_data(payload)
                    qr.print_ascii(tty=False)
                    print("Pairing text (valid for two minutes):\n" + payload)
                    input("Press Enter to issue a new code. Ctrl+C exits. ")
            except (KeyboardInterrupt, EOFError):
                pass
            finally:
                with gateway.db() as db:
                    db.execute("DELETE FROM codes")
                server.shutdown()


if __name__ == "__main__":
    run()
