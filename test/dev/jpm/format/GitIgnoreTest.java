package dev.jpm.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitIgnoreTest {
    @TempDir
    Path repo;

    private void write(String rel, String text) throws IOException {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @Test
    void patternsAsGitReadsThem() throws IOException {
        Files.createDirectories(repo.resolve(".git/info"));
        write(".gitignore", """
                # build output
                build/
                !**/src/main/**/build/
                *.log
                !keep.log
                /top.txt
                docs/*.html
                **/generated/**
                \\#hash
                trailing.txt  \s
                """);
        write(".git/info/exclude", "local.java\n");
        write("module/.gitignore", "*.tmp\n!important.tmp\n");
        GitIgnore g = new GitIgnore(repo);

        assertTrue(g.ignored("build/A.java", false));
        assertTrue(g.ignored("fabric/versions/1.21/build/B.java", false));
        assertFalse(g.ignored("core/src/main/java/dev/build/C.java", false));
        assertTrue(g.ignored("a.log", false));
        assertTrue(g.ignored("deep/b.log", false));
        assertFalse(g.ignored("keep.log", false));
        assertTrue(g.ignored("top.txt", false));
        assertFalse(g.ignored("sub/top.txt", false));
        assertTrue(g.ignored("docs/index.html", false));
        assertFalse(g.ignored("docs/api/index.html", false));
        assertTrue(g.ignored("x/generated/y/Z.java", false));
        assertTrue(g.ignored("#hash", false));
        assertTrue(g.ignored("trailing.txt", false));
        assertTrue(g.ignored("local.java", false));
        assertTrue(g.ignored("module/a.tmp", false));
        assertFalse(g.ignored("module/important.tmp", false));
        assertFalse(g.ignored("other/a.tmp", false));
        assertFalse(g.ignored("src/Main.java", false));
    }

    @Test
    void aFileInAnIgnoredFolderIsNotIncludedAgain() throws IOException {
        Files.createDirectories(repo.resolve(".git"));
        write(".gitignore", "out/\n!out/Keep.java\n");
        GitIgnore g = new GitIgnore(repo);
        assertTrue(g.ignored("out/Keep.java", false));
    }

    @Test
    void aProjectInARepositoryUsesTheGitignoresAboveIt() throws IOException {
        Files.createDirectories(repo.resolve(".git"));
        write(".gitignore", "*.gen.java\nproject/skip/\n");
        write("project/project.jsonc", "{}");
        GitIgnore g = new GitIgnore(repo.resolve("project"));
        assertTrue(g.ignored("A.gen.java", false));
        assertTrue(g.ignored("skip/B.java", false));
        assertFalse(g.ignored("C.java", false));
    }
}
