#!/usr/bin/env bash
# Guards the secret-scan workflow supply chain: third-party actions are pinned to a full commit
# SHA and the downloaded gitleaks binary is checksum-verified before it is installed.
set -euo pipefail
f="$(dirname "$0")/../.github/workflows/secret-scan.yml"
fail=0

# Every `uses:` must reference a 40-hex commit SHA.
while IFS= read -r line; do
  if ! grep -Eq 'uses: *[^ ]+@[0-9a-f]{40}( |$)' <<<"$line"; then
    echo "FAIL: action not pinned to a commit SHA: $line"
    fail=1
  fi
done < <(grep -E '^\s*-?\s*uses:' "$f")

grep -q 'GITLEAKS_SHA256:' "$f" || { echo "FAIL: no GITLEAKS_SHA256 pin"; fail=1; }
grep -q 'sha256sum -c' "$f" || { echo "FAIL: gitleaks tarball checksum is not verified"; fail=1; }

[ "$fail" -eq 0 ] && echo "OK: secret-scan pins verified"
exit "$fail"
