package cn.xiaojia.album;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only the server address and single-use code from an invitation. */
final class InvitationText {
    private static final String CODE_FORMAT = "(?:[0-9]{6}|[A-Za-z0-9_-]{16,256})";
    private static final Pattern CODE = Pattern.compile(CODE_FORMAT);
    private static final Pattern ADDRESS_LINE =
            Pattern.compile("^(?:服务器|服务器地址|服务地址|相册地址)\\s*[:：]\\s*(\\S+)\\s*$");
    private static final Pattern CODE_LINE =
            Pattern.compile("^邀请码\\s*[:：]\\s*(" + CODE_FORMAT + ")\\s*$");

    static final class Values {
        final String server;
        final String code;

        Values(String server, String code) {
            this.server = server;
            this.code = code;
        }
    }

    static Values parse(String text) {
        String input = text == null ? "" : text.trim();
        if (CODE.matcher(input).matches()) return new Values("", input);
        String server = "", code = "";
        for (String rawLine : input.replace("\r", "").split("\n")) {
            String line = rawLine.trim();
            Matcher address = ADDRESS_LINE.matcher(line), invitation = CODE_LINE.matcher(line);
            if (address.matches()) server = address.group(1);
            else if (invitation.matches()) code = invitation.group(1);
        }
        if (code.isEmpty()) throw new IllegalArgumentException("请输入6位邀请码，也可粘贴完整邀请或以前的长邀请码");
        if (!server.isEmpty()) {
            try {
                URI uri = new URI(server);
                if (!("https".equalsIgnoreCase(uri.getScheme())
                                || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getHost().isEmpty()
                        || uri.getUserInfo() != null
                        || uri.getQuery() != null
                        || uri.getFragment() != null) throw new IllegalArgumentException();
                server = server.replaceAll("/+$", "");
            } catch (Exception error) {
                throw new IllegalArgumentException("邀请中的服务器地址不正确，请向家人确认");
            }
        }
        return new Values(server, code);
    }
}
