#!/usr/bin/env bash
# publish-sample.sh <topic> <Type> <key> <payload-overrides-json>: sends the contracts 1.3.0 sample of <Type> to the dev kit's Kafka, with a new eventId and the overrides (for events no ./sim scenario sends: a desk cash-out, a refund for our own order).
set -euo pipefail
topic=$1; type=$2; key=$3; overrides=$4
jar=$(find ~/.gradle/caches -name 'contracts-1.3.0-sources.jar' | head -1)
msg=$(unzip -p "$jar" "contracts/samples/$topic/$type.json" | python3 -I -c '
import json,sys,uuid,datetime
env=json.load(sys.stdin); over=json.loads(sys.argv[2])
env["payload"].update(over)
for v in over.values():
    if isinstance(v,str) and len(v)==36: uuid.UUID(v)
env.update(eventId=str(uuid.uuid4()), key=sys.argv[1], sequence=1, occurredAt=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
print(json.dumps(env,separators=(",",":")))' "$key" "$overrides")
printf '%s|%s\n' "$key" "$msg" | docker exec -i devkit-kafka-1 kafka-console-producer --bootstrap-server localhost:9092 --topic "$topic" --property parse.key=true --property key.separator='|' >/dev/null
echo "sent $type key $key"
