# nkv

A simple, encrypted, decentralized key-value store for the command line, powered by Nostr.

## Install

Download the latest `nkv` binary:

```bash
wget https://github.com/chr15m/nkv/releases/latest/download/nkv
chmod +x ./nkv

# Optional: move to your path for easy access e.g.:
# mv ./nkv ~/bin
```

## Usage

The first time you run `nkv`, it generates a new Nostr private key and saves it to `./.nkv-nsec`. This key is used to encrypt and sync your data.

**Set a value:**
```bash
./nkv foo "hello world"
```

**Get a value:**
```bash
./nkv foo
```

### Syncing Across Devices

To use the same key-value store on another machine, copy the `./.nkv-nsec` file or set the `NKV_NSEC` environment variable with the key's content.

`nkv` loads the key from the `NKV_NSEC` environment variable first, falling back to the `.nkv-nsec` file.
