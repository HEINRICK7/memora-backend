package com.memora.application.mural;

import com.memora.domain.mural.Mural;

public record IdempotentMural(Mural mural, String requestHash) { }
