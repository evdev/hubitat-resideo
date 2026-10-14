#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
task_groovy_jar="${RESIDEO_GROOVY_JAR:-.runtime/groovy-all-2.4.21.jar}"
if [ ! -f "$task_groovy_jar" ]; then
    mkdir -p .runtime
    curl -fL --retry 2 -o "$task_groovy_jar.part" https://repo.maven.apache.org/maven2/org/codehaus/groovy/groovy-all/2.4.21/groovy-all-2.4.21.jar
    mv "$task_groovy_jar.part" "$task_groovy_jar"
fi
task_expected_sha='de65260cf2070442e99882f2f3d72e7531725c1e6a257446cc0cea525c607bd0'
if command -v sha256sum >/dev/null 2>&1; then
    task_actual_sha=$(sha256sum "$task_groovy_jar" | cut -d ' ' -f 1)
else
    task_actual_sha=$(shasum -a 256 "$task_groovy_jar" | cut -d ' ' -f 1)
fi
if [ "$task_actual_sha" != "$task_expected_sha" ]; then
    echo 'Groovy runtime checksum mismatch.' >&2
    exit 1
fi
java -cp "$task_groovy_jar" groovy.ui.GroovyMain tests/run.groovy
