package com.xinhongru.config;

import com.xinhongru.CmsApplication;
import com.xinhongru.site.launch.ApplicationArtifact;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class BootApplicationArtifact implements ApplicationArtifact {
    public String currentJar() {
        try {
            var source = CmsApplication.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path path = Paths.get(source.getLocation().toURI());
                if (path.toString().endsWith(".jar") && path.toFile().isFile()) {
                    return path.toAbsolutePath().toString();
                }
            }
        } catch (Exception e) {
            // IDE launches have no executable JAR; use the normal build output.
        }
        String command = System.getProperty("sun.java.command", "");
        String firstToken = command.split("\\s+")[0];
        if (firstToken.endsWith(".jar")) {
            return Paths.get(firstToken).toAbsolutePath().toString();
        }
        return Paths.get(System.getProperty("user.dir"), "cms-boot/target/xhr-cms-1.0-SNAPSHOT.jar")
                .toAbsolutePath()
                .toString();
    }
}
