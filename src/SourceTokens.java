import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Выделяет токены с позициями в исходном тексте и пропускает комментарии. */
final class SourceTokens {
    record Token(int start, int end, String text) {}

    static List<Token> scan(String source) throws IOException {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            int start = i;
            char ch = source.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
                continue;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                if (end == -1) throw new IOException("Незакрытый комментарий.");
                i = end + 2;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                String delimiter = source.startsWith("\"\"\"", i) ? "\"\"\"" : String.valueOf(ch);
                i += delimiter.length();
                while (i < source.length() && !source.startsWith(delimiter, i)) {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                if (i >= source.length()) throw new IOException("Незакрытый строковый или символьный литерал.");
                i += delimiter.length();
            } else if (Character.isJavaIdentifierStart(source.codePointAt(i))) {
                i += Character.charCount(source.codePointAt(i));
                while (i < source.length() && Character.isJavaIdentifierPart(source.codePointAt(i))
                        && !Character.isWhitespace(source.charAt(i))) {
                    i += Character.charCount(source.codePointAt(i));
                }
            } else {
                i++;
            }
            tokens.add(new Token(start, i, source.substring(start, i)));
        }
        return tokens;
    }

    static int firstAtOrAfter(List<Token> tokens, long position) {
        int left = 0;
        int right = tokens.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (tokens.get(mid).start() < position) left = mid + 1;
            else right = mid;
        }
        return left;
    }

    private SourceTokens() {}
}
