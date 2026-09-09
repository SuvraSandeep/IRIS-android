import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/** Syntax-only check; deliberately does not claim Android type checking. */
class ParseSources {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
             java.util.stream.Stream<Path> paths = Files.walk(Path.of(args[0]))) {
            List<Path> sources = paths.filter(p -> p.toString().endsWith(".java")).toList();
            JavacTask task = (JavacTask) compiler.getTask(null, fm, diagnostics,
                    List.of("-proc:none"), null, fm.getJavaFileObjectsFromPaths(sources));
            task.parse();
            boolean failed = false;
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) { System.err.println(d); failed = true; }
            }
            if (failed) throw new AssertionError("Java syntax check failed");
            System.out.println("Parsed " + sources.size() + " Java source files; Android type checking still requires the APK build.");
        }
    }
}
