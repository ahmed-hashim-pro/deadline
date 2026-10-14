package io.github.ahmedhashim.deadline;

/** Thrown when work is asked to continue after the caller stopped waiting for it. */
public final class DeadlineExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DeadlineExceededException(String message) {
        super(message, null, false, false);
    }
}
