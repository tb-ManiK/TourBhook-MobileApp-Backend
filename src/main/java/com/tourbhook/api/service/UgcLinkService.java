package com.tourbhook.api.service;

import com.tourbhook.api.dto.ugc.SubmitUgcLinkRequest;
import com.tourbhook.api.dto.ugc.UgcLinkResponse;
import java.util.List;

public interface UgcLinkService {

    UgcLinkResponse submitLink(SubmitUgcLinkRequest request);

    List<UgcLinkResponse> getLinksForDestination(String destination, int page, int size);

    List<UgcLinkResponse> getMyLinks(int page, int size);

    void removeLink(String linkId);
}