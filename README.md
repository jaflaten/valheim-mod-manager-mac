# valheim-mod-manager-mac

A personal Kotlin/Swing mod manager for Valheim on macOS. It installs BepInEx, downloads mods from
[Thunderstore](https://thunderstore.io/c/valheim/), lets you enable and disable them, and launches
modded Valheim.

## Run

```sh
./run.sh                 # or: ./gradlew run
./gradlew macApp         # optional: builds app/build/macApp/Valheim Mod Manager.app (drag to /Applications)
```

Requires JDK 21, Steam, Valheim, and Rosetta 2 (`softwareupdate --install-rosetta --agree-to-license`).

## Usage

1. **Setup** tab: confirm the Valheim folder, then click **Install BepInEx**. Use **Install Server devcommands** to add the dev commands mod.
2. **Browse Thunderstore**: search, select a mod, then click **Install**. Dependencies are installed automatically.
3. **Installed**: tick or untick **Enabled**, update, uninstall, and edit configs.
4. Click **▶ Play Modded**. **Play Vanilla** starts the game through Steam without mods.

Server devcommands: in game, press **F5** and type `devcommands`. Then use commands such as `god`, `fly`, or `spawn Boar 2`.
On a dedicated server, your SteamID64 must be in `adminlist.txt`.

## How it works

- BepInExPack_Valheim (pinned to 5.4.2351) is copied into the game folder. Its doorstop library supports
  only x86_64, so Valheim runs under Rosetta:
  `arch -x86_64 env DOORSTOP_ENABLED=1 DOORSTOP_TARGET_ASSEMBLY=… DYLD_INSERT_LIBRARIES=…/libdoorstop_x64.dylib Valheim -console`.
  The `DYLD_*` variables are passed as arguments to `env` because SIP strips them from the environment of protected binaries.
  BepInEx only loads with this launch, so vanilla play through Steam is unaffected.
- Mods go to `BepInEx/plugins/<Owner-Name>/` and patchers go to `BepInEx/patchers/<Owner-Name>/`. Disabling a mod moves
  its folders to `plugins_disabled/` or `patchers_disabled/`, which BepInEx does not scan. Config defaults are copied
  only when no config file exists yet, so your edits are kept. Installed-mod state is stored in `BepInEx/vmm-state.json`.
- Mods you add by hand to `BepInEx/plugins` appear as "Manual" and can also be toggled.
- App data (Thunderstore cache, downloads, game output logs): `~/Library/Application Support/ValheimModManagerSwing/`.

## Development

```sh
./gradlew test                       # unit tests (temp dirs only)
./gradlew smoke --args="install [Owner-Name ...]"  # E2E: install BepInEx + mods (default Server_devcommands) into the real game
./gradlew smoke --args=launch        # E2E: launch modded and wait for "Chainloader startup complete"
./gradlew uiSnapshot --args=/tmp     # render each tab to PNG
```
