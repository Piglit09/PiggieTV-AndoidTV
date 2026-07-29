# PiggieTV Native Diagnostics

The debug build keeps bounded route, focus, network, image, playback, reader, and performance traces in memory. In the app, open **Settings → Diagnostics & Beta**, leave **Diagnostics collection** enabled, run **Test Runner**, then choose **Save JSON + Text**.

The files are written inside the app sandbox:

- `files/diagnostics/ptv-diagnostics.json`
- `files/diagnostics/ptv-diagnostics.txt`

For the debug application ID, retrieve them from Windows without making the files public:

```powershell
cmd /c "adb exec-out run-as com.piggie.tv.debug cat files/diagnostics/ptv-diagnostics.json > ptv-diagnostics.json"
cmd /c "adb exec-out run-as com.piggie.tv.debug cat files/diagnostics/ptv-diagnostics.txt > ptv-diagnostics.txt"
```

Add `-s HOST:PORT` after `adb` when more than one device is connected. Release builds use their own application ID and must be debuggable for `run-as` retrieval.

Exports remove URL hosts and query strings, mask opaque identifiers, and redact credentials including authorization, access tokens, passwords, Quick Connect secrets, server IDs, and user IDs. Review an export before sharing it outside the project.

