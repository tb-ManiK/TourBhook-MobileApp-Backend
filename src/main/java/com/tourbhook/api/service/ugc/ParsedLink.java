package com.tourbhook.api.service.ugc;

import com.tourbhook.api.entity.UgcLinkPlatform;

public record ParsedLink(UgcLinkPlatform platform, String externalId, String canonicalUrl) {
}