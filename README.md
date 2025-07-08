Command line decentralized key-value store. Key-vals are encrytped and sync'ed over Nostr.

# Install

Download the `nkv` command line utility:

```
wget https://github.com/chr15m/nkv/releases/latest/download/nkv
chmod 755 nkv
./nkv
```

The first time it is run it will create a new nsec key in `.nkv-nsec`.

The key will be read from that file, or from the env var `NKV_NSEC`.

Use the same key on different machines to share the same kv store.

# Use

Set a value for key `foo`:

```
./nkv foo 42
```

Get the value of key `foo` from Nostr:

```
./nkv foo
```
