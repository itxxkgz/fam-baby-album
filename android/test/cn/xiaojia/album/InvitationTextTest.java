package cn.xiaojia.album;

public final class InvitationTextTest {
    private static final String CODE = "Abcdef0123456789_Abcdef0123456789-";
    private static int checks;

    public static void main(String[] args) {
        InvitationText.Values full =
                InvitationText.parse(
                        "邀请你加入家庭相册\n服务器：https://album.example.com/family/\n邀请码："
                                + CODE
                                + "\n有效至：2027年1月1日\n角色：admin\n用户名：someone");
        require(full.server.equals("https://album.example.com/family") && full.code.equals(CODE));
        InvitationText.Values plain = InvitationText.parse("  " + CODE + "  ");
        require(plain.server.isEmpty() && plain.code.equals(CODE));
        InvitationText.Values windows =
                InvitationText.parse("服务地址: http://127.0.0.1:8765\r\n邀请码: " + CODE);
        require(windows.server.equals("http://127.0.0.1:8765") && windows.code.equals(CODE));
        require(InvitationText.parse("邀请码：" + CODE).server.isEmpty());
        reject("服务器：javascript:alert(1)\n邀请码：" + CODE);
        reject("服务器：https://person:password@example.com\n邀请码：" + CODE);
        reject("服务器：https://album.example.com?role=admin\n邀请码：" + CODE);
        reject("角色：admin\npassword：secret\n没有邀请码");
        InvitationText.Values six = InvitationText.parse("123456");
        require(six.server.isEmpty() && six.code.equals("123456"));
        require(InvitationText.parse("000042").code.equals("000042"));
        require(InvitationText.parse("000000").code.equals("000000"));
        require(InvitationText.parse(" 012345 \t").code.equals("012345"));
        require(InvitationText.parse("邀请码：001234").code.equals("001234"));
        InvitationText.Values shortFull =
                InvitationText.parse(
                        "邀请奶奶加入宝宝的家庭相册\n"
                                + "服务器：https://album.example.com/family/\n"
                                + "邀请码：000123\n"
                                + "有效至：2027年1月1日\n"
                                + "已有账号可直接加入");
        require(
                shortFull.server.equals("https://album.example.com/family")
                        && shortFull.code.equals("000123"));
        InvitationText.Values shortWindows =
                InvitationText.parse("相册地址: http://127.0.0.1:8765\r\n邀请码: 010203\r\n请在有效期内加入");
        require(
                shortWindows.server.equals("http://127.0.0.1:8765")
                        && shortWindows.code.equals("010203"));
        reject("服务器：javascript:alert(1)\n邀请码：123456");
        for (String invalid :
                new String[] {
                    "12345",
                    "1234567",
                    "",
                    null,
                    "abcdef",
                    "１２３４５６",
                    "١٢٣٤٥٦",
                    "123 456",
                    "123-456",
                    "123456.0",
                    "+123456",
                    "123456\n7"
                }) reject(invalid);
        for (String invalid :
                new String[] {
                    "邀请码：12345", "邀请码:1234567", "邀请码：１２３４５６", "邀请码：1234567\n有效至：2027年1月1日"
                }) reject(invalid);
        require(InvitationText.parse(repeated('A', 16)).code.equals(repeated('A', 16)));
        require(InvitationText.parse(repeated('Z', 256)).code.equals(repeated('Z', 256)));
        require(InvitationText.parse("0012345678901234").code.equals("0012345678901234"));
        reject(repeated('A', 15));
        reject(repeated('Z', 257));
        require(
                InvitationText.parse("服务器：https://album.example.com\n邀请码：" + repeated('Z', 256))
                        .code
                        .equals(repeated('Z', 256)));
        System.out.println("Invitation parsing checks passed: " + checks);
    }

    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("邀请解析结果不符合预期");
    }

    private static void reject(String input) {
        checks++;
        try {
            InvitationText.parse(input);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("应拒绝无法识别的邀请");
    }

    private static String repeated(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }
}
