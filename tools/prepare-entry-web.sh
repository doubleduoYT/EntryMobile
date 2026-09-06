#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/_entry-offline"
DEST="$ROOT/app/src/main/assets/web"
ENTRY_OFFLINE_COMMIT="b238f8aeddcd748c035d2d3b6580cc23616dfaf2"

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v node >/dev/null || { echo "Node.js is required" >&2; exit 1; }
command -v yarn >/dev/null || npm install -g yarn@1.22.22

if [[ ! -d "$WORK/.git" ]]; then
  git clone https://github.com/entrylabs/entry-offline.git "$WORK"
fi

git -C "$WORK" fetch --depth=1 origin "$ENTRY_OFFLINE_COMMIT"
git -C "$WORK" checkout --force "$ENTRY_OFFLINE_COMMIT"

pushd "$WORK" >/dev/null
# The desktop project's postinstall runs electron-rebuild. Android only needs the
# renderer, so native Electron modules are deliberately skipped here.
yarn install --ignore-scripts --network-timeout 600000
NODE_ENV=production NODE_OPTIONS=--openssl-legacy-provider ./node_modules/.bin/webpack --config webpack.config.js
popd >/dev/null

rm -rf "$DEST/src" "$DEST/node_modules"
mkdir -p "$DEST/src/main/views" "$DEST/src/renderer" "$DEST/src/renderer_build" "$DEST/node_modules" "$DEST/licenses"

cp "$WORK/src/main/views/main.html" "$DEST/src/main/views/main.html"
cp -R "$WORK/src/renderer/resources" "$DEST/src/renderer/resources"
cp -R "$WORK/src/renderer_build/." "$DEST/src/renderer_build/"

copy_module() {
  local module="$1"
  local source="$WORK/node_modules/$module"
  local target="$DEST/node_modules/$module"
  if [[ ! -e "$source" ]]; then
    echo "Missing required module: $module" >&2
    exit 2
  fi
  mkdir -p "$(dirname "$target")"
  cp -R "$source" "$target"
}

copy_module "entry-js"
copy_module "entry-tool"
copy_module "lodash"
copy_module "jquery"
copy_module "literallycanvas-mobile"
copy_module "@entrylabs/legacy-video"

cp "$ROOT/web/mobile-preload.js" "$DEST/mobile-preload.js"
cp "$ROOT/web/mobile-overrides.css" "$DEST/mobile-overrides.css"
cp "$WORK/LICENSE" "$DEST/licenses/entry-offline-LICENSE" 2>/dev/null || true
cp "$WORK/node_modules/entry-js/LICENSE" "$DEST/licenses/entry-js-LICENSE" 2>/dev/null || true

python3 - "$DEST/src/main/views/main.html" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
if 'mobile-overrides.css' not in s:
    s = s.replace('<meta charset="utf-8" />', '<meta charset="utf-8" />\n        <meta name="viewport" content="width=device-width, initial-scale=0.8, maximum-scale=3, user-scalable=yes" />\n        <link rel="stylesheet" href="../../../mobile-overrides.css" />')
if 'mobile-preload.js' not in s:
    s = s.replace('<body>', '<body>\n        <script type="text/javascript" src="../../../mobile-preload.js"></script>')
p.write_text(s, encoding='utf-8')
PY

echo "Prepared Entry 2.1.35 web assets at: $DEST"
