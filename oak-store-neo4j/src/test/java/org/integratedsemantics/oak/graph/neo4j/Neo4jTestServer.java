package org.integratedsemantics.oak.graph.neo4j;

import java.util.List;
import java.util.Map;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.neo4j.Neo4jContainer;

/**
 * The Neo4j server used by the tests, shared by all the tests of the JVM. Two modes:
 * <ul>
 * <li>external: if the system property {@code oak.test.neo4j.uri} is set, that server is used (local or remote,
 * Docker or standalone, or AuraDB with a {@code neo4j+s://} URI); only its {@code OakTest*} nodes are touched,</li>
 * <li>otherwise (default) a Neo4j Docker container is started with Testcontainers, image {@code oak.test.neo4j.image},
 * else {@code neo4j:<oak.test.neo4j.version>} (set by the POM from {@code neo4j.version}).</li>
 * </ul>
 * Never an embedded Neo4j.
 */
public final class Neo4jTestServer {

    private static final Logger LOG = LoggerFactory.getLogger(Neo4jTestServer.class);

    public static final String TEST_URI_PROPERTY = "oak.test.neo4j.uri";

    public static final String TEST_USERNAME_PROPERTY = "oak.test.neo4j.username";

    public static final String TEST_PASSWORD_PROPERTY = "oak.test.neo4j.password";

    public static final String TEST_DATABASE_PROPERTY = "oak.test.neo4j.database";

    public static final String TEST_IMAGE_PROPERTY = "oak.test.neo4j.image";

    /** Tag of the default image, {@code neo4j:<version>}: the POM's {@code neo4j.version}. */
    public static final String TEST_VERSION_PROPERTY = "oak.test.neo4j.version";

    /** The label prefix of the tests: only these nodes are touched on an external server. */
    public static final String LABEL_PREFIX = "OakTest";

    private static Neo4jContainer container;

    private static Neo4jConfig config;

    private static Driver driver;

    private Neo4jTestServer() {
        // utility class
    }

    /** Starts (or connects to) the test server and returns its settings. */
    public static synchronized Neo4jConfig config() {
        if (config != null) {
            return config;
        }
        var uri = System.getProperty(TEST_URI_PROPERTY);
        var database = System.getProperty(TEST_DATABASE_PROPERTY);
        if (uri != null && !uri.isBlank()) {
            config = new Neo4jConfig(uri, System.getProperty(TEST_USERNAME_PROPERTY, "neo4j"),
                    System.getProperty(TEST_PASSWORD_PROPERTY, ""), database, LABEL_PREFIX);
            LOG.info("Using external {}", config);
        } else {
            var image = System.getProperty(TEST_IMAGE_PROPERTY);
            if (image == null || image.isBlank()) {
                var version = System.getProperty(TEST_VERSION_PROPERTY);
                if (version == null || version.isBlank()) {
                    throw new IllegalStateException(
                            "Set " + TEST_IMAGE_PROPERTY + " or " + TEST_VERSION_PROPERTY + " (done by the POM)");
                }
                image = "neo4j:" + version;
            }
            LOG.info("Starting Neo4j container: {}", image);
            container = new Neo4jContainer(image).withoutAuthentication();
            container.start();
            config = new Neo4jConfig(container.getBoltUrl(), null, null, database, LABEL_PREFIX);
        }
        return config;
    }

    /** A driver on the test server, for the tests' own queries. */
    public static synchronized Driver driver() {
        if (driver == null) {
            var c = config();
            var auth = c.username() == null || c.username().isEmpty() ? AuthTokens.none()
                    : AuthTokens.basic(c.username(), c.password());
            driver = GraphDatabase.driver(c.uri(), auth);
        }
        return driver;
    }

    public static SessionConfig sessionConfig() {
        var c = config();
        return c.database() == null ? SessionConfig.defaultConfig() : SessionConfig.forDatabase(c.database());
    }

    /** Runs a query on the test server. */
    public static List<org.neo4j.driver.Record> query(String cypher, Map<String, Object> params) {
        try (var session = driver().session(sessionConfig())) {
            return session.executeRead(tx -> tx.run(cypher, params).list());
        }
    }

    /** Deletes the test nodes (and their relationships). */
    public static void clear() {
        try (var session = driver().session(sessionConfig())) {
            for (var suffix : List.of("Node", "ClusterNode", "Setting", "Journal", "Blob")) {
                session.run("MATCH (n:`%s%s`) DETACH DELETE n".formatted(LABEL_PREFIX, suffix)).consume();
            }
        }
    }

}
