#!/usr/bin/env bash
# Compile and test. No build tool on purpose: the library has zero dependencies,
# so a JDK is the whole toolchain.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
mkdir -p out

javac -Xlint:all -d out $(find src test -name '*.java')
java -cp out quilt.Tests
