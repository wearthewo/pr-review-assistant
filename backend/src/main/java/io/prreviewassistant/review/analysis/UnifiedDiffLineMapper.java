package io.prreviewassistant.review.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UnifiedDiffLineMapper {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(?: .*)?$");
    private static final int MAX_HEADER_LENGTH = 200;

    List<DiffLine> map(String patch) {
        if (patch == null || patch.isEmpty()) {
            return List.of();
        }
        List<DiffLine> result = new ArrayList<>();
        long oldLine = 0;
        long newLine = 0;
        boolean inHunk = false;
        for (String rawLine : patch.split("\\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.startsWith("@@")) {
                Matcher matcher = line.length() <= MAX_HEADER_LENGTH ? HUNK.matcher(line) : null;
                if (matcher == null || !matcher.matches()) {
                    inHunk = false;
                    continue;
                }
                try {
                    oldLine = Long.parseLong(matcher.group(1));
                    newLine = Long.parseLong(matcher.group(2));
                    inHunk = oldLine >= 0 && newLine >= 0
                            && oldLine <= Integer.MAX_VALUE && newLine <= Integer.MAX_VALUE;
                } catch (NumberFormatException exception) {
                    inHunk = false;
                }
                continue;
            }
            if (!inHunk || line.equals("\\ No newline at end of file")) {
                continue;
            }
            if (line.isEmpty()) {
                inHunk = false;
                continue;
            }
            char prefix = line.charAt(0);
            String text = line.substring(1);
            switch (prefix) {
                case '+' -> {
                    if (newLine < 1) { inHunk = false; continue; }
                    result.add(new DiffLine(DiffLineKind.ADDITION, null, (int) newLine, text));
                    newLine++;
                }
                case '-' -> {
                    if (oldLine < 1) { inHunk = false; continue; }
                    result.add(new DiffLine(DiffLineKind.DELETION, (int) oldLine, null, text));
                    oldLine++;
                }
                case ' ' -> {
                    if (oldLine < 1 || newLine < 1) { inHunk = false; continue; }
                    result.add(new DiffLine(DiffLineKind.CONTEXT, (int) oldLine, (int) newLine, text));
                    oldLine++;
                    newLine++;
                }
                default -> inHunk = false;
            }
            if (oldLine > Integer.MAX_VALUE || newLine > Integer.MAX_VALUE) {
                inHunk = false;
            }
        }
        return List.copyOf(result);
    }

    enum DiffLineKind { ADDITION, DELETION, CONTEXT }

    record DiffLine(DiffLineKind kind, Integer oldLine, Integer newLine, String text) {
        @Override public String toString() {
            return "DiffLine[kind=" + kind + ", oldLine=" + oldLine + ", newLine=" + newLine
                    + ", text=<redacted>]";
        }
    }
}
