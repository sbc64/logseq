# Logseq web UI service

The Nix configuration and `pkgs/` paths below refer to the separate `lab.nix`
deployment repository. Application documentation and tests live in this Logseq
repository. See [Web server browser cache](web-server-cache.md) for cache
behavior, integration tests, and the journal-load benchmark.

## Disk-backed microVM

The deployed service at `https://logseq.mahi-ide.ts.net` is configured in
`hosts/visionary-vole/microvm/logseq.nix`. Its graph is stored at
`/var/lib/logseq/graphs/logseq_og` and shared from `visionary-vole`.

On startup, `pkgs/logseq-repair-property-tags.mjs` checks for imported
`:block/tags` references pointing to Properties instead of Classes. These
collisions caused template application to return HTTP 500 with
`DB write failed with invalid data`. The repair takes a consistent SQLite
backup under the graph's `backup/` directory, creates or reuses Classes with
the same titles, and redirects the affected tag references in one validated
worker transaction. Existing Properties and their values are preserved.
Subsequent startups make no changes once these references are repaired.

The repair runs before Tailscale Serve starts. It is deliberately limited to
Property/Class collisions; it does not remove unresolved imported references
or disable database validation.

Verified on a snapshot of `logseq_og`: the original `:apply-template` request
changed from HTTP 500 to HTTP 200 after repairing 17 references to four
Properties (`til`, `time`, `link`, `run`). A second repair run made no changes.

## Server backups

The microVM runs `logseq-backup.service` hourly through
`logseq-backup.timer`, including a missed run after boot. The deployment lives
in `lab.nix`: `hosts/visionary-vole/microvm/logseq.nix` and
`pkgs/logseq-backup.mjs`.

Each completed backup is stored under
`/var/lib/logseq/graphs/logseq_og/backup/server-<timestamp>-<suffix>/`:

```text
db.sqlite
metadata.edn
markdown/
  index.json
  pages/*.md
  journals/*.md
```

The script takes a consistent snapshot through the live worker's SQLite backup
API. It starts a separate worker on a disposable copy, exports each page with
Logseq's Markdown formatter, and publishes the backup directory only after all
exports succeed. Both formats therefore come from the same snapshot. Exports
include page and block properties; the index records original titles and UUIDs
for filenames that require normalization or collision suffixes. SQLite remains
the complete database backup, including records without a page identity.

Existing backups are retained. An export failure leaves a hidden
`.server-backup-*` directory containing the SQLite snapshot and diagnostic log;
it is not published as a completed backup. Assets remain in the graph's assets
directory and are not copied into these backups.

Run and inspect a backup inside the VM with:

```bash
systemctl start logseq-backup.service
systemctl list-timers logseq-backup.timer
journalctl -u logseq-backup.service
```

## Local static browser service

This configuration builds the browser version of
`github:TomaSajt/nixpkgs?ref=logseq_#logseq_2` and serves it as a systemd
service.

## Build

Add the source as a flake input:

```nix
logseqNixpkgs.url = "github:TomaSajt/nixpkgs?ref=logseq_";
```

Build the browser target from `logseq_2` rather than its Electron target:

```nix
logseqWeb = logseqPkgs.logseq_2.overrideAttrs (_: {
  pname = "logseq-web";
  buildPhase = ''
    runHook preBuild
    pnpm --dir packages/ui run build:ui
    pnpm run release
    runHook postBuild
  '';
  installPhase = ''
    runHook preInstall
    mkdir -p "$out/share/logseq-web"
    cp -r static/. "$out/share/logseq-web/"
    runHook postInstall
  '';
});
```

## Service

Serve the generated static files through Caddy on loopback port 3001. The
service starts at boot and is not exposed to the LAN or Tailscale.

```nix
systemd.services.logseq-web = {
  description = "Logseq web UI";
  after = [ "network.target" ];
  wantedBy = [ "multi-user.target" ];
  serviceConfig = {
    User = "sebas";
    Group = "users";
    ExecStart = "${pkgs.caddy}/bin/caddy run --config ${logseqWebCaddyfile} --adapter caddyfile";
    Restart = "on-failure";
    RestartSec = "5s";
    NoNewPrivileges = true;
    PrivateTmp = true;
    ProtectHome = "tmpfs";
    ProtectSystem = "strict";
    BindPaths = [ "/home/sebas/logseq/graphs" ];
    ReadWritePaths = [ "/home/sebas/logseq/graphs" ];
  };
};
```

The corresponding Caddyfile is:

```caddyfile
{
  admin off
  auto_https off
}

:3001 {
  bind 127.0.0.1
  root * ${logseqWeb}/share/logseq-web
  try_files {path} /index.html
  file_server
}
```

## Activate and use

```bash
cd /home/sebas/dots/home.nix
sudo nixos-rebuild switch --flake .#OT-DE-24194
systemctl status logseq-web
```

Open <http://localhost:3001> in a Chromium-based browser.

The browser UI stores DB graphs in browser storage; it does not live-mount a
local Logseq graph directory. Use **Import existing files → File to DB graph**
for file-based graphs, or configure **Export → Schedule backup** to write
hourly SQLite backups to a Syncthing-managed directory.
