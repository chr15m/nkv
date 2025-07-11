# nkv

A simple, encrypted, decentralized key-value store for the command line, powered by [Nostr](https://en.wikipedia.org/wiki/Nostr).

[![nkv demo video](https://i3.ytimg.com/vi/DSCQcAT5AEw/sddefault.jpg)](https://www.youtube.com/watch?v=DSCQcAT5AEw)

## Install

Download the latest `nkv` binary:

```bash
wget https://github.com/chr15m/nkv/releases/latest/download/nkv
chmod +x ./nkv

# Optional: move to your path for easy access e.g.:
# mv ./nkv ~/bin
```

## Usage

The first time you run `nkv`, it generates a new Nostr private key and a configuration file at `./.nkv`. This file stores the private key and the list of Nostr relays used to sync the data. Using the same config on different machines gives access to the same kv store.

**Set a value:**
```bash
./nkv foo "hello world"
```

**Get a value:**
```bash
./nkv foo
```

**Watch a key for changes:**
```bash
./nkv foo --watch echo
```
This will run the command `echo` with the new value as an argument whenever `foo` is updated.

**Note:** output is sent to stderr, except for received values, so you can safely pipe `get` into files or shell commands.

### Syncing Across Devices

To use the same key-value store on another machine, you can copy the configuration file to `./.nkv` or `~/.nkv`.

Alternatively, you can configure `nkv` using environment variables:
- `NKV_NSEC`: Set this to the shared Nostr private key (`nsec...`).
- `NKV_RELAYS`: A comma-separated list of relay URLs to use (e.g., `wss://relay.one,wss://relay.two`).

`nkv` loads its configuration with the following priority (highest first):
1. Environment variables (`NKV_NSEC`, `NKV_RELAYS`)
2. Configuration file (`./.nkv` is checked first, then `~/.nkv`)
3. Default values (a new key is generated and default relays are used)

## Tradeoffs

`nkv` is built on the Nostr protocol and inherits its characteristics. It is best suited for eventually-consistent storage of small amounts of data.

- **Update Speed**: Updates are limited to one per second and can take several seconds to propagate.
- **Data Size**: The total size of all values should be kept small (under 50kb) to avoid relay timeouts and throttling.
- **Rate Limiting**: Relays may independently rate-limit frequent updates.
- **Relay Dependency**: This depends on 3rd party relays, but values are encrypted, and you can run your own.
