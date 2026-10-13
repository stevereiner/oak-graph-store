import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Makes Oak's {@code oak-search-elastic} bundle use the JSON-P API ({@code jakarta.json}) exported in Sling (by
 * {@code org.apache.sling.commons.johnzon}) instead of its own embedded copy, then writes the bundle under a new name.
 * <p>
 * The Elasticsearch client finds its JSON-P provider with {@code JsonProvider.provider()}, a {@code ServiceLoader} on
 * the thread's context class loader. In Sling, that finds Johnzon's provider, which implements Sling's
 * {@code jakarta.json.spi.JsonProvider}, not the bundle's embedded one: {@code ServiceConfigurationError: ... not a
 * subtype}, every bulk request to Elasticsearch fails and the index is rebuilt again and again. With the API imported,
 * there is a single {@code JsonProvider} class, and Johnzon's provider works.
 * <p>
 * Run with the JDK's single-file launcher: {@code java OakSearchElasticPatch.java <bundle> <patched bundle>}.
 */
public class OakSearchElasticPatch {

    private static final String EMBEDDED_API = "jakarta.json-api-";

    private static final String IMPORTS = "jakarta.json;version=\"[2.0,3)\",jakarta.json.spi;version=\"[2.0,3)\","
            + "jakarta.json.stream;version=\"[2.0,3)\"";

    public static void main(String[] args) throws IOException {
        try (var jar = new JarFile(args[0])) {
            var manifest = new Manifest(jar.getManifest());
            var attributes = manifest.getMainAttributes();
            var classPath = Arrays.stream(attributes.getValue("Bundle-ClassPath").split(","))
                                  .filter(entry -> !entry.startsWith(EMBEDDED_API))
                                  .toList();
            attributes.putValue("Bundle-ClassPath", String.join(",", classPath));
            attributes.putValue("Import-Package", attributes.getValue("Import-Package") + "," + IMPORTS);
            try (var out = new JarOutputStream(Files.newOutputStream(Path.of(args[1])), manifest)) {
                for (var entries = jar.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement();
                    if (entry.getName().equals(JarFile.MANIFEST_NAME) || entry.getName().startsWith(EMBEDDED_API)) {
                        continue;
                    }
                    out.putNextEntry(new JarEntry(entry.getName()));
                    try (var in = jar.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
        }
    }

}
