package cn.xiaojia.album;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure scope rules shared by the selector, uploads and account-local cache. */
final class BabyScope {
    private BabyScope() {}

    static String choose(String preferred, Collection<String> accessible) {
        if (accessible == null || accessible.isEmpty()) return "";
        if (preferred != null && !preferred.isEmpty() && accessible.contains(preferred))
            return preferred;
        for (String id : accessible) if (id != null && !id.isEmpty()) return id;
        return "";
    }

    static boolean canUpload(String role) {
        return "admin".equals(role) || "contributor".equals(role);
    }

    static List<String> uploadSelection(Collection<String> chosen, Collection<String> writable) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (chosen != null && writable != null)
            for (String id : chosen)
                if (id != null && !id.isEmpty() && writable.contains(id)) selected.add(id);
        return new ArrayList<>(selected);
    }

    static String cacheScope(String server, String account, String baby) {
        // Length prefixes prevent delimiters in server names or credentials
        // from accidentally producing the same cache namespace.
        return component(server) + component(account) + component(baby);
    }

    private static String component(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }
}
