"""Windows-Demo-Einrichtung mit neuen Zufallsdaten; keine Zugangsdaten im Quellcode."""
import ctypes
from ctypes import wintypes
import io
import json
from pathlib import Path
import secrets
import socket
import subprocess
import tarfile
import time

NAME = "inventur-postgres-dev"
VOLUME = "inventur-postgres-data"
IMAGE = "postgres:17-alpine"
DOCKER = ["docker"]
SECRET_PATH = Path(__file__).resolve().parents[1] / ".cache" / "inventur-postgres-dev-password.dpapi"


def docker(*args, data=None):
    result = subprocess.run(DOCKER + list(args), input=data, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError("Docker operation failed: " + args[0] + "; credentials were not printed.")
    return result.stdout


class Blob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def dpapi(data, decrypt=False):
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    if decrypt:
        function = crypt.CryptUnprotectData
        function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                             ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
        ok = function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target))
    else:
        function = crypt.CryptProtectData
        function.argtypes = [ctypes.POINTER(Blob), wintypes.LPCWSTR, ctypes.c_void_p,
                             ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
        ok = function(ctypes.byref(source), "Inventur local development", None, None, None, 1,
                      ctypes.byref(target))
    if not ok:
        raise RuntimeError("DPAPI operation failed; no container was provisioned by this operation.")
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        kernel.LocalFree(ctypes.cast(target.data, ctypes.c_void_p))


def local_engine():
    if __import__("os").name != "nt":
        raise RuntimeError("Dieser DPAPI-Helfer benoetigt Windows. Andere Systeme: externe Konfiguration verwenden.")
    context = docker("context", "show").decode().strip()
    endpoint = __import__("os").environ.get("DOCKER_HOST") or docker(
        "context", "inspect", context, "--format", "{{.Endpoints.docker.Host}}").decode().strip()
    if not endpoint.startswith(("npipe:", "unix:")):
        raise RuntimeError("Nur eine lokale Docker Engine ist erlaubt; TCP/SSH-Kontexte werden abgelehnt.")
    docker("version", "--format", "{{.Server.Version}}")
    if docker("info", "--format", "{{.OSType}}").strip() != b"linux":
        raise RuntimeError("Linux-Container-Engine erforderlich.")


def check_only():
    local_engine()
    container = bool(docker("ps", "-a", "--filter", "name=^/" + NAME + "$", "--format", "{{.Names}}").strip())
    volume = VOLUME in docker("volume", "ls", "--format", "{{.Name}}").decode().splitlines()
    image = subprocess.run(DOCKER + ["image", "inspect", IMAGE, "--format", "{{.Id}}"],
                           capture_output=True, timeout=30).returncode == 0
    free = True
    try:
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            probe.bind(("127.0.0.1", 5433))
    except OSError:
        free = False
    print("Nur lesende Pruefung; keine Ressourcen oder Dateien angelegt.")
    print("Container vorhanden: " + str(container) + "; Volume vorhanden: " + str(volume))
    print("Port 5433 frei: " + str(free) + "; Image vorhanden: " + str(image))
    print("Lokale DPAPI-Datei vorhanden: " + str(SECRET_PATH.exists()))
    if container or volume or SECRET_PATH.exists():
        print("Keine automatische Wiederverwendung: --create stoppt bei bestehenden Zielressourcen.")


def main():
    local_engine()
    if docker("ps", "-a", "--filter", "name=^/" + NAME + "$", "--format", "{{.Names}}").strip():
        raise RuntimeError("Target container already exists; user decision required.")
    volumes = docker("volume", "ls", "--format", "{{.Name}}").decode().splitlines()
    if VOLUME in volumes:
        raise RuntimeError("Target volume already exists; user decision required.")
    docker("image", "inspect", IMAGE, "--format", "{{.Id}}")
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        probe.bind(("127.0.0.1", 5433))
    if SECRET_PATH.exists():
        raise RuntimeError("Encrypted credential file already exists; user decision required.")

    password = secrets.token_urlsafe(32).encode("ascii")
    encrypted = dpapi(password)
    if dpapi(encrypted, decrypt=True) != password:
        raise RuntimeError("Credential recovery check failed; provisioning stopped.")
    SECRET_PATH.parent.mkdir(parents=True, exist_ok=True)
    with SECRET_PATH.open("xb") as file:
        file.write(encrypted)
    if dpapi(SECRET_PATH.read_bytes(), decrypt=True) != password:
        raise RuntimeError("Stored credential recovery check failed; provisioning stopped.")
    print("Encrypted password stored and recovery verified; no plaintext host file.", flush=True)

    docker("volume", "create", "--label", "project=inventur", VOLUME)
    docker("create", "--name", NAME, "--label", "project=inventur",
           "--publish", "127.0.0.1:5433:5432",
           "--mount", "type=volume,source=" + VOLUME + ",target=/var/lib/postgresql/data",
           "--env", "POSTGRES_DB=inventur_dev", "--env", "POSTGRES_USER=inventur_dev",
           "--env", "POSTGRES_PASSWORD_FILE=/run/secrets/inventur-password",
           "--health-cmd", "pg_isready -U inventur_dev -d inventur_dev",
           "--health-interval", "5s", "--health-timeout", "3s",
           "--health-start-period", "10s", "--health-retries", "10", IMAGE)

    archive = docker("cp", NAME + ":/etc/passwd", "-")
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        passwd = source.extractfile(source.getmembers()[0]).read().decode()
    account = next(line.split(":") for line in passwd.splitlines() if line.startswith("postgres:"))
    uid, gid = int(account[2]), int(account[3])
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as target:
        directory = tarfile.TarInfo("run/secrets")
        directory.type = tarfile.DIRTYPE
        directory.mode = 0o755
        target.addfile(directory)
        files = {
            "inventur-password": password + b"\n",
            "inventur-pgpass": b"127.0.0.1:5432:inventur_dev:inventur_dev:" + password + b"\n"
        }
        for name, contents in files.items():
            entry = tarfile.TarInfo("run/secrets/" + name)
            entry.uid, entry.gid = uid, gid
            entry.mode = 0o600
            entry.size = len(contents)
            target.addfile(entry, io.BytesIO(contents))
    docker("cp", "-a", "-", NAME + ":/", data=stream.getvalue())
    password = None
    docker("start", NAME)
    print("Only the new Inventur container and volume were created. Waiting for health.", flush=True)

    for _ in range(60):
        state = docker("inspect", NAME, "--format", "{{.State.Status}}|{{.State.Health.Status}}").decode().strip()
        if state == "running|healthy":
            break
        if not state.startswith("running|"):
            raise RuntimeError("New container stopped; no other resource was changed.")
        time.sleep(1)
    else:
        raise RuntimeError("Health timeout; only new resources remain for inspection.")

    output = docker("exec", "--user", "postgres", "--env",
                    "PGPASSFILE=/run/secrets/inventur-pgpass", NAME,
                    "psql", "-h", "127.0.0.1", "-p", "5432", "-U", "inventur_dev",
                    "-d", "inventur_dev", "-w", "-v", "ON_ERROR_STOP=1", "-At", "-c",
                    "SELECT current_database(); SELECT current_user; SELECT 1; SHOW server_version; "
                    "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public';")
    lines = output.decode().strip().splitlines()
    if lines[:3] != ["inventur_dev", "inventur_dev", "1"] or lines[4] != "0":
        raise RuntimeError("Database verification failed; no business tables were created.")
    ports = json.loads(docker("inspect", NAME, "--format", "{{json .HostConfig.PortBindings}}"))
    if ports != {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "5433"}]}:
        raise RuntimeError("Unexpected port binding; review required.")
    print("Container: running; health: healthy; database and user verified; SELECT 1 = 1.")
    print("PostgreSQL version: " + lines[3] + "; public business tables: 0.")
    print("Binding: 127.0.0.1:5433 -> 5432; volume: " + VOLUME + ".")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Neue isolierte lokale Inventur-Datenbank; niemals bestehende Ressourcen ersetzen.")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="Nur lesende Pruefung")
    mode.add_argument("--create", action="store_true", help="Explizit neue Ressourcen anlegen; bei Bestand abbrechen")
    args = parser.parse_args()
    try:
        if args.check:
            check_only()
        else:
            main()
    except Exception:
        # Keine Docker-Ausgabe, Tracebacks, Payloads oder Zugangsdaten ausgeben.
        print("Pruefung/Einrichtung abgebrochen. Bestehende Zielressourcen, Kontext, Port, Image und lokale DPAPI-Datei pruefen; nichts loeschen.")
        raise SystemExit(1)
