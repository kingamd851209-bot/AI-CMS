package com.xinhongru.cms.template;

import java.util.List;

public record TraditionalTemplatePackageManifest(
        String entryPath,
        List<TraditionalTemplateFile> files,
        List<String> requiredVariables,
        long archiveBytes,
        long totalUncompressedBytes,
        String checksum,
        TraditionalTemplateRenderMode renderMode) {
    public TraditionalTemplatePackageManifest {
        files = List.copyOf(files);
        requiredVariables = List.copyOf(requiredVariables);
        renderMode =
                renderMode == null ? TraditionalTemplateRenderMode.SERVER_RENDERED : renderMode;
    }

    public TraditionalTemplatePackageManifest(
            String entryPath,
            List<TraditionalTemplateFile> files,
            List<String> requiredVariables,
            long archiveBytes,
            long totalUncompressedBytes,
            String checksum) {
        this(
                entryPath,
                files,
                requiredVariables,
                archiveBytes,
                totalUncompressedBytes,
                checksum,
                TraditionalTemplateRenderMode.SERVER_RENDERED);
    }
}
