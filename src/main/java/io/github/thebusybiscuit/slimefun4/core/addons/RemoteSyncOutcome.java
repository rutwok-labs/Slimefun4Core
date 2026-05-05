package io.github.thebusybiscuit.slimefun4.core.addons;

/**
 * Outcome of a remote config synchronization run.
 */
public enum RemoteSyncOutcome {
    UPDATED,
    UNCHANGED,
    EMPTY_REMOTE,
    FAILED,
    DISABLED
}
