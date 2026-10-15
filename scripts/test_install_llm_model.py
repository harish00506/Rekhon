#!/usr/bin/env python3
"""The model installer's decisions, tested without a network or a device (ADR-0079).

Why:  this script is the only way the weights get onto a phone, and its two interesting failure
      modes both pass silently. A wrong URL serves an HTML error page that downloads perfectly
      well; a half-finished download is a file that exists. Both must be caught here rather than
      inside the native library on the device.

      So the URL, the digest, the verification verdict and the adb commands are pure functions
      over small fixtures, and these tests pin them.
What: the URL shape, the manifest checks, and the push sequence.
Result: a bad file never reaches the device, and the manifest in this script cannot drift from the
        Kotlin one without `ManifestDriftTest` saying so.

Changelog: 2026-10-10 — Created for ADR-0079.

Run with `python3 -m unittest discover -s scripts -p 'test_*.py'`, which is what `scriptTests` does.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from install_llm_model import (
    FILE_NAME,
    SHA_256,
    SIZE_BYTES,
    digest_of,
    download_url,
    parse_args,
    push_command,
    verify,
)


class DownloadUrlTest(unittest.TestCase):
    """The URL must point at the LFS object, not at a web page about it."""

    def test_it_resolves_the_file_on_the_main_branch(self) -> None:
        url = download_url("litert-community/Qwen2.5-0.5B-Instruct", "model.task")
        self.assertEqual(
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/model.task?download=true",
            url,
        )

    def test_the_default_is_the_published_model(self) -> None:
        self.assertIn(FILE_NAME, download_url())
        self.assertIn("litert-community", download_url())


class VerifyTest(unittest.TestCase):
    """What counts as the published model, and what each failure tells the user to do."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "model.task"

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_a_missing_file_is_reported_as_missing(self) -> None:
        problems = verify(self.path)
        self.assertEqual(1, len(problems))
        self.assertIn("does not exist", problems[0])

    def test_a_short_file_is_reported_as_a_stopped_download(self) -> None:
        self.path.write_bytes(b"abc")
        problems = verify(self.path, size_bytes=10, sha256="irrelevant")
        self.assertIn("stopped download", problems[0])

    def test_the_right_length_with_the_wrong_bytes_is_reported_as_a_digest_mismatch(self) -> None:
        self.path.write_bytes(b"abc")
        problems = verify(self.path, size_bytes=3, sha256="0" * 64)
        self.assertIn("SHA-256", problems[0])

    def test_a_matching_file_has_no_problems(self) -> None:
        self.path.write_bytes(b"abc")
        self.assertEqual([], verify(self.path, size_bytes=3, sha256=digest_of(self.path)))

    def test_the_digest_is_the_standard_one(self) -> None:
        self.path.write_bytes(b"abc")
        expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        self.assertEqual(expected, digest_of(self.path))

    def test_the_published_manifest_is_a_manifest(self) -> None:
        self.assertEqual(64, len(SHA_256))
        self.assertTrue(SHA_256.islower())
        self.assertGreater(SIZE_BYTES, 100_000_000)
        self.assertTrue(FILE_NAME.endswith(".task"))


class PushCommandTest(unittest.TestCase):
    """The file has to land inside app-private storage, which `adb push` cannot write directly."""

    def test_it_stages_then_moves_then_cleans_up(self) -> None:
        commands = push_command(Path("/tmp/model.task"), "com.example.app")
        self.assertEqual(4, len(commands))
        self.assertEqual(["adb", "push", "/tmp/model.task", "/data/local/tmp/model.task"], commands[0])
        self.assertIn("run-as", commands[1])
        self.assertIn("files/models/model.task", commands[2][-1])
        self.assertEqual(["adb", "shell", "rm", "/data/local/tmp/model.task"], commands[3])

    def test_it_uses_the_application_id_it_was_given(self) -> None:
        commands = push_command(Path("model.task"), "com.aicfo.app.debug")
        self.assertTrue(all("com.aicfo.app.debug" in " ".join(command) for command in commands[1:3]))


class ParseArgsTest(unittest.TestCase):
    """The three ways this is run: whole, download-only, push-only."""

    def test_the_default_is_download_verify_and_push(self) -> None:
        args = parse_args([])
        self.assertFalse(args.download_only)
        self.assertFalse(args.push_only)
        self.assertEqual(Path(FILE_NAME), args.file)

    def test_each_half_can_be_run_alone(self) -> None:
        self.assertTrue(parse_args(["--download-only"]).download_only)
        self.assertTrue(parse_args(["--push-only", "--file", "/tmp/m.task"]).push_only)
        self.assertEqual(Path("/tmp/m.task"), parse_args(["--file", "/tmp/m.task"]).file)


if __name__ == "__main__":
    unittest.main()
