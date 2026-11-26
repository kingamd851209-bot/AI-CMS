package com.xinhongru.cms.column;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PromptGenerationException extends RuntimeException {
    public PromptGenerationException(String message) {
        super(message);
    }
}
