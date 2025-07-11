#!/bin/sh
binary="../nkv.cljs"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

PASS="${GREEN}✅${NC}"
FAIL="${RED}❌${NC}"
echo "--- Test: --init creates .nkv file ---"
output=$(${binary} --init 2>&1)
echo "$output"
if ! [[ -f ./.nkv ]]; then
  echo -e "${FAIL} .nkv file not created."
  exit 1
fi
if ! echo "$output" | grep -q "Wrote config to .nkv"; then
  echo -e "${FAIL} Did not get expected output for --init."
  exit 1
fi
echo -e "${PASS} --init created .nkv file."

echo "--- Test: --init fails if .nkv exists ---"
output=$(${binary} --init 2>&1)
exit_code=$?
echo "$output"
if [[ $exit_code -ne 1 ]]; then
  echo -e "${FAIL} Expected exit code 1, got $exit_code."
  exit 1
fi
if ! echo "$output" | grep -q "Config already present in .nkv"; then
  echo -e "${FAIL} Did not get expected output for existing .nkv."
  exit 1
fi
echo -e "${PASS} --init failed as expected."
rm ./.nkv

echo "--- Test: Set and get a value ---"
${binary} --init > /dev/null 2>init.log
cat init.log
${binary} mykey "myvalue" > /dev/null 2>set.log
cat set.log
sleep 5 # allow time for propagation
output=$(${binary} mykey 2>get.log)
cat get.log
if [[ "$output" != "myvalue" ]]; then
  echo -e "${FAIL} Expected 'myvalue', got '$output'."
  exit 1
fi
echo -e "${PASS} Set and get successful."
rm ./.nkv

echo "--- Test: Environment variables ---"
export NKV_NSEC="nsec1q795m85vzgpjnn7rhh2glfkzlxml2c99h5v3q3s7w76kjvnfzmhslvfkka"
export NKV_RELAYS="wss://relay.damus.io"
${binary} envkey "envvalue" > /dev/null 2>env-set.log
cat env-set.log
sleep 5 # allow time for propagation
output=$(${binary} envkey 2>env-get.log)
cat env-get.log
if [[ "$output" != "envvalue" ]]; then
  echo -e "${FAIL} Expected 'envvalue', got '$output'."
  exit 1
fi
if [[ -f ./.nkv ]]; then
  echo -e "${FAIL} .nkv file was created when using env vars."
  rm ./.nkv
  exit 1
fi
echo -e "${PASS} Environment variables successful."

echo "--- Test: --help flag ---"
output=$(${binary} --help 2>&1)
exit_code=$?
if [[ $exit_code -ne 0 ]]; then
  echo -e "${FAIL} --help exited with code $exit_code, expected 0."
  exit 1
fi
if ! echo "$output" | grep -q "Usage:"; then
  echo -e "${FAIL} --help output did not contain 'Usage:'."
  exit 1
fi
echo -e "${PASS} --help flag works as expected."

echo "--- Test: Read non-existent key ---"
output=$(${binary} non_existent_key 2>&1)
exit_code=$?
if [[ $exit_code -ne 1 ]]; then
  echo -e "${FAIL} Reading non-existent key exited with code $exit_code, expected 1."
  exit 1
fi
if ! echo "$output" | grep -q "No value found for key"; then
  echo -e "${FAIL} Did not get expected output for non-existent key."
  exit 1
fi
echo -e "${PASS} Reading non-existent key failed as expected."

echo "--- Test: --watch functionality ---"
WATCH_FILE="watch_output.log"
# Clear watch file if it exists
> "$WATCH_FILE"

# Set an initial value
${binary} watchkey "initial" >/dev/null 2>watch-set1.log
cat watch-set1.log
sleep 5

# Start watching in the background
${binary} watchkey --watch echo > "$WATCH_FILE" 2>watch-stderr.log &
WATCH_PID=$!
# Give the subscription a moment to start
sleep 5

# Update the value, which should trigger the watch command
${binary} watchkey "updated" >/dev/null 2>watch-set2.log
cat watch-set2.log
sleep 5 # Allow time for propagation and command execution

# Kill the watcher
kill $WATCH_PID
# Wait a moment to ensure it's killed
sleep 1

# Check the output
watch_output=$(cat "$WATCH_FILE")
if [[ "$watch_output" != "updated" ]]; then
  echo -e "${FAIL} --watch did not produce the correct output. Expected 'updated', got '$watch_output'."
  echo "--- stderr from watch process ---"
  cat watch-stderr.log
  echo "---------------------------------"
  rm "$WATCH_FILE" watch-set1.log watch-set2.log watch-stderr.log
  exit 1
fi
echo -e "${PASS} --watch functionality works as expected."
rm "$WATCH_FILE" watch-set1.log watch-set2.log watch-stderr.log

echo -e "${GREEN}All tests passed!${NC}"
