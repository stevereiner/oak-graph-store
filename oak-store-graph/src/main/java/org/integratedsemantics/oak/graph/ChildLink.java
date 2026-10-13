package org.integratedsemantics.oak.graph;

import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.plugins.document.Document;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.Path;
import org.apache.jackrabbit.oak.plugins.document.util.Utils;

/**
 * The {@code CHILD_OF} edge parameters of an inserted node document: the edge from it to its parent, and the edges
 * to it from its existing children (whose ids are between {@code lowerLimit} and {@code upperLimit}).
 *
 * @param childId the id of the inserted node document
 * @param name the name of the node (the last segment of its path, {@code ""} for the root)
 * @param parentId the id of its parent ({@code ""} for the root)
 * @param lowerLimit the exclusive lower limit of its children's ids
 * @param upperLimit the exclusive upper limit of its children's ids
 */
public record ChildLink(String childId, String name, String parentId, String lowerLimit, String upperLimit) {

    /** The edge type, the same in all the backends. */
    public static final String CHILD_OF = "CHILD_OF";

    /**
     * The link of a node document; {@code null} for documents that are not nodes: previous documents (revision history
     * split off a node document), ids that are not node ids.
     */
    public static ChildLink of(Document doc) {
        var id = doc.getId();
        if (id == null || Utils.isPreviousDocId(id)) {
            return null;
        }
        String pathString;
        if (doc.get(NodeDocument.PATH) instanceof String p) {
            pathString = p; // long path, hashed in the id
        } else {
            int colon = id.indexOf(':');
            if (colon < 1) {
                return null;
            }
            pathString = id.substring(colon + 1);
        }
        // checked first: Path.fromString only asserts validity
        if (!PathUtils.isValid(pathString) || !PathUtils.isAbsolute(pathString)) {
            return null;
        }
        try {
            var path = Path.fromString(pathString);
            if (!id.equals(Utils.getIdFromPath(path))) {
                return null;
            }
            var parent = path.getParent();
            return new ChildLink(id, path.getName(), parent == null ? "" : Utils.getIdFromPath(parent),
                    Utils.getKeyLowerLimit(path), Utils.getKeyUpperLimit(path));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
