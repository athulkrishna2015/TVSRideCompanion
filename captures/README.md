# Captures

HCI snoop logs and any recorded traffic. **Gitignored, never committed.**

These contain the owner's Bluetooth device addresses and the app's traffic in
clear. If a capture is ever needed as evidence, export a redacted excerpt
deliberately and say so — never `git add -f` a whole file.

## The app log is location data too

`files/logs/tvsride.log` is not just protocol noise. While a maps app is
navigating, `MapsNavListener` logs the instruction verbatim:

```
I/MapsNavListener: nav from com.google.android.apps.maps: '' / 'Example Road'
    d=550m eta=58m pic=null [raw title='550 m' text='Example Road' …]
```

That is a street name on a real route at a real time, and the `raw` fields are
worse. An exported log pasted into an issue therefore publishes the rider's
route. Redact the street and the distance before sharing one.

The same applies to source: use invented street names in tests and comments.
Real ones read as neutral examples but are the owner's actual location, and this
repository is **public**. `Example Road` is the convention.

Naming: `btsnoop-YYYYMMDD-HHMMSS.btsnoop_hci.log`, as produced by
`tools/capture-hci.sh pull`.
