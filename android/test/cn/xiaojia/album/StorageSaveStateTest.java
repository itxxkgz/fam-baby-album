package cn.xiaojia.album;

public final class StorageSaveStateTest {
    private static int checks;

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Storage save state check failed");
    }

    private static void blocked(StorageSaveState state) {
        try {
            state.begin();
            throw new AssertionError("Ambiguous create was allowed to repeat");
        } catch (IllegalStateException expected) {
            checks++;
        }
    }

    public static void main(String[] args) {
        StorageSaveState fresh = new StorageSaveState(null);
        check(fresh.begin().isEmpty());
        blocked(fresh);
        fresh.saved("saved-location");
        fresh.failed(false); // A later connection test fails after the location was saved.
        check(fresh.isSaved());
        check(!fresh.needsReview());
        check(fresh.begin().equals("saved-location"));

        StorageSaveState rejected = new StorageSaveState("");
        check(rejected.begin().isEmpty());
        rejected.failed(true);
        check(!rejected.needsReview());
        check(rejected.begin().isEmpty());
        rejected.saved("another-location");
        check(rejected.begin().equals("another-location"));

        StorageSaveState lost = new StorageSaveState("");
        lost.begin();
        lost.failed(false); // The response may have been lost after the server committed.
        check(lost.needsReview());
        check(!lost.isSaved());
        blocked(lost);
        lost.failed(true); // A later error must not erase the unresolved result.
        check(lost.needsReview());
        blocked(lost);

        StorageSaveState malformed = new StorageSaveState("");
        malformed.begin();
        try {
            malformed.saved("");
            throw new AssertionError("Missing saved location was accepted");
        } catch (IllegalStateException expected) {
            checks++;
        }
        blocked(malformed);

        StorageSaveState editing = new StorageSaveState("existing-location");
        check(editing.begin().equals("existing-location"));
        editing.failed(false);
        check(!editing.needsReview());
        check(editing.begin().equals("existing-location"));
        System.out.println("Storage save state checks passed: " + checks);
    }
}
