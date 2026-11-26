import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class SourceObfuscator {
    record Result(Path output, int before, int after, int renamed) {}

    private static String compact(List<SourceTokens.Token> tokens, Map<Integer, String> replacements) {
        StringBuilder output = new StringBuilder();
        SourceTokens.Token previous = null;
        for (SourceTokens.Token token : tokens) {
            // Разделители оставляем там, где удаление пробела могло бы склеить токены.
            if (previous != null && previous.end() < token.start()
                    && !separator(previous.text()) && !separator(token.text())) output.append(' ');
            output.append(replacements.getOrDefault(token.start(), token.text()));
            previous = token;
        }
        return output.append('\n').toString();
    }

    private static boolean separator(String token) {
        return token.length() == 1 && "()[]{};,".contains(token);
    }

    static Result obfuscate(Path source) throws IOException {
        String original = Files.readString(source);
        String code = original.startsWith("\uFEFF") ? original.substring(1) : original;
        if (code.matches("(?s).*\\\\u+[0-9a-fA-F]{4}.*")) {
            throw new IOException("Исходники с Unicode-подстановками вида \\uXXXX не поддерживаются.");
        }
        List<SourceTokens.Token> tokens = SourceTokens.scan(code);
        Path outputFolder = source.resolveSibling("result");
        Set<String> usedNames = new HashSet<>();
        for (SourceTokens.Token token : tokens) usedNames.add(token.text());
        Set<String> outputNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (Files.isDirectory(outputFolder)) {
            try (DirectoryStream<Path> outputs = Files.newDirectoryStream(outputFolder, "*.java")) {
                for (Path path : outputs) outputNames.add(className(path));
            }
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("Для запуска нужен JDK, содержащий javac.");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            Analysis originalUnit = analyze(compiler, files, source, source.getParent(), code);
            Renamer renamer = new Renamer(originalUnit, tokens, new Names(usedNames));
            String newClassName = renamer.rename(className(source), outputNames);
            String compact = compact(tokens, renamer.replacements);
            Path output = outputFolder.resolve(newClassName + ".java");
            analyze(compiler, files, output, source.getParent(), compact);
            Files.createDirectories(outputFolder);
            Files.writeString(output, compact, StandardOpenOption.CREATE_NEW);
            return new Result(output, original.getBytes(StandardCharsets.UTF_8).length,
                    compact.getBytes(StandardCharsets.UTF_8).length, renamer.names.size());
        }
    }

    private static String className(Path path) {
        String filename = path.getFileName().toString();
        return filename.substring(0, filename.length() - ".java".length());
    }

    private record Analysis(CompilationUnitTree unit, Trees trees, Types types) {}

    private static Analysis analyze(JavaCompiler compiler, StandardJavaFileManager files,
                                    Path path, Path classpath, String code) throws IOException {
        DiagnosticCollector<JavaFileObject> messages = new DiagnosticCollector<>();
        JavaFileObject input = new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, files, messages,
                List.of("-proc:none", "-classpath", classpath.toString(), "-implicit:none"), null, List.of(input));
        CompilationUnitTree unit = task.parse().iterator().next();
        task.analyze();
        for (Diagnostic<?> message : messages.getDiagnostics()) {
            if (message.getKind() == Diagnostic.Kind.ERROR) {
                throw new IOException(path.getFileName() + ", строка " + message.getLineNumber()
                        + ": " + message.getMessage(Locale.ROOT));
            }
        }
        return new Analysis(unit, Trees.instance(task), task.getTypes());
    }

    private static final class Names {
        private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        private final Set<String> used;
        private int index;

        Names(Set<String> used) { this.used = used; }

        String next() throws IOException {
            int count = LETTERS.length();
            while (index < count + count * count) {
                int value = index++;
                String name = value < count ? "" + LETTERS.charAt(value) :
                        "" + LETTERS.charAt((value - count) / count) + LETTERS.charAt((value - count) % count);
                if (!SourceVersion.isKeyword(name) && used.add(name)) return name;
            }
            throw new IOException("Недостаточно уникальных имён из одного или двух символов.");
        }
    }

    private static final class Renamer extends TreePathScanner<Void, Void> {
        private final Analysis analysis;
        private final SourcePositions positions;
        private final List<SourceTokens.Token> tokens;
        private final Names generator;
        private final Map<Element, String> names = new LinkedHashMap<>();
        private final Map<Integer, String> replacements = new HashMap<>();
        private final Set<String> inheritedNames = new HashSet<>();
        // Объявления выбирают, что переименовать. Позиции показывают, где заменить имя.
        private final Set<Element> declarations = new LinkedHashSet<>();
        private final Map<Integer, Element> locations = new HashMap<>();

        Renamer(Analysis analysis, List<SourceTokens.Token> tokens, Names generator) {
            this.analysis = analysis;
            positions = analysis.trees().getSourcePositions();
            this.tokens = tokens;
            this.generator = generator;
        }

        String rename(String originalClass, Set<String> outputNames) throws IOException {
            ClassTree primary = null;
            for (Tree tree : analysis.unit().getTypeDecls()) {
                if (tree instanceof ClassTree type && type.getSimpleName().contentEquals(originalClass)) {
                    primary = type;
                    break;
                }
            }
            if (primary == null) throw new IOException("Имя файла должно совпадать с именем одного из его классов.");
            Element primarySymbol = analysis.trees().getElement(com.sun.source.util.TreePath.getPath(analysis.unit(), primary));
            String primaryName = generator.next();
            while (outputNames.contains(primaryName)) primaryName = generator.next();
            names.put(primarySymbol, primaryName);

            // Собираем имена методов из предков, чтобы сохранить переопределения и контракты.
            new TreePathScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree tree, Void unused) {
                    if (analysis.trees().getElement(getCurrentPath()) instanceof TypeElement type) {
                        collectInherited(type.asType(), new HashSet<>());
                    }
                    return super.visitClass(tree, unused);
                }
            }.scan(analysis.unit(), null);

            scan(analysis.unit(), null);
            for (Element declaration : declarations) {
                if (!names.containsKey(declaration)) names.put(declaration, generator.next());
            }
            for (var location : locations.entrySet()) {
                String shortName = names.get(location.getValue());
                if (shortName != null) replacements.put(location.getKey(), shortName);
            }
            return primaryName;
        }

        private void collectInherited(TypeMirror type, Set<Element> seen) {
            for (TypeMirror parent : analysis.types().directSupertypes(type)) {
                Element symbol = analysis.types().asElement(parent);
                if (symbol == null || !seen.add(symbol)) continue;
                for (Element member : symbol.getEnclosedElements()) {
                    if (member.getKind() == ElementKind.METHOD) inheritedNames.add(member.getSimpleName().toString());
                }
                collectInherited(parent, seen);
            }
        }

        private long start(Tree tree) { return positions.getStartPosition(analysis.unit(), tree); }
        private long end(Tree tree) { return positions.getEndPosition(analysis.unit(), tree); }
        private Element symbol() { return analysis.trees().getElement(getCurrentPath()); }

        private void declare(Element element, Tree tree, int token) {
            if (token < 0 || element == null || end(tree) < 0) return;
            declarations.add(element);
            locations.put(tokens.get(token).start(), element);
        }

        @Override public Void visitClass(ClassTree tree, Void unused) {
            if (tree.getSimpleName().length() != 0) {
                String oldName = tree.getSimpleName().toString();
                int token = -1;
                for (int i = SourceTokens.firstAtOrAfter(tokens, start(tree)); i + 1 < tokens.size()
                        && tokens.get(i + 1).end() <= end(tree); i++) {
                    if (Set.of("class", "interface", "enum", "record").contains(tokens.get(i).text())
                            && tokens.get(i + 1).text().equals(oldName)) {
                        token = i + 1;
                        break;
                    }
                }
                declare(symbol(), tree, token);
            }
            return super.visitClass(tree, unused);
        }

        @Override public Void visitVariable(VariableTree tree, Void unused) {
            Element element = symbol();
            if (element != null && canRenameVariable(element)) {
                long limit = tree.getInitializer() == null ? end(tree) : start(tree.getInitializer());
                int token = -1;
                for (int i = SourceTokens.firstAtOrAfter(tokens, start(tree)); i < tokens.size()
                        && tokens.get(i).end() <= limit; i++) {
                    if (tokens.get(i).text().contentEquals(tree.getName())) token = i;
                }
                declare(element, tree, token);
            }
            return super.visitVariable(tree, unused);
        }

        private boolean canRenameVariable(Element variable) {
            Element owner = variable.getEnclosingElement();
            return switch (variable.getKind()) {
                case FIELD -> owner.getKind() != ElementKind.RECORD && owner.getKind() != ElementKind.ENUM;
                case PARAMETER -> owner.getKind() != ElementKind.CONSTRUCTOR
                        || owner.getEnclosingElement().getKind() != ElementKind.RECORD;
                case LOCAL_VARIABLE, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE -> true;
                default -> false;
            };
        }

        @Override public Void visitMethod(MethodTree tree, Void unused) {
            Element element = symbol();
            if (element != null && end(tree) >= 0) {
                Element owner = element.getEnclosingElement();
                boolean constructor = element.getKind() == ElementKind.CONSTRUCTOR;
                String oldName = constructor ? owner.getSimpleName().toString() : tree.getName().toString();
                if (constructor) {
                    int token = methodName(tree, oldName, true);
                    if (token >= 0) locations.put(tokens.get(token).start(), owner);
                } else if (!oldName.equals("main") && !inheritedNames.contains(oldName)
                        && owner.getKind() != ElementKind.ANNOTATION_TYPE && owner.getKind() != ElementKind.RECORD
                        && owner.getKind() != ElementKind.ENUM) {
                    declare(element, tree, methodName(tree, oldName, false));
                }
            }
            return super.visitMethod(tree, unused);
        }

        private int methodName(MethodTree tree, String name, boolean constructor) {
            long limit = tree.getBody() == null ? end(tree) : start(tree.getBody());
            int depth = 0;
            for (int i = SourceTokens.firstAtOrAfter(tokens, start(tree)); i + 1 < tokens.size()
                    && tokens.get(i).end() <= limit; i++) {
                String token = tokens.get(i).text();
                String next = tokens.get(i + 1).text();
                String previous = i == 0 ? "" : tokens.get(i - 1).text();
                if (depth == 0 && token.equals(name) && !previous.equals("@") && !previous.equals(".")
                        && (next.equals("(") || (constructor && next.equals("{")))) return i;
                if (token.equals("(")) depth++;
                if (token.equals(")")) depth--;
            }
            return -1;
        }

        @Override public Void visitIdentifier(IdentifierTree tree, Void unused) {
            recordReference(start(tree), tree.getName().toString());
            return super.visitIdentifier(tree, unused);
        }

        @Override public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            String oldName = tree.getIdentifier().toString();
            recordReference(end(tree) - oldName.length(), oldName);
            return super.visitMemberSelect(tree, unused);
        }

        @Override public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            if (tree.getMode() == MemberReferenceTree.ReferenceMode.INVOKE) {
                String oldName = tree.getName().toString();
                recordReference(end(tree) - oldName.length(), oldName);
            }
            return super.visitMemberReference(tree, unused);
        }

        private void recordReference(long position, String oldName) {
            if (position < 0 || oldName.equals("this") || oldName.equals("super")) return;
            Element element = symbol();
            if (element == null) return;
            if (element.getKind() == ElementKind.CONSTRUCTOR) element = element.getEnclosingElement();
            int token = SourceTokens.firstAtOrAfter(tokens, position);
            if (token < tokens.size() && tokens.get(token).start() == position
                    && tokens.get(token).text().equals(oldName)) locations.put((int) position, element);
        }
    }

    private SourceObfuscator() {}
}
