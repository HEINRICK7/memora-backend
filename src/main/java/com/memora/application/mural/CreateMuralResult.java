package com.memora.application.mural;

import com.memora.domain.mural.Mural;

public record CreateMuralResult(Mural mural, boolean replayed) { }
