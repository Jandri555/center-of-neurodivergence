#!/usr/bin/env python3
"""Reproducible Android ARM64 OpenJDK 21 APK preparation.

Downloads the selected Amethyst JRE 21 archive, verifies the published SHA-256,
and splits its *Android native ELF libraries* from the Java runtime image.
Output:
  build/generated/java21/jniLibs/arm64-v8a/lib*.so
  build/generated/java21/jniLibs/arm64-v8a/libnm.so (opaque JIMAGE data)
  build/generated/java21/assets/neurojre21/conf, legal, ...
No external Java code is executed while processing the archive.
"""
import hashlib
import io
import os
import pathlib
import posixpath
import shutil
import sys
import tarfile
import urllib.request

VERSION = "21-neurocenter-20261120-icholder"
# Reproducible pinned sha256 of patched HotSpot 21 archive from Actions run 44728572327.
EXPECTED_SHA256 = "6d9ea013b0704f7d81008094331ca0886cda86e2f5b49fb03648ef29a7729b4d"
LIMIT_ARCHIVE = 70 * 1024 * 1024
LIMIT_UNPACKED = 800 * 1024 * 1024
LIMIT_MEMBERS = 16000

def digest_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def fetch(cache):
    # For CI, Actions downloads the verified patched JRE artifact first.
    # Local builds can point to that archive explicitly or use the cache.
    override = os.environ.get("NEUROCENTER_JRE21_ARCHIVE", "").strip()
    archive = pathlib.Path(override).resolve() if override else cache
    if not archive.is_file():
        raise FileNotFoundError(
            "Falta OpenJDK 21 parcheado. Descarga el artefacto "
            "'neurocenter-openjdk21-arm64-icholder-experimental' de GitHub Actions "
            "(run 44728572327) y establece NEUROCENTER_JRE21_ARCHIVE "
            "con la ruta al tar.xz, o guárdalo en: " + str(cache))
    observed = digest_file(archive)
    if observed != EXPECTED_SHA256:
        raise ValueError("JRE21 parcheado SHA-256 incorrecto; esperado "
                         + EXPECTED_SHA256 + "; obtenido " + observed)
    print("Verified patched OpenJDK 21 Android ARM64 archive", flush=True)
    return archive

def safe_path(value):
    value = value.removeprefix("./")
    while value.startswith("./"):
        value = value[2:]
    normal = posixpath.normpath(value)
    if value.startswith("/") or value.startswith("\\") or normal in (".", "..") \
            or normal.startswith("../") or "\\" in value:
        raise ValueError("Unsafe archive path: " + value)
    return normal

def members_by_name(tar):
    members = tar.getmembers()
    if len(members) > LIMIT_MEMBERS:
        raise ValueError("Too many JRE archive entries")
    mapping = {}
    for member in members:
        # tar.xz puede incluir una entrada "directory: ." o "./" para su raíz.
        # Es normal y segura; no corresponde a ningún archivo del runtime.
        if member.name in (".", "./") and member.isdir():
            continue
        path = safe_path(member.name)
        if path in mapping:
            raise ValueError("Duplicate JRE archive entry: " + path)
        if member.isdev() or member.isfifo():
            raise ValueError("Unsupported device or FIFO in runtime")
        mapping[path] = member
    return mapping

def file_bytes(tar, mapping, name, seen=None):
    if seen is None:
        seen = set()
    if name in seen:
        raise ValueError("Archive link cycle")
    seen.add(name)
    m = mapping[name]
    if m.issym():
        next_name = safe_path(posixpath.join(posixpath.dirname(name), m.linkname))
        if next_name not in mapping:
            raise ValueError("Broken archive link: " + name)
        return file_bytes(tar, mapping, next_name, seen)
    if m.islnk():
        next_name = safe_path(m.linkname)
        if next_name not in mapping:
            raise ValueError("Broken archive hard link: " + name)
        return file_bytes(tar, mapping, next_name, seen)
    if not m.isfile() or m.size > LIMIT_UNPACKED:
        raise ValueError("Unsupported/oversize archive file: " + name)
    stream = tar.extractfile(m)
    if stream is None:
        raise ValueError("Unreadable archive file: " + name)
    return stream

def patch_hotspot_module_paths(content):
    """Experimental, length-preserving patch of only OpenJDK's two boot-image
    format strings. Never alter the ELF layout or substitute untrusted paths.

    The APK package installer extracts libnm.so as an opaque regular file
    alongside the real ELF libraries; HotSpot then expects that image at
    <nativeLibraryDir>/libnm.so, instead of <java.home>/lib/modules.
    We fail the build if the vendor binary's layout changes.
    """
    replacements = (
        (b"%/lib/modules\x00", b"%/libnm.so\x00"),
        (b"%s%slib%smodules\x00", b"%s%slibnm.so\x00"),
    )
    for before, after in replacements:
        occurrences = content.count(before)
        if occurrences != 1:
            raise ValueError("Unsupported libjvm.so layout: expected exactly "
                             "one %r, found %d" % (before, occurrences))
        content = content.replace(before, after + b"\x00" * (len(before) - len(after)))
    return content

