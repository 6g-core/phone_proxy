from __future__ import annotations

import sys
from pathlib import Path
import zipfile


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: python3 scripts/extract_zip.py <zip_path> <dest_dir>", file=sys.stderr)
        return 2

    zip_path = Path(sys.argv[1]).resolve()
    dest_dir = Path(sys.argv[2]).resolve()
    dest_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            archive.extract(info, dest_dir)
            mode = info.external_attr >> 16
            if mode:
                extracted = dest_dir / info.filename
                try:
                    extracted.chmod(mode)
                except FileNotFoundError:
                    pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
