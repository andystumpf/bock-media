#!/usr/bin/env bash
# Downloads Material Icons (baseline/filled) matching Android Icons.Default.*
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/BockMedia/Resources/Assets.xcassets"

ICONS=(
  home play-arrow library-music search list schedule album mic music-note star
  download download-done bolt record-voice-over speaker speaker-group analytics settings
  person pause skip-next skip-previous shuffle phone-android push-pin check clear
  add delete favorite favorite-border bedtime history more-horiz more-vert block
  volume-up volume-down build merge auto-awesome psychology refresh playlist-add
  grid-view edit remove stop folder new-releases close arrow-back
)

mkdir -p "$ASSETS"

cat > "$ASSETS/Contents.json" <<'EOF'
{
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
EOF

for name in "${ICONS[@]}"; do
  asset="ic_${name//-/_}"
  dir="$ASSETS/${asset}.imageset"
  mkdir -p "$dir"
  curl -fsSL "https://api.iconify.design/ic:baseline-${name}.svg" -o "$dir/${name}.svg"
  cat > "$dir/Contents.json" <<EOF
{
  "images" : [
    {
      "filename" : "${name}.svg",
      "idiom" : "universal"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  },
  "properties" : {
    "preserves-vector-representation" : true,
    "template-rendering-intent" : "template"
  }
}
EOF
  echo "Fetched $asset"
done

echo "Done — $((${#ICONS[@]})) icons in $ASSETS"
