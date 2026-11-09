#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if ! command -v pwsh >/dev/null 2>&1; then
  printf '%s\n' 'Install PowerShell 7 (pwsh), JDK 17, and Android SDK Platform/Build Tools 35 first.' >&2
  exit 1
fi
exec pwsh -NoLogo -NoProfile -File ./build.ps1 "$@"
