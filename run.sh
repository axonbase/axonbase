#!/bin/bash
# Lanza o servidor AxonBase con curl de proba.
set -e
cd "$(dirname "$0")"

M2=~/.m2/repository

CP=""
for m in axonbase-common axonbase-value axonbase-parser axonbase-core axonbase-server axonbase-sdk-java; do
  CP="$CP:$m/target/classes"
done
CP="$CP:$(find "$M2/org/eclipse/jetty" "$M2/jakarta/servlet" "$M2/jakarta/servlet-api" "$M2/org/slf4j" -name '*.jar' 2>/dev/null | tr '\n' ':')"

java -cp "${CP#:}" com.axonbase.server.Main "$@"