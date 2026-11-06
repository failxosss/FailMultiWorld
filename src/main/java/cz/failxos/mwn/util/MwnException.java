package cz.failxos.mwn.util;

/** Exception carrying a message key (from messages.yml) and its arguments. */
public final class MwnException extends Exception {
    public final String key;
    public final Object[] args;

    public MwnException(String key, Object... args) {
        super(key, null, false, false);
        this.key = key;
        this.args = args;
    }
}
