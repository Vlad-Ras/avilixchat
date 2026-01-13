package com.roften.multichat.compat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;


/**
 * Minimal legacy formatting parser.
 *
 * <p>Supported:
 * <ul>
 *   <li>Vanilla legacy: {@code §a} / {@code &a}, {@code §l} / {@code &l}, etc.</li>
 *   <li>Hex: {@code §#RRGGBB} / {@code &#RRGGBB}</li>
 *   <li>Bungee/Spigot hex: {@code §x§R§R§G§G§B§B} / {@code &x&R&R&G&G&B&B}</li>
 *   <li>Plain hex tokens: {@code #RRGGBB} (useful for players who paste gradients without &/§)</li>
 * </ul>
 *
 * <p>This keeps the mod dependency-free (no Adventure / MiniMessage).
 */
public final class LegacyComponentParser {
    private LegacyComponentParser() {}

    public static Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        // Normalize & -> § (but keep escaped \&).
        String s = input.replace("\\&", "&").replace('&', '§');

        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                // Flush buffer
                if (buf.length() > 0) {
                    out = out.append(Component.literal(buf.toString()).withStyle(style));
                    buf.setLength(0);
                }

                char code = Character.toLowerCase(s.charAt(i + 1));

                // Bungee/Spigot hex in form: §x§R§R§G§G§B§B
                if (code == 'x' && i + 13 < s.length()) {
                    // expected: § x § r § r § g § g § b § b  (14 chars including current §)
                    // positions of hex digits: i+3, i+5, i+7, i+9, i+11, i+13
                    if (s.charAt(i + 2) == '§'
                            && s.charAt(i + 4) == '§'
                            && s.charAt(i + 6) == '§'
                            && s.charAt(i + 8) == '§'
                            && s.charAt(i + 10) == '§'
                            && s.charAt(i + 12) == '§') {
                        String hex = "" + s.charAt(i + 3) + s.charAt(i + 5) + s.charAt(i + 7)
                                + s.charAt(i + 9) + s.charAt(i + 11) + s.charAt(i + 13);
                        if (hex.matches("[0-9a-fA-F]{6}")) {
                            int rgb = Integer.parseInt(hex, 16);
                            style = style.withColor(TextColor.fromRgb(rgb));
                            i += 13;
                            continue;
                        }
                    }
                }

                // Hex in form: §#RRGGBB (after normalization from &#RRGGBB)
                if (code == '#' && i + 7 < s.length()) {
                    String hex = s.substring(i + 2, i + 8);
                    if (hex.matches("[0-9a-fA-F]{6}")) {
                        int rgb = Integer.parseInt(hex, 16);
                        style = style.withColor(TextColor.fromRgb(rgb));
                        i += 7;
                        continue;
                    }
                }

                ChatFormatting fmt = ChatFormatting.getByCode(code);
                if (fmt != null) {
                    if (fmt == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (fmt.isColor()) {
                        style = style.withColor(fmt);
                        // Reset other decorations on color change like vanilla legacy formatting does.
                        style = style.withBold(false).withItalic(false).withUnderlined(false)
                                .withStrikethrough(false).withObfuscated(false);
                    } else {
                        style = switch (fmt) {
                            case BOLD -> style.withBold(true);
                            case ITALIC -> style.withItalic(true);
                            case UNDERLINE -> style.withUnderlined(true);
                            case STRIKETHROUGH -> style.withStrikethrough(true);
                            case OBFUSCATED -> style.withObfuscated(true);
                            default -> style;
                        };
                    }
                    i++;
                    continue;
                }

                // Unknown code - keep literally.
                buf.append(c);
                continue;
            }

            // Plain hex token: #RRGGBB
            if (c == '#' && i + 6 < s.length()) {
                String hex = s.substring(i + 1, i + 7);
                if (hex.matches("[0-9a-fA-F]{6}")) {
                    if (buf.length() > 0) {
                        out = out.append(Component.literal(buf.toString()).withStyle(style));
                        buf.setLength(0);
                    }
                    int rgb = Integer.parseInt(hex, 16);
                    style = style.withColor(TextColor.fromRgb(rgb));
                    i += 6;
                    continue;
                }
            }

            buf.append(c);
        }

        if (buf.length() > 0) {
            out = out.append(Component.literal(buf.toString()).withStyle(style));
        }

        return out;
    }
}
