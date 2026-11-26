import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class ObfuscatorApp {
    private static List<Path> javaFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) throw new IOException("Папка не найдена: " + folder);
        try (var entries = Files.list(folder)) {
            return entries.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !List.of("module-info.java", "package-info.java")
                            .contains(file.getFileName().toString()))
                    .sorted().toList();
        }
    }

    private static String read(Scanner scanner, String prompt) throws IOException {
        System.out.print(prompt);
        if (!scanner.hasNextLine()) throw new IOException("Ввод завершён.");
        String value = scanner.nextLine().strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) throw new IOException("Введите непустое значение.");
        return value;
    }

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8)) {
            if (args.length > 2) throw new IOException("Ожидаются путь к папке и имя файла.");
            String directory = args.length > 0 ? args[0] : read(scanner, "Папка с исходником: ");
            Path folder = Path.of(directory).toAbsolutePath().normalize();
            List<Path> files = javaFiles(folder);
            if (files.isEmpty()) throw new IOException("В папке нет Java-файлов с классами.");

            String filename;
            if (args.length == 2) {
                filename = args[1];
            } else if (files.size() == 1) {
                filename = files.get(0).getFileName().toString();
            } else {
                for (Path file : files) System.out.println("  " + file.getFileName());
                filename = read(scanner, "Имя файла из списка: ");
            }
            Path source = files.stream().filter(file -> file.getFileName().toString().equals(filename))
                    .findFirst().orElseThrow(() -> new IOException("Файл не найден: " + filename));
            var result = SourceObfuscator.obfuscate(source);
            System.out.println("Новый файл: " + result.output());
            System.out.printf("Было %d байт, стало %d байт.%n", result.before(), result.after());
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Ошибка: " + ex.getMessage());
        }
    }
}
