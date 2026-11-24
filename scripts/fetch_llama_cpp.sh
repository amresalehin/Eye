#!/usr/bin/env bash
set -euo pipefail

readonly COMMIT="56381e407c0ccfb3a6f71e668a27a901001d22ce"
readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly DESTINATION="${ROOT}/third_party/llama.cpp"

if [[ -f "${DESTINATION}/CMakeLists.txt" ]]; then
    printf 'llama.cpp already present at %s\n' "${DESTINATION}"
    exit 0
fi

mkdir -p "$(dirname "${DESTINATION}")"
git init "${DESTINATION}"
git -C "${DESTINATION}" remote add origin https://github.com/ggml-org/llama.cpp.git
git -C "${DESTINATION}" fetch --depth 1 --filter=blob:none origin "${COMMIT}"
git -C "${DESTINATION}" checkout --detach FETCH_HEAD

actual="$(git -C "${DESTINATION}" rev-parse HEAD)"
if [[ "${actual}" != "${COMMIT}" ]]; then
    echo "llama.cpp revision mismatch: expected ${COMMIT}, got ${actual}" >&2
    exit 1
fi

printf 'Fetched llama.cpp %s\n' "${COMMIT}"
