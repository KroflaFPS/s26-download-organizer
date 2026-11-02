"""CI guard: KSP must reproduce the checked-in journal schemas exactly."""
from pathlib import Path
import subprocess

schemas = Path("app/schemas")
if not list(schemas.rglob("*.json")):
    raise SystemExit("Room schema export is missing")
subprocess.run(["git", "diff", "--exit-code", "--", str(schemas)], check=True)
new = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", str(schemas)], text=True)
if new.strip():
    raise SystemExit("Unversioned Room schema export; review schema and migration policy before committing:\n" + new)
