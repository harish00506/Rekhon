#!/usr/bin/env python3
"""Install the on-device LLM weights onto a device, from the open-source repository they live in.

Why
----
ADR-0079 put a real on-device model behind `LlmEngine`, and the weights are 521 MB of Apache-2.0
licensed Qwen2.5-0.5B-Instruct. They cannot ship in the APK (Play's limit is 200 MB) and the app
is forbidden from downloading them itself: a core path may not make a network call (P-04), and a
finance app that reaches out on its own is exactly what P-01 promises it will not do. So the file
is installed deliberately, by a person, with this script — and `LlmModelStore` verifies that what
arrived is the published model before a single byte is loaded.

What
----
Downloads `Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` from Hugging Face, checks its
length and SHA-256 against the manifest this build was written against, and pushes it into the
app's private model directory with `adb`. Each step can be run on its own: `--download-only` to
fetch it on a machine with a connection, `--push-only` to install a file you already have.

Result
------
A verified model file at `/data/data/<package>/files/models/<name>.task`, after which the chat
assistant answers through the neural verbaliser instead of the template one. Prints what it did
and exits non-zero if anything did not match.

Changed: 2026-10-10 - created for ADR-0079.

Input
-----
CLI flags (see `parse_args`). Output: progress on stdout, exit code 0 on success, 1 on any failure.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

# The published model, restated from LlmModelManifest.PUBLISHED. ManifestDriftTest keeps the two
# honest: if this block and the Kotlin disagree, the Kotlin wins and this is what is wrong.
REPO = "litert-community/Qwen2.5-0.5B-Instruct"
FILE_NAME = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
SIZE_BYTES = 546_660_344
SHA_256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2"
LICENCE = "Apache-2.0"

APPLICATION_ID = "com.aicfo.app"
DEVICE_DIRECTORY = "files/models"
BLOCK_BYTES = 1 << 20


def download_url(repo: str = REPO, file_name: str = FILE_NAME) -> str:
    """Build the direct download URL for a file in a Hugging Face model repository.

    Why: the URL shape is the one thing here that is worth pinning in a test - a wrong path gives
    an HTML error page that is 200 OK and would be "downloaded" successfully.
    Result: the `resolve/main` URL, which serves the LFS object itself.
    Input: `repo` - "org/name"; `file_name` - the file inside it. Output: the URL as a string.
    """
    return f"https://huggingface.co/{repo}/resolve/main/{file_name}?download=true"


def digest_of(path: Path, block_bytes: int = BLOCK_BYTES) -> str:
    """Compute a file's SHA-256 without reading it all into memory.

    Why: the file is half a gigabyte, and this is the same check `LlmModelStore` makes on the
    device - doing it here means a bad download is caught on the machine that can retry it.
    Result: lower-case hex digest.
    Input: `path`; `block_bytes` - read size. Output: the digest as a string.
    """
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(block_bytes), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(path: Path, size_bytes: int = SIZE_BYTES, sha256: str = SHA_256) -> list[str]:
    """Check a local file against the manifest.

    Why: two checks rather than one, because they fail differently and the user's next move
    differs too - a wrong length is a stopped download to resume, a wrong digest is a file to
    throw away.
    Result: a list of human-readable problems; empty means the file is the published model.
    Input: `path`; `size_bytes`; `sha256`. Output: list of strings.
    """
    problems: list[str] = []
    if not path.is_file():
        return [f"{path} does not exist"]
    actual_size = path.stat().st_size
    if actual_size != size_bytes:
        problems.append(f"length is {actual_size} bytes, expected {size_bytes} (a stopped download?)")
        return problems
    actual_digest = digest_of(path)
    if actual_digest != sha256:
        problems.append(f"SHA-256 is {actual_digest}, expected {sha256}")
    return problems


def push_command(path: Path, application_id: str = APPLICATION_ID) -> list[list[str]]:
    """Build the adb commands that put the file where the app looks for it.

    Why: app-private storage is not writable by `adb push` directly on a user build, so the file
    goes to a temporary path first and `run-as` moves it in. Returning the commands rather than
    running them is what makes this testable at all.
    Result: the commands, in order.
    Input: `path` - the local file; `application_id` - the app's package. Output: list of argv lists.
    """
    staged = f"/data/local/tmp/{path.name}"
    target = f"{DEVICE_DIRECTORY}/{path.name}"
    return [
        ["adb", "push", str(path), staged],
        ["adb", "shell", "run-as", application_id, "mkdir", "-p", DEVICE_DIRECTORY],
        ["adb", "shell", f"run-as {application_id} sh -c 'cat {staged} > {target}'"],
        ["adb", "shell", "rm", staged],
    ]


def download(destination: Path) -> None:
    """Fetch the model into `destination`, reporting progress.

    Why: 521 MB with no output looks like a hang, and the people running this are running it once.
    Result: the file on disk; raises on a transport failure.
    Input: `destination` - where to write. Output: none.
    """
    url = download_url()
    print(f"Downloading {FILE_NAME}\n  from {url}\n  licence {LICENCE}")
    with urllib.request.urlopen(url) as response, destination.open("wb") as handle:  # noqa: S310
        written = 0
        while block := response.read(BLOCK_BYTES):
            handle.write(block)
            written += len(block)
            print(f"\r  {written / SIZE_BYTES:6.1%}", end="", flush=True)
    print()


def run(commands: list[list[str]]) -> None:
    """Run each command, stopping at the first failure.

    Result: none; raises `SystemExit(1)` with the failing command named.
    Input: `commands` - argv lists. Output: none.
    """
    for command in commands:
        print("  " + " ".join(command))
        if subprocess.run(command, check=False).returncode != 0:  # noqa: S603
            sys.exit(f"failed: {' '.join(command)}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Read the CLI flags.

    Result: the parsed arguments. Input: `argv` - for tests. Output: `argparse.Namespace`.
    """
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--file", type=Path, default=Path(FILE_NAME), help="local path for the weights")
    parser.add_argument("--download-only", action="store_true", help="fetch and verify, do not push")
    parser.add_argument("--push-only", action="store_true", help="verify and push an existing file")
    parser.add_argument("--application-id", default=APPLICATION_ID, help="the app's package name")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """Download, verify and install, according to the flags.

    Result: 0 when the model is where it belongs, 1 when a check failed.
    Input: `argv`. Output: the exit code.
    """
    args = parse_args(argv)
    if not args.push_only and not args.file.is_file():
        if shutil.which("curl") is None:
            download(args.file)
        else:
            run([["curl", "-L", "--fail", "-o", str(args.file), download_url()]])

    problems = verify(args.file)
    if problems:
        print(f"FAIL: {args.file} is not the published model")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"OK: {args.file} matches the manifest ({SIZE_BYTES} bytes, {LICENCE})")

    if args.download_only:
        print("Not pushing (--download-only).")
        return 0

    print(f"Installing into {args.application_id}:")
    run(push_command(args.file, args.application_id))
    print("Done. The assistant will use the on-device model on its next question.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
