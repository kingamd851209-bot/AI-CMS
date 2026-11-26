package com.xinhongru.ai.site.security;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ZipSafetyValidator {
    private final ZipSafetyPolicy policy;

    public ZipSafetyValidator() {
        this(ZipSafetyPolicy.defaults());
    }

    public ZipSafetyValidator(ZipSafetyPolicy policy) {
        this.policy = policy;
    }

    public ZipValidationResult validate(byte[] bytes) {
        List<ZipViolation> violations = new ArrayList<>();
        List<ZipEntrySummary> summaries = new ArrayList<>();
        CentralDirectoryReadResult centralDirectoryRead = readCentralDirectory(bytes, violations);
        if (!violations.isEmpty()) {
            return ZipValidationResult.rejected(violations, summaries);
        }
        if (!centralDirectoryRead.validCentralDirectory()) {
            violations.add(
                    new ZipViolation(
                            "INVALID_ZIP", null, "ZIP central directory is missing or incomplete"));
            return ZipValidationResult.rejected(violations, summaries);
        }
        Map<String, CentralDirectoryEntry> centralDirectory = centralDirectoryRead.entries();
        for (Map.Entry<String, CentralDirectoryEntry> metadata : centralDirectory.entrySet()) {
            if (!metadata.getValue().localHeaderMatches(bytes, metadata.getKey())) {
                violations.add(
                        new ZipViolation(
                                "INVALID_ZIP",
                                metadata.getKey(),
                                "ZIP central directory entry local header metadata is"
                                        + " inconsistent"));
                return ZipValidationResult.rejected(violations, summaries);
            }
        }
        byte[] buffer = new byte[8192];
        int count = 0;
        long totalUncompressed = 0L;
        Set<String> normalizedPaths = new HashSet<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (policy.isIgnoredSystemEntry(entryName)) {
                    zip.closeEntry();
                    continue;
                }
                count++;
                if (count > policy.maxEntries()) {
                    violations.add(
                            new ZipViolation(
                                    "TOO_MANY_ENTRIES",
                                    entryName,
                                    "ZIP entry count exceeds " + policy.maxEntries()));
                    break;
                }

                String normalizedPath = normalizeEntryPath(entryName, violations);
                if (!violations.isEmpty()) {
                    return ZipValidationResult.rejected(violations, summaries);
                }
                if (!normalizedPaths.add(normalizedPath)) {
                    violations.add(
                            new ZipViolation(
                                    "DUPLICATE_NORMALIZED_PATH",
                                    entryName,
                                    "ZIP entry normalizes to a duplicate path"));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                if (!entry.isDirectory() && hasHiddenDirectory(normalizedPath)) {
                    violations.add(
                            new ZipViolation(
                                    "HIDDEN_DIRECTORY",
                                    entryName,
                                    "ZIP entry is inside a hidden directory"));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                if (!entry.isDirectory() && policy.isBlockedExtension(entryName)) {
                    violations.add(
                            new ZipViolation(
                                    "BLOCKED_EXTENSION",
                                    entryName,
                                    "ZIP entry uses blocked extension"));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                if (!entry.isDirectory()
                        && !hasHiddenFileName(normalizedPath)
                        && !policy.isKnownFileExtension(entryName)) {
                    violations.add(
                            new ZipViolation(
                                    "UNKNOWN_EXTENSION",
                                    entryName,
                                    "ZIP entry uses an unsupported extension"));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                if (!entry.isDirectory() && policy.isMacOsSystemPath(entryName)) {
                    violations.add(
                            new ZipViolation(
                                    "MACOSX_NON_METADATA",
                                    entryName,
                                    "ZIP __MACOSX entry is not a safe macOS metadata file"));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                CentralDirectoryEntry metadata = centralDirectory.get(entryName);
                if (metadata == null) {
                    violations.add(
                            new ZipViolation(
                                    "INVALID_ZIP",
                                    entryName,
                                    "ZIP local header missing from central directory"));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                if (!metadata.localHeaderMatches(bytes, entryName)) {
                    violations.add(
                            new ZipViolation(
                                    "INVALID_ZIP",
                                    entryName,
                                    "ZIP central directory local header metadata is inconsistent"));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                if (metadata.symbolicLink()) {
                    violations.add(
                            new ZipViolation(
                                    "SYMBOLIC_LINK", entryName, "ZIP entry is a symbolic link"));
                    return ZipValidationResult.rejected(violations, summaries);
                }

                long entryBytes = 0L;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    totalUncompressed += read;
                    if (entryBytes > policy.maxSingleEntryBytes()) {
                        violations.add(
                                new ZipViolation(
                                        "ENTRY_TOO_LARGE",
                                        entryName,
                                        "ZIP entry exceeds "
                                                + policy.maxSingleEntryBytes()
                                                + " bytes"));
                        summaries.add(
                                new ZipEntrySummary(
                                        entryName,
                                        normalizedPath,
                                        entryBytes,
                                        compressedBytes(metadata, entry),
                                        entry.isDirectory(),
                                        normalizedPath != null
                                                && policy.isHtmlExtension(normalizedPath)));
                        return ZipValidationResult.rejected(violations, summaries);
                    }
                    if (totalUncompressed > policy.maxTotalUncompressedBytes()) {
                        violations.add(
                                new ZipViolation(
                                        "TOTAL_TOO_LARGE",
                                        entryName,
                                        "ZIP uncompressed total exceeds "
                                                + policy.maxTotalUncompressedBytes()
                                                + " bytes"));
                        summaries.add(
                                new ZipEntrySummary(
                                        entryName,
                                        normalizedPath,
                                        entryBytes,
                                        compressedBytes(metadata, entry),
                                        entry.isDirectory(),
                                        normalizedPath != null
                                                && policy.isHtmlExtension(normalizedPath)));
                        return ZipValidationResult.rejected(violations, summaries);
                    }
                }

                long compressedBytes = compressedBytes(metadata, entry);
                if (compressedBytes > 0
                        && entryBytes / (double) compressedBytes > policy.maxCompressionRatio()) {
                    violations.add(
                            new ZipViolation(
                                    "COMPRESSION_RATIO_TOO_HIGH",
                                    entryName,
                                    "ZIP entry compression ratio exceeds "
                                            + policy.maxCompressionRatio()));
                    summaries.add(
                            new ZipEntrySummary(
                                    entryName,
                                    normalizedPath,
                                    entryBytes,
                                    compressedBytes,
                                    entry.isDirectory(),
                                    normalizedPath != null
                                            && policy.isHtmlExtension(normalizedPath)));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                if (!metadata.matchesZipEntry(entry)) {
                    violations.add(
                            new ZipViolation(
                                    "INVALID_ZIP",
                                    entryName,
                                    "ZIP central directory metadata does not match local entry"));
                    summaries.add(
                            new ZipEntrySummary(
                                    entryName,
                                    normalizedPath,
                                    entryBytes,
                                    compressedBytes,
                                    entry.isDirectory(),
                                    normalizedPath != null
                                            && policy.isHtmlExtension(normalizedPath)));
                    return ZipValidationResult.rejected(violations, summaries);
                }
                summaries.add(
                        new ZipEntrySummary(
                                entryName,
                                normalizedPath,
                                entryBytes,
                                compressedBytes,
                                entry.isDirectory(),
                                normalizedPath != null && policy.isHtmlExtension(normalizedPath)));

                if (!violations.isEmpty()) {
                    break;
                }
            }
        } catch (IOException ex) {
            violations.add(new ZipViolation("INVALID_ZIP", null, "ZIP could not be read"));
        }

        if (summaries.isEmpty()) {
            violations.add(
                    new ZipViolation("INVALID_ZIP", null, "ZIP archive has no readable entries"));
        }

        if (violations.isEmpty()) {
            return ZipValidationResult.accepted(summaries);
        }
        return ZipValidationResult.rejected(violations, summaries);
    }

    public ZipSafetyPolicy policy() {
        return policy;
    }

    private static String normalizeEntryPath(String entryName, List<ZipViolation> violations) {
        if (entryName == null || entryName.isBlank()) {
            violations.add(new ZipViolation("EMPTY_PATH", entryName, "ZIP entry path is empty"));
            return null;
        }
        if (entryName.startsWith("/")
                || entryName.startsWith("\\")
                || entryName.matches("^[A-Za-z]:[\\\\/].*")) {
            violations.add(
                    new ZipViolation("ABSOLUTE_PATH", entryName, "ZIP entry path is absolute"));
            return null;
        }
        String cleaned = entryName.replace('\\', '/');
        for (String segment : cleaned.split("/")) {
            if (segment.equals("..")) {
                violations.add(
                        new ZipViolation(
                                "PATH_TRAVERSAL", entryName, "ZIP entry path escapes import root"));
                return null;
            }
        }
        Path normalized = Path.of(cleaned).normalize();
        String normalizedText = normalized.toString().replace('\\', '/');
        if (normalized.isAbsolute() || normalizedText.isBlank() || normalizedText.equals(".")) {
            violations.add(
                    new ZipViolation("INVALID_PATH", entryName, "ZIP entry path is invalid"));
            return null;
        }
        if (normalizedText.equals("..")
                || normalizedText.startsWith("../")
                || normalizedText.contains("/../")) {
            violations.add(
                    new ZipViolation(
                            "PATH_TRAVERSAL", entryName, "ZIP entry path escapes import root"));
            return null;
        }
        return normalizedText;
    }

    private static boolean hasHiddenDirectory(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return false;
        }
        String[] segments = normalizedPath.replace('\\', '/').split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].startsWith(".") && segments[i].length() > 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHiddenFileName(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return false;
        }
        String[] segments = normalizedPath.replace('\\', '/').split("/");
        String filename = segments.length == 0 ? normalizedPath : segments[segments.length - 1];
        return filename.startsWith(".") && filename.length() > 1;
    }

    private static long compressedBytes(CentralDirectoryEntry metadata, ZipEntry entry) {
        return metadata == null
                ? Math.max(entry.getCompressedSize(), -1L)
                : metadata.compressedSize();
    }

    private static CentralDirectoryReadResult readCentralDirectory(
            byte[] bytes, List<ZipViolation> violations) {
        Map<String, CentralDirectoryEntry> entries = new HashMap<>();
        try {
            int eocdOffset = findEndOfCentralDirectory(bytes);
            if (eocdOffset < 0) {
                return new CentralDirectoryReadResult(Map.of(), false);
            }
            if (!hasBytes(bytes, eocdOffset, 22)) {
                violations.add(
                        new ZipViolation(
                                "INVALID_ZIP", null, "ZIP central directory is truncated"));
                return new CentralDirectoryReadResult(entries, false);
            }
            int entryCount = littleEndianShort(bytes, eocdOffset + 10);
            long centralDirectorySize =
                    Integer.toUnsignedLong(littleEndianInt(bytes, eocdOffset + 12));
            long centralDirectoryOffset =
                    Integer.toUnsignedLong(littleEndianInt(bytes, eocdOffset + 16));
            if (centralDirectoryOffset > bytes.length
                    || centralDirectorySize > bytes.length
                    || centralDirectoryOffset + centralDirectorySize > bytes.length
                    || centralDirectoryOffset + centralDirectorySize != eocdOffset) {
                violations.add(
                        new ZipViolation(
                                "INVALID_ZIP", null, "ZIP central directory offset is invalid"));
                return new CentralDirectoryReadResult(entries, false);
            }
            int cursor = (int) centralDirectoryOffset;
            for (int i = 0; i < entryCount; i++) {
                if (!hasBytes(bytes, cursor, 46) || littleEndianInt(bytes, cursor) != 0x02014b50) {
                    violations.add(
                            new ZipViolation(
                                    "INVALID_ZIP", null, "ZIP central directory entry is invalid"));
                    return new CentralDirectoryReadResult(entries, false);
                }
                int method = littleEndianShort(bytes, cursor + 10);
                long crc32 = Integer.toUnsignedLong(littleEndianInt(bytes, cursor + 16));
                long compressedSize = Integer.toUnsignedLong(littleEndianInt(bytes, cursor + 20));
                long uncompressedSize = Integer.toUnsignedLong(littleEndianInt(bytes, cursor + 24));
                int fileNameLength = littleEndianShort(bytes, cursor + 28);
                int extraLength = littleEndianShort(bytes, cursor + 30);
                int commentLength = littleEndianShort(bytes, cursor + 32);
                int externalAttributes = littleEndianInt(bytes, cursor + 38);
                long localHeaderOffset =
                        Integer.toUnsignedLong(littleEndianInt(bytes, cursor + 42));
                int nameStart = cursor + 46;
                long nextCursor = (long) nameStart + fileNameLength + extraLength + commentLength;
                if (fileNameLength <= 0 || nextCursor > bytes.length || nextCursor > eocdOffset) {
                    violations.add(
                            new ZipViolation(
                                    "INVALID_ZIP",
                                    null,
                                    "ZIP central directory entry length is invalid"));
                    return new CentralDirectoryReadResult(entries, false);
                }
                String name = new String(bytes, nameStart, fileNameLength, StandardCharsets.UTF_8);
                int unixMode = (externalAttributes >>> 16) & 0xFFFF;
                boolean symbolicLink = (unixMode & 0170000) == 0120000;
                if (entries.containsKey(name)) {
                    violations.add(
                            new ZipViolation(
                                    "INVALID_ZIP",
                                    name,
                                    "ZIP central directory contains duplicate entry name"));
                    return new CentralDirectoryReadResult(entries, false);
                }
                entries.put(
                        name,
                        new CentralDirectoryEntry(
                                compressedSize,
                                uncompressedSize,
                                crc32,
                                method,
                                localHeaderOffset,
                                symbolicLink));
                cursor = (int) nextCursor;
            }
            if (cursor != centralDirectoryOffset + centralDirectorySize) {
                violations.add(
                        new ZipViolation(
                                "INVALID_ZIP",
                                null,
                                "ZIP central directory size does not match entry count"));
                return new CentralDirectoryReadResult(entries, false);
            }
            return new CentralDirectoryReadResult(entries, true);
        } catch (RuntimeException ex) {
            violations.add(
                    new ZipViolation(
                            "INVALID_ZIP", null, "ZIP central directory could not be parsed"));
            return new CentralDirectoryReadResult(Map.of(), false);
        }
    }

    private static int findEndOfCentralDirectory(byte[] bytes) {
        for (int cursor = bytes.length - 22; cursor >= 0; cursor--) {
            if (littleEndianInt(bytes, cursor) == 0x06054b50) {
                return cursor;
            }
        }
        return -1;
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static boolean hasBytes(byte[] bytes, int offset, int length) {
        return offset >= 0 && length >= 0 && offset <= bytes.length - length;
    }

    private record CentralDirectoryEntry(
            long compressedSize,
            long uncompressedSize,
            long crc32,
            int method,
            long localHeaderOffset,
            boolean symbolicLink) {
        private boolean localHeaderMatches(byte[] bytes, String expectedName) {
            if (localHeaderOffset > Integer.MAX_VALUE) {
                return false;
            }
            int offset = (int) localHeaderOffset;
            if (!hasBytes(bytes, offset, 30) || littleEndianInt(bytes, offset) != 0x04034b50) {
                return false;
            }
            int localMethod = littleEndianShort(bytes, offset + 8);
            if (localMethod != method) {
                return false;
            }
            int fileNameLength = littleEndianShort(bytes, offset + 26);
            int extraLength = littleEndianShort(bytes, offset + 28);
            int nameStart = offset + 30;
            long dataStart = (long) nameStart + fileNameLength + extraLength;
            if (fileNameLength <= 0 || dataStart > bytes.length) {
                return false;
            }
            String localName = new String(bytes, nameStart, fileNameLength, StandardCharsets.UTF_8);
            return expectedName.equals(localName);
        }

        private boolean matchesZipEntry(ZipEntry entry) {
            if (entry.getMethod() != -1 && entry.getMethod() != method) {
                return false;
            }
            if (entry.getCrc() != -1L && entry.getCrc() != crc32) {
                return false;
            }
            if (entry.getCompressedSize() != -1L && entry.getCompressedSize() != compressedSize) {
                return false;
            }
            return entry.getSize() == -1L || entry.getSize() == uncompressedSize;
        }
    }

    private record CentralDirectoryReadResult(
            Map<String, CentralDirectoryEntry> entries, boolean validCentralDirectory) {}
}
