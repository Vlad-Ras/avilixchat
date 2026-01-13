package com.roften.multichat.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dependency-free MiniMessage subset parser.
 *
 * <p>LuckPerms prefixes are often MiniMessage, e.g. {@code <red><bold>Admin</bold></red>}.
 * NeoForge servers do not ship Kyori Adventure by default, so we implement a small subset
 * and convert it into legacy codes, which are then parsed by {@link LegacyComponentParser}.
 */
public final class MiniMessageComponentParser {
    private MiniMessageComponentParser() {}

    private static final Map<String, String> COLOR_TAG_TO_LEGACY = Map.ofEntries(
            Map.entry("black", "§0"),
            Map.entry("dark_blue", "§1"),
            Map.entry("dark_green", "§2"),
            Map.entry("dark_aqua", "§3"),
            Map.entry("dark_red", "§4"),
            Map.entry("dark_purple", "§5"),
            Map.entry("gold", "§6"),
            Map.entry("gray", "§7"),
            Map.entry("grey", "§7"),
            Map.entry("dark_gray", "§8"),
            Map.entry("dark_grey", "§8"),
            Map.entry("blue", "§9"),
            Map.entry("green", "§a"),
            Map.entry("aqua", "§b"),
            Map.entry("red", "§c"),
            Map.entry("light_purple", "§d"),
            Map.entry("yellow", "§e"),
            Map.entry("white", "§f")
    );

    private static final Map<String, String> DECORATION_TAG_TO_LEGACY = Map.ofEntries(
            Map.entry("bold", "§l"),
            Map.entry("b", "§l"),
            Map.entry("italic", "§o"),
            Map.entry("i", "§o"),
            Map.entry("underlined", "§n"),
            Map.entry("underline", "§n"),
            Map.entry("u", "§n"),
            Map.entry("strikethrough", "§m"),
            Map.entry("st", "§m"),
            Map.entry("obfuscated", "§k"),
            Map.entry("magic", "§k"),
            Map.entry("k", "§k"),
            Map.entry("reset", "§r"),
            Map.entry("r", "§r")
    );

    private static final class State {
        // "§c" or "§#RRGGBB"
        String color = null;
        boolean bold, italic, underlined, strikethrough, obfuscated;

        State copy() {
            State s = new State();
            s.color = this.color;
            s.bold = this.bold;
            s.italic = this.italic;
            s.underlined = this.underlined;
            s.strikethrough = this.strikethrough;
            s.obfuscated = this.obfuscated;
            return s;
        }

        String toLegacyCodes() {
            StringBuilder sb = new StringBuilder();
            if (color != null) sb.append(color);
            if (obfuscated) sb.append("§k");
            if (bold) sb.append("§l");
            if (strikethrough) sb.append("§m");
            if (underlined) sb.append("§n");
            if (italic) sb.append("§o");
            return sb.toString();
        }

        void resetAll() {
            color = null;
            bold = italic = underlined = strikethrough = obfuscated = false;
        }
    }

    private static final class Frame {
        final String tag;
        final State prev;

        Frame(String tag, State prev) {
            this.tag = tag;
            this.prev = prev;
        }
    }

    public static MutableComponent parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        // Expand common LuckPerms MiniMessage gradients/rainbow into legacy-friendly per-character hex tokens.
        // We keep this dependency-free and intentionally support only the simple, non-nested forms
        // that are typical for prefixes.
        input = expandGradientsAndRainbow(input);

        StringBuilder out = new StringBuilder(input.length() + 16);
        Deque<Frame> stack = new ArrayDeque<>();
        State cur = new State();

