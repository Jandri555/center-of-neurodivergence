#!/usr/bin/env python3
"""Compile the small HotSpot-only Java loader and embed it as immutable C bytes.

The output is generated on each Android build; no executable classes are
unpacked to app-private storage at runtime. javac --release 17 is sufficient,
as the embedded loader runs inside OpenJDK 21.
"""
from pathlib import Path
import subprocess
import sys

def main():
    if len(sys.argv) != 3:
        sys.exit("Usage: compile_jar_loader.py <StreamingJarClassLoader.java> <output-dir>")
    source = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-d",
                    str(classes), str(source)], check=True)
    base = classes / "com" / "neurocenter" / "sandbox"
    required = (
        ("StreamingJarClassLoader.class", "kStreamingJarLoaderClass"),
        ("StreamingJarClassLoader$ArchiveInput.class", "kStreamingJarArchiveInputClass"),
    )
    lines = ["/* Generated from the signed NeuroCenter Java loader source. */",
             "#ifndef NEUROCENTER_STREAMING_JAR_LOADER_CLASS_H",
             "#define NEUROCENTER_STREAMING_JAR_LOADER_CLASS_H"]
    for filename, symbol in required:
        data = (base / filename).read_bytes()
        if not data.startswith(bytes.fromhex("cafebabe")):
            raise ValueError("Invalid compiled class: " + filename)
        if len(data) > 100_000:
            raise ValueError("Unexpected class size: " + filename)
        lines.append("static const unsigned char " + symbol + "[] = {")
        for i in range(0, len(data), 16):
            lines.append("    " + ", ".join("0x%02x" % x for x in data[i:i+16]) + ",")
        lines.append("};")
    lines.extend(["#endif", ""])
    (output / "streaming_jar_loader_class.h").write_text(
        "\n".join(lines), encoding="ascii")
    print("Generated immutable HotSpot loader header")

if __name__ == "__main__":
    main()
