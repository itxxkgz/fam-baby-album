package cn.xiaojia.album;

/** One baby's confirmed access and a pending change; empty access is not a viewer role. */
final class ChildAccessState {
    static final int VIEW = 0, MANAGEMENT = 1, UPLOAD = 2;
    private String saved, pending;
    private boolean allowed, busy;

    ChildAccessState(String role, boolean allowed) {
        complete(role, allowed);
    }

    static String normalize(String role) {
        return "viewer".equals(role) || "contributor".equals(role) || "admin".equals(role)
                ? role
                : "";
    }

    static String next(String role, int control, boolean enabled) {
        role = normalize(role);
        if (control == VIEW) return enabled ? role.isEmpty() ? "viewer" : role : "";
        if (role.isEmpty()) return "";
        if (control == MANAGEMENT) return MemberAccessState.management(role, enabled);
        if (control == UPLOAD) return MemberAccessState.upload(role, enabled);
        return role;
    }

    boolean begin(int control, boolean enabled) {
        if (!enabled(control)) return false;
        String change = next(saved, control, enabled);
        if (change.equals(saved)) return false;
        pending = change;
        busy = true;
        return true;
    }

    boolean enabled(int control) {
        if (!allowed || busy) return false;
        if (control == VIEW) return true;
        if (role().isEmpty()) return false;
        return control == MANAGEMENT || control == UPLOAD && !"admin".equals(role());
    }

    boolean checked(int control) {
        String role = role();
        if (control == VIEW) return !role.isEmpty();
        if (control == MANAGEMENT) return "admin".equals(role);
        return control == UPLOAD && ("admin".equals(role) || "contributor".equals(role));
    }

    String role() {
        return pending == null ? saved : pending;
    }

    boolean busy() {
        return busy;
    }

    void fail() {
        pending = null;
        busy = false;
    }

    void complete(String role, boolean allowed) {
        saved = normalize(role);
        this.allowed = allowed;
        fail();
    }
}
