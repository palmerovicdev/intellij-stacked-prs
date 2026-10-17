#!/bin/bash
# Prepares one capture round: a fresh demo repository, the capture script in the sandbox IDE's
# startup scripts and the project marked as trusted. Then run the printed runIde command; the
# IDE paints every screenshot to build/marketplace/shots/raw and writes DONE to capture.log.
#
#   docs/marketplace/tools/capture.sh [step,step...]   # only those steps (default: all)
#   docs/marketplace/tools/capture.sh --clean           # afterwards: startup script out, settings back
set -euo pipefail

TOOLS=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$TOOLS/../../.." && pwd)
W=$ROOT/build/marketplace
IDE="$HOME/Applications/IntelliJ IDEA.app"
VERSION=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$IDE/Contents/Resources/product-info.json")
SANDBOX=$ROOT/.intellijPlatform/sandbox/stacklane/IU-$VERSION
STARTUP=$SANDBOX/config/extensions/com.intellij/startup

pkill -f "intellij-stacked-prs/.intellijPlatform/sandbox/stacklane" 2>/dev/null || true
for _ in $(seq 1 40); do pgrep -f "intellij-stacked-prs/.intellijPlatform/sandbox/stacklane" >/dev/null || break; sleep 1; done

# The capture changes sandbox settings (gh path, editor, navigation bar, diff, trusted paths):
# they are saved before the first round and put back by --clean.
OPTIONS=$SANDBOX/config/options
BACKUP=$W/options-backup
TOUCHED="stacklane.xml editor.xml ui.lnf.xml diff.xml trusted-paths.xml"

if [[ "${1:-}" == "--clean" ]]; then
  rm -f "$STARTUP/stacklane-capture.groovy"
  rmdir "$STARTUP" 2>/dev/null || true
  if [[ -d "$BACKUP" ]]; then
    for f in $TOUCHED; do
      if [[ -f "$BACKUP/$f" ]]; then cp "$BACKUP/$f" "$OPTIONS/$f"; else rm -f "$OPTIONS/$f"; fi
    done
    rm -rf "$BACKUP"
  fi
  echo "startup script removed, sandbox settings restored"
  exit 0
fi

"$TOOLS/demo-repo.sh" >/dev/null
mkdir -p "$W/shots/raw" "$W/state"
rm -f "$W/shots/raw/"*.png "$W/shots/capture.log" "$W/state/calls.log"
if [[ -n "${1:-}" ]]; then echo "$1" > "$W/state/only"; else rm -f "$W/state/only"; fi

# A new sandbox starts from the previous one: theme, dismissed promotions and trusted paths.
if [[ ! -d "$SANDBOX/config" ]]; then
  previous=$(ls -d "$ROOT"/.intellijPlatform/sandbox/stacklane/IU-*/config 2>/dev/null | tail -1 || true)
  mkdir -p "$SANDBOX"
  if [[ -n "$previous" ]]; then cp -R "$previous" "$SANDBOX/config"; fi
fi
mkdir -p "$OPTIONS" "$STARTUP"
if [[ ! -d "$BACKUP" ]]; then
  mkdir -p "$BACKUP"
  for f in $TOUCHED; do if [[ -f "$OPTIONS/$f" ]]; then cp "$OPTIONS/$f" "$BACKUP/$f"; fi; done
fi
python3 - "$SANDBOX/config/options/trusted-paths.xml" "$W/storefront" <<'PY'
import os, sys
path, project = sys.argv[1], sys.argv[2]
text = open(path).read() if os.path.exists(path) else ""
if project not in text:
    entry = f'        <entry key="{project}" value="true" />\n'
    if "<map>" in text:
        text = text.replace("<map>\n", "<map>\n" + entry, 1)
    else:
        text = ('<application>\n  <component name="Trusted.Paths">\n    <option name="TRUSTED_PROJECT_PATHS">\n'
                f'      <map>\n{entry}      </map>\n    </option>\n  </component>\n</application>\n')
    open(path, "w").write(text)
PY
sed "s#@@ROOT@@#$ROOT#g" "$TOOLS/capture.groovy" > "$STARTUP/stacklane-capture.groovy"

cat <<EOF
round ready. Run (in the background):
  cd $ROOT && ./gradlew runIde "-PlocalIdePath=$IDE" "-Porg.gradle.java.installations.paths=$IDE/Contents/jbr/Contents/Home" "-PrunIdeProject=$W/storefront" > $W/runide.log 2>&1
EOF