        int i = 0;
        while (i < input.length()) {
            char ch = input.charAt(i);

            if (ch == '<') {
                int gt = input.indexOf('>', i + 1);
                if (gt == -1) {
                    out.append(ch);
                    i++;
                    continue;
                }

                String raw = input.substring(i + 1, gt).trim();
                i = gt + 1;

                if (raw.isEmpty()) continue;

                // closing tag
                if (raw.startsWith("/")) {
                    String closeName = raw.substring(1).trim().toLowerCase(Locale.ROOT);
                    Frame match = null;
                    Iterator<Frame> it = stack.iterator();
                    while (it.hasNext()) {
                        Frame f = it.next();
                        if (f.tag.equals(closeName)) match = f;
                    }
                    if (match != null) {
                        while (!stack.isEmpty()) {
                            Frame top = stack.removeLast();
                            if (top == match) break;
                        }
                        cur = match.prev.copy();
                        out.append("§r").append(cur.toLegacyCodes());
                    }
                    continue;
                }

                String tagName = raw;
                String args = null;
                int colon = raw.indexOf(':');
                if (colon >= 0) {
                    tagName = raw.substring(0, colon).trim();
                    args = raw.substring(colon + 1).trim();
                }
                tagName = tagName.toLowerCase(Locale.ROOT);

                // strip non-style tags (keep basic linebreak)
                if (tagName.equals("click") || tagName.equals("hover") || tagName.equals("insertion")
                        || tagName.equals("font") || tagName.equals("lang")
                        || tagName.equals("transition") || tagName.equals("new")) {
                    continue;
                }
                if (tagName.equals("br")) {
                    out.append("\n");
                    continue;
                }

                // save state for restoration on closing tag
                stack.addLast(new Frame(tagName, cur.copy()));

                if (tagName.equals("reset") || tagName.equals("r")) {
                    cur.resetAll();
                    out.append("§r");
                    continue;
                }

                // hex <#RRGGBB>
                if (tagName.startsWith("#") && tagName.length() == 7 && isHex6(tagName.substring(1))) {
                    cur.color = "§#" + tagName.substring(1);
                    // vanilla behavior: decorations reset on color change
                    cur.bold = cur.italic = cur.underlined = cur.strikethrough = cur.obfuscated = false;
                    out.append(cur.color);
                    continue;
                }

                // <color:#RRGGBB> or <color:red>
                if (tagName.equals("color") && args != null) {
                    String a = args.trim().toLowerCase(Locale.ROOT);
                    if (a.startsWith("#") && a.length() == 7 && isHex6(a.substring(1))) {
                        cur.color = "§#" + a.substring(1);
                        cur.bold = cur.italic = cur.underlined = cur.strikethrough = cur.obfuscated = false;
                        out.append(cur.color);
                        continue;
                    }
                    String legacy = COLOR_TAG_TO_LEGACY.get(a);
                    if (legacy != null) {
                        cur.color = legacy;
                        cur.bold = cur.italic = cur.underlined = cur.strikethrough = cur.obfuscated = false;
                        out.append(legacy);
                        continue;
                    }
                }

                // named color tag
                String legacyColor = COLOR_TAG_TO_LEGACY.get(tagName);
                if (legacyColor != null) {
                    cur.color = legacyColor;
                    cur.bold = cur.italic = cur.underlined = cur.strikethrough = cur.obfuscated = false;
                    out.append(legacyColor);
                    continue;
                }

                // decoration tag
                String legacyDec = DECORATION_TAG_TO_LEGACY.get(tagName);
                if (legacyDec != null) {
                    switch (legacyDec) {
                        case "§l" -> cur.bold = true;
                        case "§o" -> cur.italic = true;
                        case "§n" -> cur.underlined = true;
                        case "§m" -> cur.strikethrough = true;
                        case "§k" -> cur.obfuscated = true;
                        default -> {
                        }
                    }
                    out.append(legacyDec);
                    continue;
                }

                // unknown tag -> ignore (will restore on close if present)
                continue;
            }

            out.append(ch);
            i++;
        }

