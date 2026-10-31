package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.entity.ImportSourceType;


public interface ImportParser {

    ImportSourceType sourceType();

    ParseResult parse(String content);
}