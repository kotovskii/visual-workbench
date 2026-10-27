# AutismCraft focused performance build

28 October 2026. Minecraft 1.21.1, NeoForge, Java 21.

The installed Visual Workbench 21.1.2 jar registered a global PuzzlesLib
`ModelEvents.MODIFY_UNBAKED_MODEL` listener. The supplied source already replaces
that older path with `onRegisterBlockStateResolver`, registering a resolver for
each converted workbench. The local build uses this targeted source path and
adds one timing message per resource reload.

Run `python build-local.py`. This compiles
`Common/src/main/java/fuzs/visualworkbench/client/VisualWorkbenchClient.java`
against the installed PuzzlesLib 21.1.62, local mapped Minecraft, and server
libraries. It packages the compiled client entrypoint into a copy of the
installed release. It does not rebuild unrelated downloaded sources.

Only two zip entries change:

- The client entrypoint class, switching to targeted model resolution, keeping
  crafting-screen and block-entity-renderer registration, and copying render
  types through PuzzlesLib's current registration context.
- NeoForge metadata: version `21.1.2+autismcraft.1` and PuzzlesLib minimum
  `21.1.58`, matching the newer source API.

All server and inventory logic, block registrations, tags, recipes, assets,
JEI/EMI integration classes, and mixin configurations remain byte-for-byte
identical to the installed release. The former BlockModelHandler class is
retained for binary compatibility but the new entrypoint does not register its
global callback. No new mixins are activated.

Original jar: `local-build-input/VisualWorkbench-v21.1.2-1.21.1-NeoForge.jar`.

Output: `local-build/VisualWorkbench-v21.1.2+autismcraft.1-1.21.1-NeoForge.jar`.

`local-build/receipt.json` records artifact/source hashes and verifies the exact
archive changes. Identical artifacts are deployed to client and server because
the mod uses MATCH_VERSION. Original jars are backed up and disabled.

The model source is loaded from the active ResourceManager on every resource
reload. Models are mapped through the original block's properties, with the
supplied source's missing-model fallback retained. No cross-reload model cache
is introduced, so resource-pack changes can still replace models normally.

Runtime checks use the actual pack's converted workbench registry and compare
baseline/patched baked meshes for every state, including texture identity and
sprite-relative UVs. Normalizing UVs avoids mistaking texture-atlas repacking
for a changed model. Test agent source is under `local-tests`; it is not a mod
or a persistent JVM argument. Results and startup timing are recorded under
AutismCraft-Server/reports/2026-10-27-visual-workbench.

The source-before snapshot and original jars are preserved in
AutismCraft-Server/backups/2026-10-28-visual-workbench. Existing user Git changes
were preserved.