        Component parsed = LegacyComponentParser.parse(out.toString());
        return parsed.copy();
    }

    /**
     * Heuristic for AUTO detection.
     */
    public static boolean looksLikeMiniMessage(String s) {
        if (s == null) return false;
        int lt = s.indexOf('<');
        int gt = s.indexOf('>');
        return lt >= 0 && gt > lt;
    }

    private static boolean isHex6(String s) {
        if (s.length() != 6) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Expands simple (non-nested) MiniMessage gradients/rainbow into per-character plain hex tokens.
     *
     * Supported:
     * - <gradient:#RRGGBB:#RRGGBB>Text</gradient>
     * - <gradient:#RRGGBB:#RRGGBB:#RRGGBB>Text</gradient> (multi-stop)
     * - <rainbow>Text</rainbow>
     *
     * This is intentionally minimal and geared toward LuckPerms prefix strings.
     */
    private static String expandGradientsAndRainbow(String input) {
        if (input == null || input.isEmpty()) return input;

        String s = input;

        // Expand gradients
        int safety = 0;
        while (s.contains("<gradient") && safety++ < 32) {
            int open = s.indexOf("<gradient");
            int gt = s.indexOf('>', open);
            if (open < 0 || gt < 0) break;

            int close = s.indexOf("</gradient>", gt);
            if (close < 0) break;

            String header = s.substring(open + 1, gt).trim(); // gradient:...
            String inner = s.substring(gt + 1, close);

            // Parse args: gradient:... or gradient ...
            String args = null;
            int colon = header.indexOf(':');
            if (colon >= 0) args = header.substring(colon + 1).trim();

            List<Integer> stops = new ArrayList<>();
            if (args != null && !args.isBlank()) {
                for (String part : args.split(":")) {
                    String p = part.trim();
                    if (p.startsWith("#") && p.length() == 7 && isHex6(p.substring(1))) {
                        stops.add(Integer.parseInt(p.substring(1), 16));
                    }
                }
            }

            String expanded = inner;
            if (stops.size() >= 2) {
                // Strip other tags inside the gradient region to avoid breaking formatting.
                String plain = inner.replaceAll("<[^>]+>", "");
                expanded = applyMultiStopGradientPlainHex(plain, stops);
            }

            s = s.substring(0, open) + expanded + s.substring(close + "</gradient>".length());
        }

        // Expand rainbow
        safety = 0;
        while (s.contains("<rainbow") && safety++ < 32) {
            int open = s.indexOf("<rainbow");
            int gt = s.indexOf('>', open);
            if (open < 0 || gt < 0) break;
            int close = s.indexOf("</rainbow>", gt);
            if (close < 0) break;

            String inner = s.substring(gt + 1, close);
            String plain = inner.replaceAll("<[^>]+>", "");
            String expanded = applyRainbowPlainHex(plain);

            s = s.substring(0, open) + expanded + s.substring(close + "</rainbow>".length());
        }

        return s;
    }

    private static String applyMultiStopGradientPlainHex(String text, List<Integer> stops) {
        if (text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length() * 9);
        int n = text.length();
        for (int i = 0; i < n; i++) {
            double t = (n == 1) ? 0.0 : (double) i / (double) (n - 1);
            int rgb = sampleMultiStop(stops, t);
            sb.append('#').append(String.format("%06X", rgb));
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    private static String applyRainbowPlainHex(String text) {
        if (text.isEmpty()) return text;
        // Simple 6-stop rainbow.
        int[] stops = new int[]{0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF};
        List<Integer> list = new ArrayList<>();
        for (int c : stops) list.add(c);
        return applyMultiStopGradientPlainHex(text, list);
    }

    private static int sampleMultiStop(List<Integer> stops, double t) {
        if (stops.size() == 1) return stops.get(0);
        t = Math.max(0.0, Math.min(1.0, t));
        double scaled = t * (stops.size() - 1);
        int idx = (int) Math.floor(scaled);
        if (idx >= stops.size() - 1) return stops.get(stops.size() - 1);
        double localT = scaled - idx;
        return lerpRgb(stops.get(idx), stops.get(idx + 1), localT);
    }

    private static int lerpRgb(int a, int b, double t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int rr = (int) Math.round(ar + (br - ar) * t);
        int rg = (int) Math.round(ag + (bg - ag) * t);
        int rb = (int) Math.round(ab + (bb - ab) * t);
        return (rr << 16) | (rg << 8) | rb;
    }

}