def prepare(archive, output):
    assets = output / "assets" / "neurojre21"
    libs = output / "jniLibs" / "arm64-v8a"
    if output.exists():
        shutil.rmtree(output)
    assets.mkdir(parents=True)
    libs.mkdir(parents=True)

    total = 0
    copied_libs = {}
    included_paths = set()
    with tarfile.open(archive, mode="r:xz") as tar:
        mapping = members_by_name(tar)
        images = [n for n, m in mapping.items() if n.endswith("/lib/modules")
                  or n == "lib/modules"]
        images = [n for n in images if mapping[n].isfile()]
        if len(images) != 1:
            raise ValueError("Expected exactly one OpenJDK lib/modules image; found "
                             + repr(images))
        root = images[0][:-len("lib/modules")].rstrip("/")
        print("OpenJDK archive runtime root:", root or "<root>")
        for name in sorted(mapping):
            member = mapping[name]
            if member.isdir():
                continue
            if not member.isfile() and not member.issym() and not member.islnk():
                raise ValueError("Unsupported archive member: " + name)
            if root and not name.startswith(root + "/"):
                continue
            relative = name[len(root) + 1:] if root else name
            relative = safe_path(relative)
            if relative in included_paths:
                raise ValueError("Duplicate relative runtime path: " + relative)
            included_paths.add(relative)

            # No runnable executables are unpacked into Android's writable app
            # data directory (W^X policy for apps targeting Android 10+).
            if relative.startswith(("bin/", "include/", "man/")):
                continue
            # Install the signed JIMAGE as a non-executable, opaque data file
            # in nativeLibraryDir, NOT in private filesDir. Android will unpack
            # lib*.so entries itself when legacy JNI packaging is enabled.
            if relative == "lib/modules":
                destination = libs / "libnm.so"
                with file_bytes(tar, mapping, name) as source, open(destination, "wb") as out:
                    header = source.read(4)
                    if header != b"\xDA\xDA\xFE\xCA":
                        raise ValueError("Invalid JIMAGE magic in lib/modules")
                    out.write(header)
                    total += len(header)
                    while block := source.read(1024 * 1024):
                        total += len(block)
                        if total > LIMIT_UNPACKED:
                            raise ValueError("JIMAGE exceeds unpack size limit")
                        out.write(block)
                continue
            if relative.endswith(".so"):
                basename = posixpath.basename(relative)
                if not (basename.startswith("lib") and basename.endswith(".so")):
                    raise ValueError("Unexpected native ELF basename: " + basename)
                dest = libs / basename
                with file_bytes(tar, mapping, name) as source:
                    content = source.read(LIMIT_UNPACKED + 1)
                if len(content) > LIMIT_UNPACKED or not content.startswith(b"\x7fELF"):
                    raise ValueError("Native library is not ELF: " + relative)
                if basename == "libjvm.so":
                    if b"NEUROCENTER_JRE_HOME" not in content:
                        raise ValueError("OpenJDK JRE does not contain required NeuroCenter source patch")
                    content = patch_hotspot_module_paths(content)
                native_digest = hashlib.sha256(content).hexdigest()
                if basename in copied_libs:
                    if copied_libs[basename] != native_digest:
                        raise ValueError("Conflicting libraries sharing name: " + basename)
                else:
                    dest.write_bytes(content)
                    copied_libs[basename] = native_digest
                total += len(content)
                continue
            destination = assets.joinpath(*relative.split("/"))
            if not destination.resolve().is_relative_to(assets.resolve()):
                raise ValueError("Archive traversal attempt: " + name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with file_bytes(tar, mapping, name) as source, open(destination, "wb") as dest:
                while block := source.read(1024 * 1024):
                    total += len(block)
                    if total > LIMIT_UNPACKED:
                        raise ValueError("Expanded JRE exceeds size limit")
                    dest.write(block)

    if not (libs / "libnm.so").is_file():
        raise ValueError("No libnm.so JIMAGE data in prepared native libraries")
    for required in ("libjvm.so", "libjava.so", "libjli.so"):
        if required not in copied_libs:
            raise ValueError("Missing critical native library: " + required)
    (assets / "source.sha256").write_text(EXPECTED_SHA256 + "\n", encoding="ascii")
    print("Prepared JRE 21: %d native libraries + APK-hosted JIMAGE, %.1f MiB unpacked"
          % (len(copied_libs), total / 1048576), flush=True)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: prepare_java21_runtime.py <build/generated/java21>")
    out = pathlib.Path(sys.argv[1]).resolve()
    cache = pathlib.Path(__file__).resolve().parent.parent / ".cache" / "jre21-android-arm64-neurocenter.tar.xz"
    prepare(fetch(cache), out)
