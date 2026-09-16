# Voxy for Minecraft 1.21.1 (Fabric)

An unofficial backport of [Cortex's Voxy](https://github.com/MCRcortex/voxy),
which renders distant terrain using levels of detail (LODs). This branch also
includes rendering fixes, customizable distant clouds, and an optional
[VoxyFog](https://github.com/notmugi/VoxyFog) addon bridge.

> **Do not report backport bugs to Cortex's Discord or upstream Voxy.**
> Upstream does not provide support for this backport. Keep backport-specific
> reports in this repository.

## Install

Use the **`fabric/1.21.1` branch**, not the repository's default `dev` branch.

Requirements:

- Minecraft **1.21.1** with **Java 21**.
- Fabric Loader (the build targets **0.18.2**; use **0.18.4+** with VoxyFog).
- Fabric API for 1.21.1 (the build uses **0.116.6+1.21.1**).
- Sodium **0.6.13 for Fabric 1.21.1**, the version this port is built against.
- A compatible GPU/driver; Voxy checks for compute-shader and indirect-draw
  support at startup. Support for every GPU is not guaranteed.

Build the JAR as described below, or use the `voxy-artifacts` download from a
successful **manual-artifact** run for `fabric/1.21.1` in
[GitHub Actions](https://github.com/notmugi/voxy_ports/actions).

Put the Voxy JAR in your client's `mods` folder alongside its dependencies.
Remove any older Voxy JAR first; do not install two versions together.
[Mod Menu](https://modrinth.com/mod/modmenu) is optional and provides another way
to open Voxy's settings.

## Settings

Open **Video Settings → Voxy** in Sodium, or Voxy's configuration through Mod Menu.

- **Enable Voxy**, **rendering**, and **ingestion** control the mod, drawing LODs,
  and collecting terrain data respectively.
- **Render distance** controls distant-terrain coverage independently of vanilla's
  render distance. The UI displays chunks; higher distances cost more resources.
- **Enable render fog** controls the port's vanilla/environmental fog setting.
- **Massive distant clouds** enables this port's independent cloud renderer, even
  when vanilla/Sodium clouds are off. Disable it to restore their cloud rendering.
- Cloud sliders adjust **height, cell width, thickness, speed, and fade start/end**.
  Cloud fade distances are percentages of Voxy's render distance.

Use **Apply** for ordinary settings and toggles. Cloud sliders apply and save
immediately; Undo does not revert them. Shaderpacks retain their own clouds.
Settings are saved in `config/voxy-config.json`.

### Optional distance fog

Install [VoxyFog 0.1.1](https://github.com/notmugi/VoxyFog) alongside this port for
adjustable distant-terrain fog with an early-building S-curve and a gentle fade
toward full opacity. It adds **Voxy Distance Fog**, **Fog Min**, and **Fog Max**
to the Voxy settings page.

Min/Max are fractions of Voxy's render distance and save immediately. The addon
stores its settings separately in `config/voxy-renderdistance-fog.json`; its new
curve does not replace water, lava, or status-effect fog. VoxyFog is optional,
not bundled into this port.

## Build

Select a **Java 21 JDK**, then:

```sh
git clone --branch fabric/1.21.1 https://github.com/notmugi/voxy_ports.git
cd voxy_ports
./gradlew clean build
```

On Windows, use `gradlew.bat clean build`. The Gradle wrapper downloads the build
tools and dependencies. Local installable JARs are written to
`build/libs/voxy-0.2.10-alpha-<commit>.jar`; CI builds omit the commit suffix.

## Credits and license

Voxy was created by Cortex. This is an unofficial port, not an upstream release.
See [LICENSE.md](LICENSE.md) for the existing all-rights-reserved license and
redistribution restrictions.
