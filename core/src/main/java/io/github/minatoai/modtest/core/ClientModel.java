package io.github.minatoai.modtest.core;

import java.util.List;

/**
 * The client abstraction the executor drives.
 *
 * <p>The protocol core never talks to Minecraft directly: a mod adapter implements this interface
 * (the Forge glue in `forge/` does), and tests implement it with a fake. Everything the executor
 * can change about the game goes through here, which keeps the safety policy (see {@link Guard})
 * in one place.
 */
public interface ClientModel {

    // ---- state ----------------------------------------------------------
    double x();

    double y();

    double z();

    float yaw();

    float pitch();

    String dimension();

    String heldItemId();

    /**
     * The off-hand item id, or {@code ""} when that hand is empty.
     *
     * <p>{@code use.item} takes a {@code hand} parameter, so the item the receipt reports
     * ({@code heldBefore}/{@code heldAfter}) has to come from the hand the ticket named. Reporting the
     * main hand for an {@code hand:"off"} request would describe a different op. The default is
     * {@code ""} (unknown reads as empty) so existing adapters keep compiling; an adapter that can see
     * the off hand must override it, and {@code state.query} exposes it so an agent can actually verify
     * an off-hand dispatch instead of trusting the request.
     */
    default String offHandItemId() {
        return "";
    }

    /** Teleport/rotate and run the settle loop; {@link #settled()} reports whether it converged. */
    void teleport(double x, double y, double z, float yaw, float pitch, int settleMs);

    boolean settled();

    // ---- inventory ------------------------------------------------------
    /** Slot contents by index; {@code ""} means empty. Armor slots are 36..39, offhand 40. */
    List<String> inventory();

    /**
     * How many client ticks after a join, a dimension change, or a container action this client's view may
     * still be stale.
     *
     * <p>Counted in **ticks the client actually ran**, never in wall-clock time. That distinction is a
     * defect fix (P12): the first version of this window used {@code System.currentTimeMillis()}, and on a
     * real client an empty hand was still reported as {@code container-not-synced} four seconds later —
     * i.e. the conservative answer never converged, so {@code empty-hand} was unreachable and a caller
     * could wait forever. A tick-counted window always closes while the client keeps running.
     */
    int CONTAINER_SYNC_WINDOW_TICKS = 20;

    /**
     * Age, in client ticks, of the newest reason this adapter's container view may be stale — a join or
     * world change, or a click/toss this client dispatched whose answer has not arrived — or
     * {@link Long#MAX_VALUE} when it knows of none.
     *
     * <p>Two properties are required, both from P12:
     * <ol>
     *   <li><b>Conservative inside the window:</b> while the age is below
     *       {@link #CONTAINER_SYNC_WINDOW_TICKS}, core MUST NOT turn an unread slot or hand into a factual
     *       negative ("the slot is empty"); it answers "cannot determine";</li>
     *   <li><b>Bounded convergence:</b> the age MUST grow as the client keeps ticking, so the window
     *       always closes and the plain refusal ({@code reason:"empty-hand"}, {@code "slot-empty"}, …)
     *       becomes reachable. An adapter that can see the answer arrive — e.g. the open menu's state id
     *       moved after a click — SHOULD close the window immediately: that is evidence, not a timer.</li>
     * </ol>
     * The default is "settled" ({@code Long.MAX_VALUE}): a test double's state is exact, and an adapter
     * that models no race window must not become unable to refuse anything.
     */
    default long containerSyncAgeTicks() {
        return Long.MAX_VALUE;
    }

    /**
     * The conservative gate core applies, derived from {@link #containerSyncAgeTicks()}.
     *
     * <p>Core reads the age; this boolean exists so a receipt or a reader can talk about the same state
     * without repeating the comparison.
     */
    default boolean containerSyncPending() {
        return containerSyncAgeTicks() < CONTAINER_SYNC_WINDOW_TICKS;
    }

    boolean usingItem();

    void releaseUsingItem();

    void selectSlot(int slot);

    void clickSlot(int slot, int button, String mode);

    void tossSlot(int slot, int count);

    /**
     * The open container's window id, or {@code -1} when no container is open.
     *
     * <p>Part of the core-observable surface of {@code inv.click}/{@code inv.toss}: a click must name
     * the window it targets, and a stale window id is a precondition failure rather than a silent
     * no-op. Defaults to {@code -1} so an adapter that has not wired it yet reports "no container".
     */
    default int windowId() {
        return -1;
    }

    /** Remaining item-cooldown ticks; {@code 0} when usable. Informational for {@code use.item}. */
    default int cooldownTicks() {
        return 0;
    }

    // ---- interaction ----------------------------------------------------
    /** Use the held item. Implementations may refuse (already using, no item, wrong state). */
    void useItem();

