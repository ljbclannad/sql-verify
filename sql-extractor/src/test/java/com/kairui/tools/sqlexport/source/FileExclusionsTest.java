package com.kairui.tools.sqlexport.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExclusionsTest {
    @TempDir
    Path root;

    @Test
    void matchesDirectorySegmentsAndDoesNotDependOnWorkingDirectory() {
        FileExclusions exclusions = new FileExclusions("src/main/java/com/example/schema");
        assertTrue(exclusions.excludes(root.resolve("module/src/main/java/com/example/schema/Dialect.java")));
        assertTrue(exclusions.excludes(root.resolve("module/src/main/java/com/example/schema/nested/Dialect.java")));
        assertFalse(exclusions.excludes(root.resolve("module/src/main/java/com/example/schema_extra/Dialect.java")));
    }

    @Test
    void matchesAbsoluteFilesAndNormalizesPaths() {
        FileExclusions exclusions = new FileExclusions(root.resolve("ignore/../ignore/Drop.java").toString());
        assertTrue(exclusions.excludes(root.resolve("ignore/Drop.java")));
        assertFalse(exclusions.excludes(root.resolve("other/Drop.java")));
        assertFalse(exclusions.excludes(root.resolve("ignore/DropExtra.java")));
        assertThrows(IllegalArgumentException.class, () -> new FileExclusions("."));
    }
}
