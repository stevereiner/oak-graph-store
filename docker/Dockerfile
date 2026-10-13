# syntax=docker/dockerfile:1.7

# Apache Sling Starter 14 (Oak 1.90.0) with Composum Tools, its Oak repository in ArcadeDB or Neo4j, Elasticsearch for fulltext.
# Build from the repository root: docker build -f docker/Dockerfile -t oak-graph-sling:local .

ARG SLING_IMAGE=apache/sling:14

# --- the OSGi bundles, built for the Oak version of the Sling image (the fragment runs in its oak-store-document) ---
FROM maven:3.9.9-eclipse-temurin-21 AS build

ARG OAK_VERSION=1.90.0

# Composum Tools (JCR browser and editor, packages, users), built from its release tag: not on Maven Central yet
ARG COMPOSUM_TOOLS_VERSION=1.4.1
RUN --mount=type=cache,id=oak-graph-maven,target=/root/.m2 \
    mkdir -p /tmp/composum-tools /out && cd /tmp/composum-tools \
 && curl -fsSL "https://github.com/ist-dresden/composum-tools/archive/refs/tags/${COMPOSUM_TOOLS_VERSION}.tar.gz" \
      | tar -xz --strip-components=1 \
 && mvn -B -nsu install -DskipTests -pl sling/bundle -am \
 && cp sling/bundle/target/composum-tools-sling-bundle-${COMPOSUM_TOOLS_VERSION}.jar /out/

WORKDIR /build
COPY . .
# .mvn/jvm.config only holds a Windows trust store setting
RUN rm -f .mvn/jvm.config
RUN --mount=type=cache,id=oak-graph-maven,target=/root/.m2 \
    mvn -B -nsu install -DskipTests -Doak.version=${OAK_VERSION} -pl oak-graph-fragment,oak-graph-sling -am \
 && mkdir -p /out \
 && cp oak-graph-fragment/target/oak-graph-fragment-*.jar oak-graph-sling/target/oak-graph-sling-*.jar /out/ \
 && mvn -B -nsu dependency:copy -Dartifact=org.apache.jackrabbit:oak-search-elastic:${OAK_VERSION} -DoutputDirectory=/tmp \
 && java docker/OakSearchElasticPatch.java /tmp/oak-search-elastic-${OAK_VERSION}.jar /out/oak-search-elastic-${OAK_VERSION}.jar
# a fragment of tika-core with Tika's encoding detectors: tika-core's DefaultEncodingDetector looks them up with its own
# class loader, which can't see the tika-parsers bundle that lists them, so without it every text file (plain text,
# HTML, XML) fails text extraction ("Failed to detect the character encoding of a document")
RUN jar --create --file /out/tika-encoding-detectors-0.1.0-SNAPSHOT.jar \
      --manifest docker/tika-encoding-detectors/META-INF/MANIFEST.MF \
      -C docker/tika-encoding-detectors META-INF/services \
# and a fragment of the ServiceMix POI bundle adding the imports of org.w3c.dom its manifest lacks (without it, POI's
# OOXML support fails to load: NoClassDefFoundError: org/w3c/dom/Node, and no docx, xlsx or pptx is extracted)
 && jar --create --file /out/poi-dom-imports-0.1.0-SNAPSHOT.jar --manifest docker/poi-dom-imports/META-INF/MANIFEST.MF
