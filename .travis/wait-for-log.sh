#!/bin/sh
# Sealed build: Travis archives job logs asynchronously, often minutes after the
# job ends, and the build harvester reads the log as soon as the build finishes.
# Keep the job open (after_script, cannot change the build result) until this
# job's public log already contains the test result, so the harvested log is
# complete. Prints a heartbeat on every poll so Travis's no-output watchdog never
# fires; capped well under Travis's 50-minute job limit; always exits 0.
CAP=1500
URL="https://api.travis-ci.com/v3/job/${TRAVIS_JOB_ID}/log.txt"
# the gradle test summary, or Travis's exit line for the test command when it fails earlier
MARK="TEST SUMMARY [(]:test[)]|lib/java check\" exited"
waited=0
while [ "$waited" -lt "$CAP" ]; do
  if curl -fsSL "$URL" 2>/dev/null | grep -qE "$MARK"; then
    echo "log archive contains the test result after ${waited}s"
    exit 0
  fi
  echo "waiting for log archive... (${waited}s of ${CAP}s)"
  sleep 20
  waited=$((waited + 20))
done
echo "log archive wait cap reached (${CAP}s)"
exit 0
