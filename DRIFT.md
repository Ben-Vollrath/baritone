# DriftClient fork (Minecraft 26.2)

Based on upstream `26.2` at `9fadf7cf95293d7f6678981e77a939b4a7978373`.
The fork retains Baritone's LGPL-3.0-or-later license.

`naturalMovement` adds seeded motor-response variation, occasional overshoot and
correction, render-frame camera interpolation, spatial route/lane preferences,
straight-line lookahead, walking gaze changes, turn strafing, and optional sprint
hops on verified flat cardinal or diagonal runways. Diagonal hops check both swept
corner cells at jump height. `naturalMovementSeed` fixes a job for replay;
Drift supplies fresh job seeds in normal use. `naturalSprintJump` controls optional
hops and `naturalRouteVariation` controls the added route preference cost.
Natural mode briefly acquires mining aim and waits for nearby drops to become
collectible. This is ongoing work; visual human equivalence is not established.

Natural motor parameters are generated centrally in `NaturalSteering.parameters`,
so live steering and seeded convergence tests use the same responses. With the
default base limits, job speed limits vary from 25.2 to 50.4 degrees/tick; stronger
braking shortens the constant-speed part of large turns. These ranges were
adjusted after matched personal movement references showed faster, more varied
initial turns. The regression `smoothMovement` profile retains its original limits.

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
Prediction forks copy their own velocities/random state. After arrival, natural
steering finishes the last hand motion for at most 40 ticks. Manual keys, external
rotation, explicit reset, and world transitions cancel this continuation. Other
idle ticks reset steering momentum. Elytra uses the existing aim algorithm.

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

`NaturalSteeringTest` covers seeded overshoot/recovery and camera continuity;
`RouteVariationTest` covers deterministic, coherent, nonnegative spatial
preferences. The in-game fixtures also cover standing aim, alternate routes,
headroom, ledges, and a long runway. DriftClient can record rendered footage and
build a local synchronized comparison with `dev/movement_review.py`.
