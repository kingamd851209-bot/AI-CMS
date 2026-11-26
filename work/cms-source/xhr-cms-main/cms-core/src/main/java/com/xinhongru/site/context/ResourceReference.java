package com.xinhongru.site.context;

import java.util.Objects;

public record ResourceReference(
        ResourceId bodySite,
        ResourceId locale,
        ResourceId channel,
        ResourceId model,
        ResourceId content,
        String resourcePath) {
    public static ResourceReference currentSite() {
        return new ResourceReference(null, null, null, null, null, null);
    }

    public static ResourceReference channel(long channelId) {
        return currentSite().withChannel(IdKind.NATIVE, channelId);
    }

    public static ResourceReference legacyContent(long channelId, long contentId) {
        return channel(channelId).withContent(IdKind.LEGACY, contentId);
    }

    public static ResourceReference legacyModel(long modelId) {
        return currentSite().withModel(IdKind.LEGACY, modelId);
    }

    public static ResourceReference resourcePath(String resourcePath) {
        return new ResourceReference(null, null, null, null, null, resourcePath);
    }

    public ResourceReference withBodySite(IdKind kind, long id) {
        return new ResourceReference(
                new ResourceId(kind, id), locale, channel, model, content, resourcePath);
    }

    public ResourceReference withLocale(IdKind kind, long id) {
        return new ResourceReference(
                bodySite, new ResourceId(kind, id), channel, model, content, resourcePath);
    }

    public ResourceReference withChannel(IdKind kind, long id) {
        return new ResourceReference(
                bodySite, locale, new ResourceId(kind, id), model, content, resourcePath);
    }

    public ResourceReference withModel(IdKind kind, long id) {
        return new ResourceReference(
                bodySite, locale, channel, new ResourceId(kind, id), content, resourcePath);
    }

    public ResourceReference withContent(IdKind kind, long id) {
        return new ResourceReference(
                bodySite, locale, channel, model, new ResourceId(kind, id), resourcePath);
    }

    public enum IdKind {
        NATIVE,
        LEGACY
    }

    public record ResourceId(IdKind kind, long value) {
        public ResourceId {
            Objects.requireNonNull(kind, "ID 类型不能为空");
            if (value <= 0) {
                throw new IllegalArgumentException("资源 ID 无效");
            }
        }
    }
}
