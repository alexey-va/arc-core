# Personal and contextual action panels

`ru.arc.paper.actionpanel.PaperActionPanels<C, A>` owns the reusable floating
control policy. It delegates keyed visuals, native input and exact world queries to
[the shared spatial scene owners](paper-spatial-scenes.md). `C` is an immutable domain session/target identity; `A` is a
typed domain action, commonly an enum. The owning plugin supplies a controller
that resolves a fresh frame and rechecks domain authority in `activate`.

```kotlin
val panels = PaperActionPanels(plugin, displays, object : ActionPanelController<PlanId, BuildAction>() {
    override fun frame(player: Player): ActionPanelFrame<PlanId, BuildAction>? {
        val plan = currentEligiblePlan(player) ?: return null
        val placement = ActionPanelPlacement.FollowTarget(plan.worldId, plan.center)
        val layout = ActionPanelLayouts.twoColumn(plan.actions.map { ActionPanelAction(it, label(it)) }, plan.status)
        return ActionPanelFrame(plan.id, placement, layout)
    }
    override fun activate(player: Player, context: PlanId, action: BuildAction) {
        executeAfterCurrentPermissionAndPlanChecks(player, context, action)
    }
})
```

The display-taking constructor borrows the existing `PaperPacketDisplays` owner.
The source-taking constructor creates and closes its own owner. Both use the
same asynchronous packet encoding, admission budget, delta transport and ordered
cleanup. There is no second transport or host service. Public convenience calls
use concrete overloads; consumers can call explicit constructors across classloader
boundaries without synthetic Kotlin default-argument seams.

All controller calls, world reads, lifecycle calls and handle mutations happen
on Paper's primary thread. Tick through the feature's existing lifecycle, or
`refresh(player)` when that lifecycle already discovers eligible viewers.
`target(player)` re-resolves a context/action without executing or deduplicating;
it is intended for existing external tool routers. `intercept(player)` dispatches
the aimed action with a shared 250 ms gate. A miss returns false. `clear(UUID)`
and `close()` release text, native targets and guide suppression idempotently.
Quit, death, teleport, world change and respawn clear the current panel; the next
eligible refresh reconstructs it. No world entity or chunk survives shutdown.

## Specifications and input

Layouts contain stable-key buttons and optional stable-key decorative labels.
Every rectangle describes the complete **rendered** footprint in blocks, with
scale already included. Local X is display-right, Y is up, Z points from viewer
toward the plane. Text anchors use rectangle bottom plus the explicit baseline
inset. Callers own measured text fit; culling dimensions alone do not establish
the rendered footprint. Layouts are bounded to 32 controls and 8 labels.

`Fixed` copies an authored Bukkit Location and preserves arbitrary local XYZ
offsets. `FollowTarget` faces the target's horizontal centre, retaining the
previous direction within 0.25 blocks of the centre. `FollowView` adopts camera
yaw only after body translation. Following captures eye offset once, so head
turns, crouching and eye bob do not reposition controls. Actual eye rays still
update hover and click each time. Domain eligibility and distance/context exit
remain in `frame`; the renderer cannot decide whether a player still owns a
selection, occupies the required seat, holds a tool, or may manage a land.

| Profile | Preserved input contract |
|---|---|
| `ENTITY_BOTH` | Native left and right controls plus optional tool interception. Owns cancelled right-click events; left events preserve real external cancellation while accepting the engine's `willAttack=false` form. |
| `ENTITY_RIGHT` | Native right controls; already-cancelled events never dispatch. |
| `EXTERNAL_RAY` | No native Interaction entities. Existing tool router owns gestures and may use `target` with its own deduplication. |

Offhand and foreign-owner entity clicks never activate controls. Native AABB
overlap is remapped through the same visible yaw plane as hover. A disabled
nearest visible control blocks click-through to a farther enabled control.
An entity ID alone grants no authority: resolve the current controller frame,
check owner/world/context/layout/reach/audience/visibility/enablement and the
actual-eye ray/occlusion, then invoke the domain controller. Replacing semantic
context rotates native hitbox IDs and removes their old dispatch mapping while
retaining text handles; stale native input cannot activate a replacement session.

## Placement and motion

Following panels validate every visible rectangle corner, including the header,
against reach and line of sight. Space uses actual block collision shapes and
an oriented volume. Bounds and ray paths require loaded **and already sent**
chunks; no read generates a chunk. Click-time checks use actual eyes across the
whole following layout. Optional front-face-only selection preserves the Lands
ray contract; Builder and seated controls retain two-sided plane selection.

When blocked, first try bounded upward/side offsets in 0.25-block increments,
then a bounded 0.1-block pullback search capped by reach. Retain a safe shift or
distance and require an extra 0.12-block clearance probe before returning by
0.06 blocks. This retains narrow non-monotonic safe gaps instead of assuming
every nearer distance is safe. Safe small motion interpolates for two ticks;
native click targets move immediately. A blocked swept envelope, large turn or
step, or changed layout snaps. Ordinary text/content refresh retains IDs. A fixed
layout may hide every authored control temporarily without discarding its text
or native handles; restoring visibility reuses their identities. A following
layout with no visible geometry, or a truly empty fixed layout, releases its scene.

