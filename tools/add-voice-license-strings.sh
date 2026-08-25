#!/bin/sh
# Insert the five voice-license strings into one strings.xml, right before </resources>.
# Usage: add-voice-license-strings.sh <values-dir> <link> <title> <body> <checkbox> <confirm>
# Idempotent: a file that already has the strings is left alone.
f="app/src/main/res/$1/strings.xml"
[ -f "$f" ] || { echo "FEHLT   $1"; exit 1; }
grep -q 'voice_license_link' "$f" && { echo "schon da $1"; exit 0; }
tmp=$(mktemp)
{
  echo "    <!-- Voice licenses -->"
  printf '    <string name="voice_license_link">%s</string>\n' "$2"
  printf '    <string name="voice_license_title">%s</string>\n' "$3"
  printf '    <string name="voice_license_body">%s</string>\n' "$4"
  printf '    <string name="voice_license_checkbox">%s</string>\n' "$5"
  printf '    <string name="voice_license_confirm">%s</string>\n' "$6"
} > "$tmp"
awk -v B="$tmp" 'BEGIN{while((getline l < B)>0) blk=blk l "\n"} /<\/resources>/{printf "%s", blk} {print}' "$f" > "$f.new" \
  && mv "$f.new" "$f"
rm -f "$tmp"
echo "ok      $1"
