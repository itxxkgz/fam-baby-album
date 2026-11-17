package cn.xiaojia.album;

/** Confirmed baby-scoped access, with a pending change that can be rolled back. */
final class MemberAccessState {
    private String saved, pending;
    private boolean allowed, busy;

    MemberAccessState(String role, boolean allowed) {
        complete(role, allowed);
    }

    static String relationship(String value) {
        return value == null ? "" : value.trim().replaceAll("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$", "");
    }

    static String management(String role, boolean enabled) {
        return enabled ? "admin" : "admin".equals(role) ? "contributor" : role;
    }

    static String upload(String role, boolean enabled) {
        return "admin".equals(role) ? "admin" : enabled ? "contributor" : "viewer";
    }

    boolean beginToggle(boolean management, boolean enabled) {
        if (busy || !allowed || (!management && "admin".equals(saved))) return false;
        String next = management ? management(saved, enabled) : upload(saved, enabled);
        if (saved.equals(next)) return false;
        pending = next;
        busy = true;
        return true;
    }

    boolean beginSave() {
        if (busy) return false;
        busy = true;
        return true;
    }

    void complete(String role, boolean allowed) {
        saved = "admin".equals(role) || "contributor".equals(role) ? role : "viewer";
        this.allowed = allowed;
        fail();
    }

    void fail() {
        pending = null;
        busy = false;
    }

    String role() {
        return pending == null ? saved : pending;
    }

    boolean busy() {
        return busy;
    }

    boolean managementOn() {
        return "admin".equals(role());
    }

    boolean uploadOn() {
        return !"viewer".equals(role());
    }

    boolean managementEnabled() {
        return allowed && !busy;
    }

    boolean uploadEnabled() {
        return allowed && !busy && !managementOn();
    }
}
