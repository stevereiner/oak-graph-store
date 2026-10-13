package org.integratedsemantics.oak.graph.arcadedb;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.arcadedb.remote.RemoteDatabase;

/**
 * The ArcadeDB server used by the tests, shared by all the tests of the JVM. Two modes:
 * <ul>
 * <li>external: if the system property {@code oak.test.arcadedb.host} is set, that server is used (local or remote,
 * Docker or standalone); its database must exist and be dedicated to the tests (its documents are deleted),</li>
 * <li>otherwise (default) an ArcadeDB Docker container is started with Testcontainers, image
 * {@code oak.test.arcadedb.image} (default {@code arcadedata/arcadedb:<client version>}).</li>
 * </ul>
 */
public final class ArcadeDBTestServer {

    private static final Logger LOG = LoggerFactory.getLogger(ArcadeDBTestServer.class);

    public static final String TEST_HOST_PROPERTY = "oak.test.arcadedb.host";

    public static final String TEST_PORT_PROPERTY = "oak.test.arcadedb.port";

    public static final String TEST_DATABASE_PROPERTY = "oak.test.arcadedb.database";

    public static final String TEST_USERNAME_PROPERTY = "oak.test.arcadedb.username";

    public static final String TEST_PASSWORD_PROPERTY = "oak.test.arcadedb.password";

    public static final String TEST_IMAGE_PROPERTY = "oak.test.arcadedb.image";

    /** The ArcadeDB client version, set by the POM, used as default image tag. */
    public static final String TEST_VERSION_PROPERTY = "oak.test.arcadedb.version";

    private static final String TEST_ROOT_PASSWORD = "playwithdata";

    private static final int HTTP_PORT = ArcadeDBConfig.DEFAULT_PORT;

    private static GenericContainer<?> container;

    private static ArcadeDBConfig config;

    private ArcadeDBTestServer() {
        // utility class
    }

    /** Starts (or connects to) the test server and returns its settings. */
    public static synchronized ArcadeDBConfig config() {
        if (config != null) {
            return config;
        }
        var host = System.getProperty(TEST_HOST_PROPERTY);
        var database = System.getProperty(TEST_DATABASE_PROPERTY, "oak-test");
        if (host != null && !host.isBlank()) {
            var port = Integer.parseInt(System.getProperty(TEST_PORT_PROPERTY, String.valueOf(HTTP_PORT)));
            config = new ArcadeDBConfig(host, port, database, System.getProperty(TEST_USERNAME_PROPERTY, "root"),
                    System.getProperty(TEST_PASSWORD_PROPERTY, ""));
            LOG.info("Using external {}", config);
        } else {
            startContainer(database);
            config = new ArcadeDBConfig(container.getHost(), container.getMappedPort(HTTP_PORT), database, "root",
                    TEST_ROOT_PASSWORD);
        }
        return config;
    }

    private static void startContainer(String database) {
        var image = System.getProperty(TEST_IMAGE_PROPERTY);
        if (image == null || image.isBlank()) {
            var version = System.getProperty(TEST_VERSION_PROPERTY);
            if (version == null || version.isBlank()) {
                throw new IllegalStateException(
                        "Set " + TEST_IMAGE_PROPERTY + " or " + TEST_VERSION_PROPERTY + " (done by the POM)");
            }
            image = "arcadedata/arcadedb:" + version;
        }
        LOG.info("Starting ArcadeDB container: {}", image);
        var javaOpts = String.join(" ", //
                // a bounded heap: by default the JVM takes a quarter of the Docker VM's memory, and the server grows
                // into it (Oak's 32 MB document test), until the VM's OOM killer stops the container
                "-Xmx4g", //
                "-Darcadedb.server.rootPassword=" + TEST_ROOT_PASSWORD, //
                "-Darcadedb.server.defaultDatabases=" + database + "[root]");
        var ready = Wait.forHttp("/api/v1/ready")
                        .forPort(HTTP_PORT)
                        .forStatusCodeMatching(code -> code >= 200 && code < 300)
                        .withStartupTimeout(Duration.ofMinutes(2));
        container = new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(HTTP_PORT)
                                                                        .withEnv("JAVA_OPTS", javaOpts)
                                                                        .waitingFor(ready);
        container.start();
    }

    /** Opens a client on the test database. */
    public static RemoteDatabase openDatabase() {
        var c = config();
        return new RemoteDatabase(c.host(), c.port(), c.database(), c.username(), c.password());
    }

    /** Deletes all the Oak documents and edges of the test database. */
    public static void clear() {
        try (var db = openDatabase()) {
            // only the existing types: on a new database no store has created them yet
            var existing = db.query("sql", "SELECT name FROM schema:types WHERE name IN :types",
                    Map.of("types", ArcadeDBDocumentStore.ALL_TYPES))
                             .stream()
                             .map(r -> (String) r.getProperty("name"))
                             .toList();
            // the edges first: cheaper than through their vertices
            if (existing.contains(ArcadeDBDocumentStore.CHILD_OF_TYPE)) {
                db.command("sql", "DELETE FROM " + ArcadeDBDocumentStore.CHILD_OF_TYPE, Map.of());
            }
            for (var type : existing) {
                if (!type.equals(ArcadeDBDocumentStore.CHILD_OF_TYPE)) {
                    db.command("sql", "DELETE FROM " + type, Map.of());
                }
            }
        }
    }

}
