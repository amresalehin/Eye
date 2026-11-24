#!/usr/bin/env bash
set -euo pipefail

readonly COMMIT="56381e407c0ccfb3a6f71e668a27a901001d22ce"
readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly DESTINATION="${ROOT}/third_party/llama.cpp"

# Reuse only a complete checkout at the exact pinned revision. Remove partial
# checkouts left by interrupted downloads so git init/remote setup is repeatable.
if [[ -d "${DESTINATION}" ]]; then
    existing="$(git -C "${DESTINATION}" rev-parse HEAD 2>/dev/null || true)"
    if [[ -f "${DESTINATION}/CMakeLists.txt" && "${existing}" == "${COMMIT}" ]]; then
        printf 'llama.cpp %s already present\n' "${COMMIT}"
        exit 0
    fi
    rm -rf "${DESTINATION}"
fi

mkdir -p "$(dirname "${DESTINATION}")"
git init "${DESTINATION}"
git -C "${DESTINATION}" remote add origin https://github.com/ggml-org/llama.cpp.git

# Some Git mirrors/proxies do not support partial-clone filters. Retry with a
# normal shallow fetch rather than leaving a broken dependency directory.
if ! git -C "${DESTINATION}" fetch --depth 1 --filter=blob:none origin "${COMMIT}"; then
    git -C "${DESTINATION}" fetch --depth 1 origin "${COMMIT}"
fi
git -C "${DESTINATION}" checkout --detach FETCH_HEAD

actual="$(git -C "${DESTINATION}" rev-parse HEAD)"
if [[ "${actual}" != "${COMMIT}" || ! -f "${DESTINATION}/CMakeLists.txt" ]]; then
    echo "llama.cpp checkout verification failed: expected ${COMMIT}, got ${actual}" >&2
    rm -rf "${DESTINATION}"
    exit 1
fi

printf 'Fetched llama.cpp %s\n' "${COMMIT}"
