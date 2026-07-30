#!/bin/sh
# Builds AGENT-BUNDLE.md: one self-contained file to hand to another agent,
# containing the briefing, the reference documentation and every source file inline.
# Regenerate after changing any source so the bundle never goes stale.
set -eu
cd "$(dirname "$0")/.."

OUT=AGENT-BUNDLE.md

{
    cat AGENT-BRIEFING.md
    echo
    echo '---'
    echo
    echo '# Referentiedocumentatie'
    echo
    # demote the README headings one level so it nests under the heading above
    sed 's/^#/##/' README.md
    echo
    echo '---'
    echo
    echo '# Broncode'
    echo
    echo 'Alle bestanden hieronder horen in `src/main/java/nl/spheredetect/`, behalve'
    echo '`SyntheticSelfTest.java` (die hoort in `src/test/java/nl/spheredetect/` en mag'
    echo 'buiten de app-build blijven).'
    echo

    for f in $(find src -name '*.java' | sort); do
        echo
        echo "## \`$f\`"
        echo
        echo '```java'
        cat "$f"
        echo '```'
    done
} > "$OUT"

echo "$OUT: $(wc -l < "$OUT") regels, $(du -h "$OUT" | cut -f1)"
