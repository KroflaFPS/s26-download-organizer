"""Create ONLY known synthetic fixtures in a new local directory; never user Downloads.
Run in a private Codespace, then deliberately download/place the two .bin files in
Download/S26_Organizer_Test_Input using My Files. This tool never installs APKs.
"""
from pathlib import Path
import argparse

def create_fixtures(directory):
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    with (directory / "s26-test-empty.bin").open("xb"):
        pass
    with (directory / "s26-test-1MiB.bin").open("xb") as stream:
        # Exact reproducible byte pattern, no personal data or network.
        for start in range(0, 1024 * 1024, 64 * 1024):
            stream.write(bytes((start + index) % 251 for index in range(64 * 1024)))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("new_directory")
    create_fixtures(parser.parse_args().new_directory)
