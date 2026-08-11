import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs the project's clean build and conformance suite. */
public final class TestRunner {
    private TestRunner() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("build/Build.java");
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .inheritIO()
                .start();
        System.exit(process.waitFor());
    }
}
