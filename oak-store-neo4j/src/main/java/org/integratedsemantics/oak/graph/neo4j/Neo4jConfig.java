package org.integratedsemantics.oak.graph.neo4j;

/**
 * Connection settings of a Neo4j server (or AuraDB), and the labels to use.
 *
 * @param uri the Bolt URI ({@code bolt://host:7687}, {@code neo4j://...}, or {@code neo4j+s://...} for AuraDB)
 * @param username the user name, {@code null} or empty for no authentication
 * @param password the password
 * @param database the database, {@code null} for the server's default database
 * @param labelPrefix the prefix of the node labels ({@code Oak}: {@code OakNode}, {@code OakClusterNode}, ...), so
 *            that several Oak repositories, or other data, can share a database
 */
public record Neo4jConfig(String uri, String username, String password, String database, String labelPrefix) {

    public static final String DEFAULT_LABEL_PREFIX = "Oak";

    public Neo4jConfig {
        if (labelPrefix == null || labelPrefix.isBlank()) {
            labelPrefix = DEFAULT_LABEL_PREFIX;
        }
        if (!labelPrefix.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid label prefix: " + labelPrefix);
        }
    }

    @Override
    public String toString() {
        // no password
        return "Neo4j(" + uri + (database == null ? "" : "/" + database) + ", labels " + labelPrefix + "*)";
    }

}
