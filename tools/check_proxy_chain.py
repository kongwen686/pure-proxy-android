"""Probe JVM-generated production chain wiring against desktop Xray using localhost only.

Run ProxyChainTest first, then pass --xray /path/to/xray. No real node credentials are used.
This is not an Android VPN/device test.
"""
import argparse
import contextlib
import http.server
import json
import select
import socket
import socketserver
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path


def receive(sock, count):
    result = b""
    while len(result) < count:
        part = sock.recv(count - len(result))
        if not part:
            raise EOFError()
        result += part
    return result


class ThreadedServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class Socks(socketserver.BaseRequestHandler):
    def handle(self):
        upstream = None
        try:
            self.server.connections += 1
            self.request.settimeout(10)
            version, count = receive(self.request, 2)
            methods = receive(self.request, count)
            assert version == 5 and 2 in methods, (version, methods)
            self.request.sendall(b"\x05\x02")
            version, size = receive(self.request, 2)
            username = receive(self.request, size)
            password = receive(self.request, receive(self.request, 1)[0])
            assert version == 1 and username == password == b"demo"
            self.request.sendall(b"\x01\x00")
            version, command, _, kind = receive(self.request, 4)
            assert version == 5 and command == 1
            if kind == 1:
                address = socket.inet_ntoa(receive(self.request, 4))
            elif kind == 3:
                address = receive(self.request, receive(self.request, 1)[0]).decode()
            else:
                raise ValueError("unexpected address family")
            port = struct.unpack("!H", receive(self.request, 2))[0]
            assert address == "127.0.0.1" and port == self.server.allowed_port
            self.server.requests.append((address, port))
            upstream = socket.create_connection((address, port), timeout=3)
            self.request.sendall(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00")
            while True:
                ready, _, _ = select.select([self.request, upstream], [], [], 5)
                if not ready:
                    break
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    (upstream if source is self.request else self.request).sendall(data)
        except (EOFError, OSError, AssertionError, ValueError) as error:
            self.server.errors.append(repr(error))
            return
        finally:
            if upstream:
                upstream.close()


class Website(http.server.BaseHTTPRequestHandler):
    requests = 0

    def do_GET(self):
        Website.requests += 1
        self.send_response(200)
        self.send_header("Content-Length", "10")
        self.end_headers()
        self.wfile.write(b"chain-pass")

    def log_message(self, *args):
        pass


def start_server(server):
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server.server_address[1]


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_port(number, process):
    for _ in range(50):
        if process.poll() is not None:
            raise RuntimeError("Xray exited before listening")
        try:
            with socket.create_connection(("127.0.0.1", number), timeout=.1):
                return
        except OSError:
            time.sleep(.1)
    raise RuntimeError("listener timeout")


def run(xray, directory):
    website = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Website)
    website_port = start_server(website)
    relay_port = port()
    exit_server = ThreadedServer(("127.0.0.1", 0), Socks)
    exit_server.allowed_port, exit_server.requests = website_port, []
    exit_server.errors = []
    exit_server.connections = 0
    exit_port = start_server(exit_server)
    front_server = ThreadedServer(("127.0.0.1", 0), Socks)
    front_server.allowed_port, front_server.requests = relay_port, []
    front_server.errors = []
    front_server.connections = 0
    front_port = start_server(front_server)
    mapping = {39301: relay_port, 39302: exit_port, 39303: front_port}
    results = {}
    with tempfile.TemporaryDirectory(prefix="pure-proxy-chain-") as temporary:
        temporary = Path(temporary)
        processes = []
        logs = []

        def spawn(name, config):
            config["log"] = {"loglevel": "debug"}
            filename = temporary / (name + ".json")
            filename.write_text(json.dumps(config))
            log = (temporary / (name + ".log")).open("w")
            logs.append(log)
            process = subprocess.Popen([xray, "run", "-config", str(filename)], stdout=log, stderr=log)
            processes.append(process)
            return process

        def curl(client_port):
            return subprocess.run(["curl", "--silent", "--show-error", "--noproxy", "",
                "--socks5-hostname", f"127.0.0.1:{client_port}", "--max-time", "15",
                f"http://127.0.0.1:{website_port}/"], capture_output=True)

        try:
            relay = spawn("relay", {"log": {"loglevel": "warning"}, "inbounds": [{
                "listen": "127.0.0.1", "port": relay_port, "protocol": "vless", "settings": {
                    "clients": [{"id": "00000000-0000-4000-8000-000000000001"}], "decryption": "none"}
            }], "outbounds": [{"protocol": "freedom", "settings": {
                # Xray 26.5+ blocks private destinations on a server by default. This test
                # permits only its localhost mock services; no production config is changed.
                # https://xtls.github.io/en/config/outbounds/freedom.html#finalrules
                "finalRules": [{"action": "allow", "network": "tcp", "ip": ["127.0.0.1"],
                    "port": f"{exit_port},{website_port}"}]
            }}]})
            wait_port(relay_port, relay)
            for name in ("client-single-hop", "client-two-hop", "client-three-hop"):
                source = "client-two-hop" if name == "client-single-hop" else name
                template = (directory / (source + ".json")).read_text()
                for old, new in mapping.items():
                    template = template.replace(str(old), str(new))
                config = json.loads(template)
                if name == "client-single-hop":
                    config["outbounds"] = [config["outbounds"][1]]
                    config["outbounds"][0]["tag"] = "proxy"
                client_port = port()
                config["inbounds"][0]["port"] = client_port
                client = spawn(name, config)
                wait_port(client_port, client)
                before_exit, before_front = len(exit_server.requests), len(front_server.requests)
                request = curl(client_port)
                assert request.returncode == 0 and request.stdout == b"chain-pass", (
                    name, request.returncode, request.stderr.decode(),
                    (temporary / (name + ".log")).read_text(), (temporary / "relay.log").read_text(),
                    exit_server.requests, exit_server.errors, exit_server.connections, Website.requests)
                if name == "client-single-hop":
                    assert len(exit_server.requests) == before_exit
                else:
                    assert len(exit_server.requests) > before_exit
                if name == "client-three-hop":
                    assert len(front_server.requests) > before_front
                else:
                    assert len(front_server.requests) == before_front
                results[name] = "passed: ordinary VLESS relay" if name == "client-single-hop" else \
                    "passed: authenticated SOCKS exit reached through VLESS relay"
                if name == "client-three-hop":
                    before_site = Website.requests
                    exit_server.shutdown()
                    exit_server.server_close()
                    request = curl(client_port)
                    assert request.returncode != 0
                    assert Website.requests == before_site
                    results["exit-failure"] = "passed: target received no fallback traffic"
                client.terminate()
                client.wait(timeout=5)
        finally:
            for process in processes:
                if process.poll() is None:
                    process.terminate()
                    with contextlib.suppress(subprocess.TimeoutExpired):
                        process.wait(timeout=5)
                    if process.poll() is None:
                        process.kill()
            for log in logs:
                log.close()
            for server in (website, front_server):
                server.shutdown()
                server.server_close()
            exit_server.server_close()
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--xray", required=True)
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).resolve().parents[1] /
        "V2rayNG/app/build/chain-fixtures")
    args = parser.parse_args()
    print(json.dumps(run(args.xray, args.fixtures), ensure_ascii=False, indent=2))
