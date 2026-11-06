package cz.failxos.mwn.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Universal text engine: legacy (&amp; and section), hex (&amp;#RRGGBB), MiniMessage (gradients, rainbow, click, hover...). */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern SECTION_HEX = Pattern.compile("&[xX]((?:&[0-9a-fA-F]){6})");
    private static final String COLORS = "0123456789abcdef";
    private static final String[] COLOR_TAGS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};

    private Text() {}

    public static String legacyToMini(String in) {
        if (in == null) return "";
        String s = in.replace('\u00a7', '&');
        Matcher m = SECTION_HEX.matcher(s);
        StringBuilder tmp = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(tmp, Matcher.quoteReplacement("<#" + m.group(1).replace("&", "") + ">"));
        }
        m.appendTail(tmp);
        s = HEX.matcher(tmp.toString()).replaceAll("<#$1>");
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char n = Character.toLowerCase(s.charAt(i + 1));
                int ci = COLORS.indexOf(n);
                String tag = null;
                if (ci >= 0) {
                    tag = "<reset><" + COLOR_TAGS[ci] + ">";
                } else {
                    switch (n) {
                        case 'l' -> tag = "<bold>";
                        case 'o' -> tag = "<italic>";
                        case 'n' -> tag = "<underlined>";
                        case 'm' -> tag = "<strikethrough>";
                        case 'k' -> tag = "<obfuscated>";
                        case 'r' -> tag = "<reset>";
                        default -> { }
                    }
                }
                if (tag != null) {
                    sb.append(tag);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static Component parse(String s, TagResolver... resolvers) {
        return MM.deserialize(legacyToMini(s), resolvers);
    }

    public static String plain(String s) {
        return PlainTextComponentSerializer.plainText().serialize(parse(s));
    }
}
