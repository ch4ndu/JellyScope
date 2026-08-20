# Desktop JVM Runtime Notices

`DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv` records every external Gradle
family resolved on the current macOS arm64 `desktop-app` runtime classpath, the
accepted module-and-version coordinates, each family's published license, and
its upstream source route. `verifyDesktopJvmRuntimeLicenseInventory` rejects an
unlisted group, module, or version before binary license-metadata readiness can
pass.

Most of this runtime is published under Apache-2.0. SLF4J is published under
MIT. JNA is dual-licensed under Apache-2.0 or LGPL-2.1-or-later; JellyScope uses
the Apache-2.0 option. Each component retains its upstream copyright, license,
and notice terms. The source routes in the inventory are the canonical owners
for the corresponding license and notice text.

The inventory is scoped to the resolved macOS arm64 JVM runtime. It does not
replace the separate native IINA/mpv and VLC manifests or their notices, and it
does not approve an unlisted platform, architecture, component group, module,
or version.
