# VHS with a Java 21 runtime, to record the demo from the real jar.
FROM eclipse-temurin:21-jre AS jre
FROM ghcr.io/charmbracelet/vhs:v0.10.0
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
