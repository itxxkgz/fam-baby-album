package cn.xiaojia.album;

import java.util.Arrays;
import java.util.Collections;

public final class BabyScopeTest {
    private static int checks;
    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        expect(BabyScope.choose("second", Arrays.asList("first", "second")).equals("second"),
                "应保留仍然可访问的宝宝");
        expect(BabyScope.choose("removed", Arrays.asList("first", "second")).equals("first"),
                "撤销访问后应重新选择可访问的宝宝");
        expect(BabyScope.choose("old", Collections.emptyList()).isEmpty(), "没有权限时不能沿用旧选择");
        expect(BabyScope.choose(null, Arrays.asList(null, "", "first")).equals("first"), "空宝宝记录不能成为选择");
        expect(BabyScope.canUpload("contributor") && BabyScope.canUpload("admin"), "可上传角色应能上传");
        expect(!BabyScope.canUpload("viewer") && !BabyScope.canUpload(null), "未知或仅查看角色不能上传");
        expect(BabyScope.uploadSelection(Arrays.asList("first", "denied", "first", "second"),
                Arrays.asList("first", "second")).equals(Arrays.asList("first", "second")), "合照归属必须去重且只能使用可上传宝宝");
        expect(BabyScope.uploadSelection(Collections.singletonList("denied"),
                Collections.singletonList("first")).isEmpty(), "不能把无权限归属自动改成另一位宝宝");
        expect(!BabyScope.cacheScope("server", "account", "first").equals(
                BabyScope.cacheScope("server", "account", "second")), "宝宝之间缓存必须隔离");
        expect(!BabyScope.cacheScope("server", "account", "first").equals(
                BabyScope.cacheScope("server", "another-account", "first")), "账号之间缓存必须隔离");
        expect(!BabyScope.cacheScope("a|b", "c", "d").equals(
                BabyScope.cacheScope("a", "b|c", "d")), "缓存范围不能发生分隔符碰撞");
        System.out.println("宝宝范围检查通过，共" + checks + "项。");
    }
}
