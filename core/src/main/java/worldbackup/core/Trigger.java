package worldbackup.core;

/** Why a snapshot was taken (design/README.md, "Snapshots and triggers"). */
public enum Trigger {
    PRE_UPDATE("pre-update", true),
    MOD_CHANGE("mod-change", true),
    /** The first time WorldBackup sees a world: maybe the only copy from before an update, so kept like one. */
    FIRST_RUN("first-run", true),
    MILESTONE("milestone", false),
    PRE_RESTORE("pre-restore", false),
    SHUTDOWN("shutdown", false),
    SCHEDULED("scheduled", false),
    MANUAL("manual", false);

    public final String id;
    /** Automatic milestones, kept within {@code KeepMilestones}. */
    public final boolean autoMilestone;

    Trigger(String id, boolean autoMilestone) {
        this.id = id;
        this.autoMilestone = autoMilestone;
    }

    public static Trigger of(String id) {
        for (Trigger t : values()) {
            if (t.id.equals(id)) return t;
        }
        throw new IllegalArgumentException("Unknown trigger: " + id);
    }
}
