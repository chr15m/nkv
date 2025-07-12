#!/bin/sh
binary="../nkv.cljs"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

PASS="${GREEN}✅"
FAIL="${RED}❌"
echo "--- Test: --init creates .nkv file ---"
output=$(${binary} --init 2>&1)
echo "$output"
if ! [ -f ./.nkv ]; then
  printf "%b\n" "${FAIL} .nkv file not created.${NC}"
  exit 1
fi
if ! echo "$output" | grep -q "Wrote config to .nkv"; then
  printf "%b\n" "${FAIL} Did not get expected output for --init.${NC}"
  exit 1
fi
printf "%b\n" "${PASS} --init created .nkv file.${NC}"

echo "--- Test: --init fails if .nkv exists ---"
output=$(${binary} --init 2>&1)
exit_code=$?
echo "$output"
if [ $exit_code -ne 1 ]; then
  printf "%b\n" "${FAIL} Expected exit code 1, got $exit_code.${NC}"
  exit 1
fi
if ! echo "$output" | grep -q "Config already present in .nkv"; then
  printf "%b\n" "${FAIL} Did not get expected output for existing .nkv.${NC}"
  exit 1
fi
printf "%b\n" "${PASS} --init failed as expected.${NC}"
rm ./.nkv

echo "--- Test: Set and get a value ---"
${binary} --init > /dev/null 2>init.log
cat init.log
${binary} mykey "myvalue" > /dev/null 2>set.log
cat set.log
sleep 5 # allow time for propagation
output=$(${binary} mykey 2>get.log)
cat get.log
if [ "$output" != "myvalue" ]; then
  printf "%b\n" "${FAIL} Expected 'myvalue', got '$output'.${NC}"
  exit 1
fi
printf "%b\n" "${PASS} Set and get successful.${NC}"
rm ./.nkv

echo "--- Test: Environment variables ---"
export NKV_NSEC="nsec1q795m85vzgpjnn7rhh2glfkzlxml2c99h5v3q3s7w76kjvnfzmhslvfkka"
export NKV_RELAYS="wss://relay.damus.io"
${binary} envkey "envvalue" > /dev/null 2>env-set.log
cat env-set.log
sleep 5 # allow time for propagation
output=$(${binary} envkey 2>env-get.log)
cat env-get.log
if [ "$output" != "envvalue" ]; then
  printf "%b\n" "${FAIL} Expected 'envvalue', got '$output'.${NC}"
  exit 1
fi
if [ -f ./.nkv ]; then
  printf "%b\n" "${FAIL} .nkv file was created when using env vars.${NC}"
  rm ./.nkv
  exit 1
fi
printf "%b\n" "${PASS} Environment variables successful.${NC}"

echo "--- Test: --help flag ---"
output=$(${binary} --help 2>&1)
exit_code=$?
if [ $exit_code -ne 0 ]; then
  printf "%b\n" "${FAIL} --help exited with code $exit_code, expected 0.${NC}"
  exit 1
fi
if ! echo "$output" | grep -q "Usage:"; then
  printf "%b\n" "${FAIL} --help output did not contain 'Usage:'.${NC}"
  exit 1
fi
printf "%b\n" "${PASS} --help flag works as expected.${NC}"

echo "--- Test: Read non-existent key ---"
output=$(${binary} non_existent_key 2>&1)
exit_code=$?
if [ $exit_code -ne 1 ]; then
  printf "%b\n" "${FAIL} Reading non-existent key exited with code $exit_code, expected 1.${NC}"
  exit 1
fi
if ! echo "$output" | grep -q "No value found for key"; then
  printf "%b\n" "${FAIL} Did not get expected output for non-existent key.${NC}"
  exit 1
fi
printf "%b\n" "${PASS} Reading non-existent key failed as expected.${NC}"

echo "--- Test: --watch functionality ---"
WATCH_FILE="watch_output.log"
# Clear watch file if it exists
true > "$WATCH_FILE"

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
if [ "$watch_output" != "updated" ]; then
  printf "%b\n" "${FAIL} --watch did not produce the correct output. Expected 'updated', got '$watch_output'.${NC}"
  echo "--- stderr from watch process ---"
  cat watch-stderr.log
  echo "---------------------------------"
  rm "$WATCH_FILE" watch-set1.log watch-set2.log watch-stderr.log
  exit 1
fi
printf "%b\n" "${PASS} --watch functionality works as expected.${NC}"
rm "$WATCH_FILE" watch-set1.log watch-set2.log watch-stderr.log

printf "%b\n" "${GREEN}All tests passed!${NC}"
