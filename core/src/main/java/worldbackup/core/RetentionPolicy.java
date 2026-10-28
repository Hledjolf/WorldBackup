package worldbackup.core;

/** A world's counts (design/settings.md). The space cap is machine-wide: {@link StoreSettings}. */
public final class RetentionPolicy {
    /** The newest scheduled snapshots, all kept. */
    public int keepScheduled = 24;
    /** After those, the last scheduled snapshot of each local calendar day, for this many days that have one. */
    public int keepDaily = 7;
    /** After those, the last of each ISO week, for this many weeks that have one. */
    public int keepWeekly = 4;
    public int keepShutdown = 2;
    /** Automatic milestones (pre-update, mod-change) kept per world; 0 keeps all. */
    public int keepMilestones = 3;

    /** The most scheduled and shutdown snapshots these counts keep of one world. */
    public int countedSnapshots() {
        return keepScheduled + keepDaily + keepWeekly + keepShutdown;
    }
}