    /**
     * Use the item in the requested hand.
     *
     * <p>{@code use.item} takes a {@code hand} parameter, so an adapter that can honour it must
     * override this; the default keeps every existing implementation (and test double) working by
     * falling back to the main hand. Without this, {@code hand:"off"} would be accepted by the guard
     * and then silently dispatched from the wrong hand.
     */
    default void useItem(boolean offHand) {
        useItem();
    }

    /**
     * Whether the held item is currently on cooldown.
     *
     * <p>Separate from {@link #cooldownTicks()} because an adapter may know "on cooldown" without
     * knowing the exact remainder (Minecraft's {@code ItemCooldowns} exposes a percentage, not ticks).
     * The default is {@code false}: an adapter that cannot tell must not block a use it cannot judge.
     */
    default boolean itemOnCooldown() {
        return false;
    }

    /**
     * Whether a cell is non-air, as this client sees it.
     *
     * <p><b>Not a placement gate.</b> {@code world.place} used to refuse on this before the interaction,
     * which made the adapter's precise replaceability check unreachable and wrongly refused replaceable
     * targets (tall grass, snow layers) that a player can place into. Replaceability is now decided by the
     * adapter from the block state itself; this read remains what it is — the client's own view, used for
     * the receipt's read-back.
     */
    boolean cellOccupied(int x, int y, int z);

    /**
     * The block id at a position, {@code ""} for air, or {@code null} when this adapter cannot report it.
     *
     * <p>{@code world.place} uses this to read back what the client's own world now shows, instead of
     * asserting {@code placed:true} from the request (P10: an unconditional self-report is not evidence).
     * {@code null} means "cannot witness", which must be reported as such and never as a measurement.
     */
    default String blockIdAt(int x, int y, int z) {
        return null;
    }

    /**
     * Place a block <b>the way a player does</b>: aim at a neighbouring block's face and use the item in
     * the selected slot.
     *
     * <p>P11: {@code world.place} used to write the world directly, so no player interaction ever happened
     * — the server, Forge events, KubeJS {@code BlockEvents.placed} and protection plugins all saw
     * nothing. The implementation MUST therefore go through the client's real interaction entry
     * ({@code MultiPlayerGameMode.useItemOn}), which predicts locally and sends the interaction packet to
     * the server, where the ordinary placement path runs.
     *
     * <p>That path has consequences the op must not paper over: the block has to be **in the player's
     * hand**, and the target has to be **within the player's block reach** with a neighbouring block to
     * place against. An adapter refuses with {@code E_PRECONDITION} when any of that does not hold; it
     * must never conjure the requested block into the world by itself.
     */
    void useItemOnBlock(int x, int y, int z, String block);

    /**
     * Direct world write. <b>This is not a placement path.</b>
     *
     * <p>It exists only so that the bypass removed in P11 stays detectable: the default refuses, the
     * Forge adapter does not implement it, and no op may call it. A caller that reaches this method is
     * writing the world without a player interaction — precisely the defect this method now exists to
     * make impossible (and so a test can prove it).
     */
    @Deprecated
    default void placeBlock(int x, int y, int z, String block) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "direct world writes are not available: world.place goes through the player-interaction "
                        + "path (useItemOnBlock)");
    }

    // ---- telemetry ------------------------------------------------------
    record BenchSample(double fpsMedian, double frameMsP95, double onePercentLow) {
    }

    /**
     * The <b>byte-level facts</b> of one captured frame.
     *
     * <p>Deliberately carries no interpretation of the image: {@code shot.capture} may only report
     * "these bytes exist", never "the scene looks like X". {@code sha256} is the lowercase hex digest
     * of {@code bytes}.
     */
    record CapturedFrame(byte[] bytes, String format, int width, int height, String sha256, long tick,
                         String path) {
    }

    /**
     * One frame-time sampling window, as actually measured.
     *
     * <p>Deliberately carries the <b>window</b> ({@code warmupFrames}/{@code sampleFrames}/
     * {@code sampleCount}/{@code windowMs}), the unitless-plus-united values and the per-frame sample
     * file, because a bare "fps" number without a window is not interpretable.
     */
    record BenchResult(int warmupFrames, int sampleFrames, int sampleCount, long windowMs,
                       double fpsMedian, double frameMsP95, double onePercentLow, String samplesPath) {
    }

    /** Legacy screenshot entry point: returns a path only, with no byte facts attached. */
    String captureScreenshot(String name);

    BenchSample bench(int warmupFrames, int sampleFrames);

    /**
     * Capture one rendered frame and report its bytes. Default: refuse, because an adapter that has
     * not implemented it must not hand back an empty image as if it were a capture.
     */
    default CapturedFrame capture(String name) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "frame capture is not implemented by this client adapter");
    }

    /**
     * Sample this client's frame durations. Default: refuse, because a receipt must never carry
     * invented numbers.
     */
    default BenchResult benchWindow(int warmupFrames, int sampleFrames) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "frame-time sampling is not implemented by this client adapter");
    }

    void waitFrames(int frames);
}
