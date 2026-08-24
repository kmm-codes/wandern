"""Serve APK artifacts with Android's package MIME type.

Python's MIME database differs between machines and can otherwise expose APKs as
generic binary downloads. Android then offers file managers instead of its
package installer.
"""

from __future__ import annotations

import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


APK_MIME_TYPE = "application/vnd.android.package-archive"


class ApkRequestHandler(SimpleHTTPRequestHandler):
    def guess_type(self, path: str) -> str:
        if path.lower().endswith(".apk"):
            return APK_MIME_TYPE
        return super().guess_type(path)

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True)
    parser.add_argument("--port", type=int, default=8876)
    parser.add_argument("--bind", default="127.0.0.1")
    arguments = parser.parse_args()

    handler = partial(ApkRequestHandler, directory=arguments.directory)
    server = ThreadingHTTPServer((arguments.bind, arguments.port), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
