#!/bin/sh
# Maven Wrapper - runs Maven via the wrapper jar

# Determine the project base dir
BASEDIR="$( cd "$( dirname "$0" )" && pwd )"

# Maven Wrapper jar location
MAVEN_WRAPPER_JAR="$BASEDIR/.mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_MAIN_CLASS="org.apache.maven.wrapper.MavenWrapperMain"

# Download properties
MAVEN_WRAPPER_PROPERTIES="$BASEDIR/.mvn/wrapper/maven-wrapper.properties"

# Default JVM options
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

# Use the maximum available, or set MAX_FD != -1 to use that value
MAX_FD="maximum"

# Setup the command line
warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Check if java is available
if ! command -v "$JAVACMD" > /dev/null 2>&1; then
    die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

# Check if wrapper jar exists
if [ ! -f "$MAVEN_WRAPPER_JAR" ]; then
    echo "Maven Wrapper jar not found, downloading..."
    MAVEN_WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
    if command -v curl > /dev/null 2>&1; then
        curl -sSLO "$MAVEN_WRAPPER_URL" -o "$MAVEN_WRAPPER_JAR"
    elif command -v wget > /dev/null 2>&1; then
        wget -q "$MAVEN_WRAPPER_URL" -O "$MAVEN_WRAPPER_JAR"
    else
        die "ERROR: Neither curl nor wget is available to download the Maven Wrapper jar."
    fi
fi

exec "$JAVACMD" \
    $DEFAULT_JVM_OPTS \
    $JAVA_OPTS \
    $MAVEN_OPTS \
    -Dmaven.multiModuleProjectDirectory="$BASEDIR" \
    -classpath "$MAVEN_WRAPPER_JAR" \
    "$MAVEN_WRAPPER_MAIN_CLASS" \
    "$@"