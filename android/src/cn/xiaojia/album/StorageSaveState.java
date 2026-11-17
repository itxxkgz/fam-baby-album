package cn.xiaojia.album;

/** Tracks the saved location across a save followed by a fallible connection check. */
final class StorageSaveState {
    private String savedId;
    private boolean creating;
    private boolean uncertain;

    StorageSaveState(String existingId) {
        savedId = existingId == null ? "" : existingId;
    }

    synchronized String begin() {
        if (creating || uncertain) {
            throw new IllegalStateException("保存结果尚未确认，请返回存放位置列表查看。");
        }
        creating = savedId.isEmpty();
        return savedId;
    }

    synchronized void saved(String id) {
        if (id == null || id.isEmpty()) {
            uncertain = creating;
            creating = false;
            throw new IllegalStateException("设置已提交，请返回存放位置列表确认保存结果。");
        }
        savedId = id;
        creating = false;
        uncertain = false;
    }

    synchronized void failed(boolean serverRejected) {
        if (creating) uncertain = !serverRejected;
        creating = false;
    }

    synchronized boolean needsReview() {
        return uncertain;
    }

    synchronized boolean isSaved() {
        return !savedId.isEmpty();
    }
}