No-fit searches retry after 250 ms, immediately invalidated by body, context or
layout change; renderer exceptions log once and retry after five seconds.
Records are bounded to the eligible viewers and cleared on lifecycle exit.
Guide suppression uses an 8-degree enter / 13-degree restore margin. The callback
receives its exact old context on restoration before a new context can suppress
another guide. `isLookingAt` remains available even if suppression is disabled.

## Verified variants and ownership

| Existing source | Shared mechanism | Preserved feature-owned behavior and settings |
|---|---|---|
| ArcBuilder `BuilderSelectionActionPanel` | Full runtime with FollowTarget, ENTITY_BOTH, two-column profile | Domain actions/selection and eligibility; exact zero movement tolerance, configurable distance/offsets/spacing/button dimensions/scales; status-only progress and reserved six-plus-two pagination. |
| ArcCivs placement controller | Full runtime with FollowTarget and typed action states | Placement/region authority, confirm/move/rotate/details/cancel and anchor cue. |
| ArcCasino `CasinoActionPanels` (blackjack, poker, durak) | Full runtime with Fixed and ENTITY_RIGHT | Seat/mount/table identity, session token, payment/card gates, visible actions, arbitrary XYZ button rectangles, optional status, source audit and scene audience. Block filtering remains controller-owned. |
| ARC `ClaimLandMenu` | Full runtime with FollowView and EXTERNAL_RAY | Native claim-block gesture/dedupe and land permission rules; 0.03 movement dead zone, front-face-only targeting and guide ownership. |
| ArcDungeons `StaffSpellHud` | `DisplayGeometry.rectangleSamples`, clearance and proportional-scale helpers | Passenger attachment, CENTER billboard, camera-following motion/retreat, one-tick local transforms, hologram/bossbar/off and top/bottom preferences; spell content/hotkeys remain informational. |
| ARC `ChestPreviewPlacement` and `ChestPreviewIcons` | `PacketDisplayScene`, `PaperSpatialSpace`, OBB / translated sweep | Mixed item/text grid topology, shared destination for every part, below-lid/front/above-lid choice, 0.08 side motion clearance, stable anchors, fresh scene for changed container/layout. Rotation must still be checked; translation sweep alone is insufficient. |
| ARC TravelAnchors | Proportional proxy-scale and clearance helpers | Distance/aim emphasis, proxy placement and enlarged rendered footprint; no action menu is fabricated. |

Builder, Casino and Lands share the exact established palette:
normal ARGB `(190,15,23,30)`, hover ARGB `(225,76,57,24)` and gold glow
RGB `(255,204,64)`, full 15/15 brightness, shadowed opaque fixed text and
0.15 native view range. Text styles retain individual scale, line width, culling
dimensions and baseline inset. Builder/Casino retain zero transformation
interpolation; Lands explicitly supplies delay -1/duration 2. Fixed controls
snap; following movement uses the safe two-tick path. Optional guide suppression
never removes the guide's underlying geometry.

## Verification boundaries

| Prior oracle / regression | Shared check |
|---|---|
| Builder odd row, six-plus-two page slots and long/farthest controls | `ActionPanelGeometryTest`: stable page/header geometry and whole-rectangle reach over five yaws |
| Builder/Claim actual-plane hover, edge clicks, near disabled control and occlusion | `ActionPanelGeometryTest`: nearest visible plane, scaled edge/reach and disabled-nearest blocking; `PaperActionPanelsTest`: click-time actual-eye/full-layout and occlusion gates |
| Builder head/crouch stationarity, micro-motion and guide restoration | `PaperActionPanelsTest`: retained IDs/pose, two-tick movement, old/new context suppression order; consumer adapter tests retain exact zero-dead-zone behavior |
| Builder obstacle shift/distance jitter and gradual recovery | `ActionPanelGeometryTest`: bounded search, clearance hysteresis and non-monotonic gap; runtime retry/recovery tests |
| Builder swept sagitta, thin barrier and intermediate rotation | `ActionPanelGeometryTest`: intermediate envelope and small-turn thinness; `DisplayGeometryTest`: pitched OBB false-positive negative control and translated obstacle sweep |
| Builder/Casino stale native IDs and duplicate click routes | `PaperActionPanelsTest`: old context IDs invalidated, text identity retained, 250 ms gate across contexts, cancellation/offhand/owner behavior |
| Casino all-hidden waiting/card-flight transitions | `PaperActionPanelsTest`: fixed text/native IDs survive visible → hidden → visible while the hidden scene accepts no input; empty following geometry still releases its scene |
| Staff/chest arbitrary camera basis and scale | `DisplayGeometryTest`: copied finite orthonormal basis, all card corners, exact clearance and double-to-float proportional scaling; existing consumer motion/low-angle tests remain in their owner |
| Plugin lifecycle / transport ownership | `PaperActionPanelsTest`: target/display cleanup, scoped suppression and borrowed display owner still usable; existing PaperPacketDisplays tests own packet delta/chunk/connection/budget behavior |

These are source and test contracts. Offline composition previews establish only
their sampled geometry. Native-client smoothness, outline compositing, apparent
font size, two-client privacy and reliable real gestures remain separate runtime
acceptance observations; do not infer them from MockBukkit or a build.
