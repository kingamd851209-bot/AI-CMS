package com.xinhongru.site.launch;

/** Path of the executable assembled by the boot module, used for independent site processes. */
@FunctionalInterface
public interface ApplicationArtifact {
    String currentJar();
}