# the libraries Sling Starter's Tika (1.28.5) leaves out, for text extraction of more formats: Apache POI 5.2.2, the version Tika 1.28.5 is built with (Office
# documents, and the charset detection of every text file), XMLBeans (Office Open XML: docx, xlsx, pptx),
# Commons Compress, TagSoup (HTML), and the Log4j 2 API that XMLBeans 5 requires (routed to Sling's logging by
# log4j-to-slf4j). OSGi bundles, placed in Sling's artifacts repository (Maven layout). Apache 2.0.
RUN --mount=type=cache,id=oak-graph-maven,target=/root/.m2 \
    mkdir -p /tmp/libs \
 && mvn -B -nsu dependency:copy -Dartifact=org.apache.servicemix.bundles:org.apache.servicemix.bundles.poi:5.2.2_3 -DoutputDirectory=/tmp/libs \
 && mvn -B -nsu dependency:copy -Dartifact=org.apache.servicemix.bundles:org.apache.servicemix.bundles.xmlbeans:5.0.3_1 -DoutputDirectory=/tmp/libs \
 && mvn -B -nsu dependency:copy -Dartifact=org.apache.commons:commons-compress:1.21 -DoutputDirectory=/tmp/libs \
 && mvn -B -nsu dependency:copy -Dartifact=org.apache.servicemix.bundles:org.apache.servicemix.bundles.tagsoup:1.2.1_1 -DoutputDirectory=/tmp/libs \
 && mvn -B -nsu dependency:copy -Dartifact=org.apache.logging.log4j:log4j-api:2.24.3 -DoutputDirectory=/tmp/libs \
 && mvn -B -nsu dependency:copy -Dartifact=org.apache.logging.log4j:log4j-to-slf4j:2.24.3 -DoutputDirectory=/tmp/libs \
 && mkdir -p /out/artifacts/org/apache/servicemix/bundles/org.apache.servicemix.bundles.poi/5.2.2_3 && cp /tmp/libs/org.apache.servicemix.bundles.poi-5.2.2_3.jar /out/artifacts/org/apache/servicemix/bundles/org.apache.servicemix.bundles.poi/5.2.2_3/ \
 && mkdir -p /out/artifacts/org/apache/servicemix/bundles/org.apache.servicemix.bundles.xmlbeans/5.0.3_1 && cp /tmp/libs/org.apache.servicemix.bundles.xmlbeans-5.0.3_1.jar /out/artifacts/org/apache/servicemix/bundles/org.apache.servicemix.bundles.xmlbeans/5.0.3_1/ \
 && mkdir -p /out/artifacts/org/apache/commons/commons-compress/1.21 && cp /tmp/libs/commons-compress-1.21.jar /out/artifacts/org/apache/commons/commons-compress/1.21/ \
 && mkdir -p /out/artifacts/org/apache/servicemix/bundles/org.apache.servicemix.bundles.tagsoup/1.2.1_1 && cp /tmp/libs/org.apache.servicemix.bundles.tagsoup-1.2.1_1.jar /out/artifacts/org/apache/servicemix/bundles/org.apache.servicemix.bundles.tagsoup/1.2.1_1/ \
 && mkdir -p /out/artifacts/org/apache/logging/log4j/log4j-api/2.24.3 && cp /tmp/libs/log4j-api-2.24.3.jar /out/artifacts/org/apache/logging/log4j/log4j-api/2.24.3/ \
 && mkdir -p /out/artifacts/org/apache/logging/log4j/log4j-to-slf4j/2.24.3 && cp /tmp/libs/log4j-to-slf4j-2.24.3.jar /out/artifacts/org/apache/logging/log4j/log4j-to-slf4j/2.24.3/ \
 && chmod -R a+rX /out/artifacts

# --- Sling with the bundles and the oak_graph feature, in its artifacts repository (Maven layout) ---
FROM ${SLING_IMAGE}

ARG OAK_VERSION=1.90.0
ARG COMPOSUM_TOOLS_VERSION=1.4.1
ARG V=0.1.0-SNAPSHOT
ARG REPO=/opt/sling/artifacts/org/integratedsemantics/oak/graph

COPY --from=build --chown=sling:sling /out/oak-graph-fragment-${V}.jar ${REPO}/oak-graph-fragment/${V}/
COPY --from=build --chown=sling:sling /out/oak-graph-sling-${V}.jar ${REPO}/oak-graph-sling/${V}/
COPY --chown=sling:sling docker/oak_graph.slingosgifeature ${REPO}/oak-graph-sling/${V}/oak-graph-sling-${V}-oak_graph.slingosgifeature
# Oak's Elasticsearch index bundle (Sling Starter doesn't have it), in the Oak version of the image, patched to use
# Sling's JSON-P API (docker/OakSearchElasticPatch.java): under our group id, as it isn't Oak's artifact as released.
# With Lucene only (see oak_graph.slingosgifeature), this bundle, and its download and patch above, aren't needed.
COPY --from=build --chown=sling:sling /out/oak-search-elastic-${OAK_VERSION}.jar ${REPO}/oak-search-elastic/${OAK_VERSION}/
COPY --from=build --chown=sling:sling /out/tika-encoding-detectors-${V}.jar ${REPO}/tika-encoding-detectors/${V}/
COPY --from=build --chown=sling:sling /out/poi-dom-imports-${V}.jar ${REPO}/poi-dom-imports/${V}/
COPY --from=build --chown=sling:sling /out/artifacts/ /opt/sling/artifacts/
COPY --from=build --chown=sling:sling /out/composum-tools-sling-bundle-${COMPOSUM_TOOLS_VERSION}.jar \
     /opt/sling/artifacts/com/composum/tools/composum-tools-sling-bundle/${COMPOSUM_TOOLS_VERSION}/

# binaries (Oak file data store), and the log of the ArcadeDB client
USER root
RUN mkdir -p /opt/sling/datastore /opt/sling/log && chown sling:sling /opt/sling/datastore /opt/sling/log \
 && sed -i -e "s/oak-search-elastic:1.90.0/oak-search-elastic:${OAK_VERSION}/" \
      -e "s/composum-tools-sling-bundle:1.4.1/composum-tools-sling-bundle:${COMPOSUM_TOOLS_VERSION}/" \
      ${REPO}/oak-graph-sling/${V}/oak-graph-sling-${V}-oak_graph.slingosgifeature
USER sling:sling
VOLUME /opt/sling/datastore

# launch.sh selects the feature whose name contains the argument
CMD ["oak_graph"]
