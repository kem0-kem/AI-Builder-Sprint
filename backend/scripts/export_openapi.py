import json
import sys
from pathlib import Path

from app.main import create_app


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: python scripts/export_openapi.py OUTPUT")
    destination = Path(sys.argv[1])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(create_app().openapi(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
