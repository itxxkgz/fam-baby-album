package cn.xiaojia.album;

/** Exercises confirmed permissions and retries without Android or network state. */
public final class MemberAccessStateTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(String expected, String actual, String message) {
        check(expected.equals(actual), message);
    }

    public static void main(String[] args) {
        MemberAccessState viewer = new MemberAccessState("viewer", true);
        check(!viewer.managementOn() && !viewer.uploadOn(), "仅查看没有管理或上传权限");
        check(viewer.managementEnabled() && viewer.uploadEnabled(), "可分别开启两项权限");
        check(viewer.beginToggle(true, true), "仅查看可以直接开启管理");
        check(viewer.managementOn() && viewer.uploadOn(), "管理包含上传");
        check(viewer.busy() && !viewer.managementEnabled() && !viewer.uploadEnabled(), "请求中禁用两个开关");
        check(!viewer.beginToggle(false, false), "请求期间不能发起第二个权限修改");
        check(!viewer.beginSave(), "请求期间不能提交关系或移出操作");
        viewer.fail();
        equal("viewer", viewer.role(), "失败恢复已确认的仅查看权限");
        check(!viewer.busy() && viewer.managementEnabled() && viewer.uploadEnabled(), "失败后可重试");
        check(viewer.beginToggle(false, true), "重试可开启上传");
        viewer.complete("contributor", true);
        check(!viewer.managementOn() && viewer.uploadOn(), "保存后拥有上传而没有管理");
        check(viewer.beginToggle(true, true), "上传家人可开启管理");
        viewer.complete("admin", true);
        check(viewer.managementOn() && viewer.uploadOn(), "管理保存成功同时保留上传");
        check(viewer.managementEnabled() && !viewer.uploadEnabled(), "管理开启时上传开关锁定");
        check(!viewer.beginToggle(false, false), "不能单独撤去管理员上传能力");
        check(viewer.beginToggle(true, false), "可以关闭管理权限");
        equal("contributor", viewer.role(), "关闭管理保留上传能力");
        viewer.fail();
        equal("admin", viewer.role(), "关闭管理失败恢复管理员状态");
        check(viewer.beginToggle(true, false), "关闭管理失败可重试");
        viewer.complete("contributor", true);
        check(viewer.uploadEnabled(), "管理关闭后可操作上传开关");
        check(viewer.beginToggle(false, false), "可以关闭上传");
        viewer.complete("viewer", true);
        check(!viewer.managementOn() && !viewer.uploadOn(), "关闭上传恢复仅查看");

        for (String role : new String[] {"viewer", "contributor", "admin"}) {
            equal("admin", MemberAccessState.management(role, true), "开启管理必须为宝宝管理员");
            equal(
                    "admin".equals(role) ? "contributor" : role,
                    MemberAccessState.management(role, false),
                    "关闭管理不得额外提升已降权家人");
            equal(
                    "admin".equals(role) ? "admin" : "contributor",
                    MemberAccessState.upload(role, true),
                    "新读取的管理员权限不能被上传操作降级");
            equal(
                    "admin".equals(role) ? "admin" : "viewer",
                    MemberAccessState.upload(role, false),
                    "上传关闭只影响普通家人");
            MemberAccessState protectedMember = new MemberAccessState(role, false);
            check(
                    !protectedMember.managementEnabled() && !protectedMember.uploadEnabled(),
                    "受保护账号两个开关都禁用");
            check(!protectedMember.beginToggle(true, true), "受保护账号拒绝开启管理");
            check(!protectedMember.beginToggle(true, false), "受保护账号拒绝关闭管理");
            check(!protectedMember.beginToggle(false, true), "受保护账号拒绝开启上传");
            check(!protectedMember.beginToggle(false, false), "受保护账号拒绝关闭上传");
            check(protectedMember.beginSave(), "账号保护不妨碍合法修改当前宝宝关系");
            check(!protectedMember.beginSave(), "关系请求也防重复提交");
            protectedMember.fail();
            equal(role, protectedMember.role(), "关系请求失败不改变权限");
        }
        MemberAccessState stale = new MemberAccessState("viewer", true);
        check(stale.beginToggle(false, true), "开始上传权限请求");
        stale.complete("admin", false);
        check(stale.managementOn() && stale.uploadOn(), "以服务端最新权限为准");
        check(!stale.managementEnabled() && !stale.uploadEnabled(), "保存后重新应用最新保护状态");
        check(!new MemberAccessState("unknown", false).uploadOn(), "未知角色不能显示上传授权");

        equal("奶奶", MemberAccessState.relationship("  奶奶 \n"), "关系去除前后空白");
        equal("小姨", MemberAccessState.relationship("\u3000\u00a0小姨\u3000"), "处理手机输入的全角和不换行空格");
        equal("", MemberAccessState.relationship("\t\n\u3000\u00a0"), "全空白关系必须为空");
        equal("", MemberAccessState.relationship(null), "未填关系安全处理");
        equal("姥姥 家", MemberAccessState.relationship("姥姥 家"), "保留关系内部空格");
        System.out.println("Member access checks passed: " + checks);
    }
}
