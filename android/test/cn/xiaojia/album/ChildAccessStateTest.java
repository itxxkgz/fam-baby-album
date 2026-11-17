package cn.xiaojia.album;

public final class ChildAccessStateTest {
    private static int checks;

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Child access check failed: " + checks);
    }

    public static void main(String[] args) {
        ChildAccessState state = new ChildAccessState("", true);
        check(state.role().isEmpty());
        check(!state.checked(ChildAccessState.VIEW));
        check(!state.enabled(ChildAccessState.MANAGEMENT));
        check(!state.enabled(ChildAccessState.UPLOAD));
        check(!state.begin(ChildAccessState.UPLOAD, true));
        check(state.begin(ChildAccessState.VIEW, true));
        check(state.role().equals("viewer"));
        check(state.busy());
        check(!state.begin(ChildAccessState.MANAGEMENT, true));
        state.fail();
        check(state.role().isEmpty());
        check(!state.busy());
        state.complete("viewer", true);
        check(state.checked(ChildAccessState.VIEW));
        check(!state.checked(ChildAccessState.UPLOAD));
        check(state.begin(ChildAccessState.UPLOAD, true));
        check(state.role().equals("contributor"));
        state.complete("contributor", true);
        check(state.begin(ChildAccessState.MANAGEMENT, true));
        check(state.checked(ChildAccessState.UPLOAD));
        state.complete("admin", true);
        check(!state.enabled(ChildAccessState.UPLOAD));
        check(!state.begin(ChildAccessState.UPLOAD, false));
        check(state.role().equals("admin"));
        check(state.begin(ChildAccessState.MANAGEMENT, false));
        check(state.role().equals("contributor"));
        state.complete("contributor", true);
        check(state.begin(ChildAccessState.UPLOAD, false));
        check(state.role().equals("viewer"));
        state.complete("viewer", true);
        check(state.begin(ChildAccessState.VIEW, false));
        check(state.role().isEmpty());
        state.fail();
        check(state.role().equals("viewer"));
        state.complete("admin", false);
        check(!state.begin(ChildAccessState.VIEW, false));
        check(!state.begin(ChildAccessState.MANAGEMENT, false));
        check(!state.begin(ChildAccessState.UPLOAD, false));
        check(state.role().equals("admin"));
        for (String role : new String[] {"", "viewer", "contributor", "admin"}) {
            check(ChildAccessState.next(role, ChildAccessState.VIEW, false).isEmpty());
            check(!ChildAccessState.next(role, ChildAccessState.VIEW, true).isEmpty());
            check(ChildAccessState.next(role, 99, true).equals(role));
        }
        check(ChildAccessState.next("", ChildAccessState.MANAGEMENT, true).isEmpty());
        check(ChildAccessState.next("", ChildAccessState.UPLOAD, true).isEmpty());
        check(ChildAccessState.next("admin", ChildAccessState.UPLOAD, false).equals("admin"));
        check(
                ChildAccessState.next("admin", ChildAccessState.MANAGEMENT, false)
                        .equals("contributor"));
        check(
                ChildAccessState.next("contributor", ChildAccessState.MANAGEMENT, false)
                        .equals("contributor"));
        check(ChildAccessState.next("viewer", ChildAccessState.MANAGEMENT, false).equals("viewer"));
        check(ChildAccessState.normalize(null).isEmpty());
        check(ChildAccessState.normalize("unexpected").isEmpty());
        System.out.println("Baby access checks passed: " + checks);
    }
}
