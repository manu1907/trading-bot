package io.github.manu.journal;

public final class JournalException extends RuntimeException {

    public JournalException(String message) {
        super(message);
    }

    public JournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
