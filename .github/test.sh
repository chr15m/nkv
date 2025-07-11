#!/bin/sh
binary="../nkv.cljs"
echo "--- Test: --init creates .nkv file ---"
output=$(${binary} --init 2>&1)
echo "$output"
if ! [[ -f ./.nkv ]]; then
  echo "FAIL: .nkv file not created."
  exit 1
fi
if ! echo "$output" | grep -q "Wrote config to .nkv"; then
  echo "FAIL: Did not get expected output for --init."
  exit 1
fi
echo "PASS: --init created .nkv file."

echo "--- Test: --init fails if .nkv exists ---"
output=$(${binary} --init 2>&1)
exit_code=$?
echo "$output"
if [[ $exit_code -ne 1 ]]; then
  echo "FAIL: Expected exit code 1, got $exit_code."
  exit 1
fi
if ! echo "$output" | grep -q "Config already present in .nkv"; then
  echo "FAIL: Did not get expected output for existing .nkv."
  exit 1
fi
echo "PASS: --init failed as expected."
rm ./.nkv

echo "--- Test: Set and get a value ---"
${binary} --init > /dev/null 2>&1
${binary} mykey "myvalue" > /dev/null 2>&1
sleep 5 # allow time for propagation
output=$(${binary} mykey 2> /dev/null)
if [[ "$output" != "myvalue" ]]; then
  echo "FAIL: Expected 'myvalue', got '$output'."
  exit 1
fi
echo "PASS: Set and get successful."
rm ./.nkv

echo "--- Test: Environment variables ---"
export NKV_NSEC="nsec10wuq8c3n7p3mwa5588mnc6w8p6pl2vkhuse0k2gkwfg43l85f5s2qzv3fr"
export NKV_RELAYS="wss://relay.damus.io"
${binary} envkey "envvalue" > /dev/null 2>&1
sleep 5 # allow time for propagation
output=$(${binary} envkey 2> /dev/null)
if [[ "$output" != "envvalue" ]]; then
  echo "FAIL: Expected 'envvalue', got '$output'."
  exit 1
fi
if [[ -f ./.nkv ]]; then
  echo "FAIL: .nkv file was created when using env vars."
  rm ./.nkv
  exit 1
fi
echo "PASS: Environment variables successful."

echo "All tests passed!"
