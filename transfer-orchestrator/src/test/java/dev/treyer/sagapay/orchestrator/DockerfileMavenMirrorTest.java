package dev.treyer.sagapay.orchestrator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DockerfileMavenMirrorTest {

    private static final Pattern RUNS_MAVEN = Pattern.compile("^\\s*RUN\\b.*\\bmvn\\b", Pattern.MULTILINE);
    private static final Pattern DECLARES_MIRROR_ARG =
            Pattern.compile("^\\s*ARG\\s+MAVEN_MIRROR_URL\\b", Pattern.MULTILINE);

    @Test
    void everyDockerfileThatRunsMavenDeclaresTheMirrorArgument() throws IOException {
        List<Path> dockerfiles = dockerfiles();

        assertThat(dockerfiles).isNotEmpty();
        for (Path dockerfile : dockerfiles) {
            String content = Files.readString(dockerfile);
            if (RUNS_MAVEN.matcher(content).find()) {
                assertThat(DECLARES_MIRROR_ARG.matcher(content).find())
                        .as("%s runs Maven without declaring ARG MAVEN_MIRROR_URL", dockerfile)
                        .isTrue();
            }
        }
    }

    private static List<Path> dockerfiles() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir")).getParent();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.getFileName().toString().startsWith("Dockerfile"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
        }
    }
}
