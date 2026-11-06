# DriftClient fork (Minecraft 26.2)

Based on upstream `26.2` at `9fadf7cf95293d7f6678981e77a939b4a7978373`.
The fork retains Baritone's LGPL-3.0-or-later license.

Enable `smoothMovement` to apply gradual, mouse-quantized ground rotations. Limits
are `smoothMovementMaxTurn` (18 degrees/tick per axis) and
`smoothMovementAcceleration` (3 degrees/tick²). The pure controller reserves enough
angular distance to brake at stationary targets. Changed targets can require a
hard stop; mouse quantization also adds small deviations. Large ground turns wait
before walking, except during movements unsafe to interrupt. Block interaction
waits for the actual crosshair to arrive. The old camera averaging is bypassed for
ground smoothing. Disable free look to display the resulting rotations.

The live aim processor holds one rotation origin per tick. Repeated previews do
not consume turn state; committed velocities take effect on the next tick.
Prediction forks copy their own velocities/random state. Idle ticks and world
transitions reset steering momentum. Elytra uses the existing aim algorithm.

`drift/build.gradle` is a Fabric-only build adapter for inclusion as the `:baritone`
subproject of DriftClient's Java 25/Loom build. It compiles the upstream source
sets into a separate Fabric jar with the license and native dependency included.
Upstream multi-loader build files are retained.

In a DriftClient checkout:

```sh
git submodule update --init --recursive
cd client
./gradlew :baritone:test
./gradlew build
```

`SmoothRotationTest` covers seams, fixed-target convergence, overshoot, limits,
reversal, and invalid inputs without launching Minecraft. DriftClient's
`dev/movement_bench.py` runs resettable singleplayer fixtures for turning, corners,
stairs, and mining, compares baseline/smooth telemetry, and checks the real aim
processor's preview/fork contracts. See DriftClient's `docs/movement.md` for setup.
