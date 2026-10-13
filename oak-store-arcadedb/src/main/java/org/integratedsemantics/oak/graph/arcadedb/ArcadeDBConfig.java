package org.integratedsemantics.oak.graph.arcadedb;

/**
 * Connection settings of an ArcadeDB server, reached through its HTTP API.
 *
 * @param host the server host
 * @param port the server HTTP port (2480 by default)
 * @param database the database, which must exist and be dedicated to the Oak repository
 * @param username the user name
 * @param password the password
 */
public record ArcadeDBConfig(String host, int port, String database, String username, String password) {

    public static final int DEFAULT_PORT = 2480;

    @Override
    public String toString() {
        // no password
        return "ArcadeDB(" + host + ":" + port + "/" + database + ")";
    }

}
