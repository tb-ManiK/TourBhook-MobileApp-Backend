package com.tourbhook.api.service;

import com.tourbhook.api.dto.dataimport.ImportFeedbackRequest;
import com.tourbhook.api.dto.dataimport.ImportFeedbackResponse;

public interface ImportFeedbackService {

    ImportFeedbackResponse submitFeedback(ImportFeedbackRequest request);
}